package io.hska2a.hsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal MCP (Streamable HTTP, JSON-RPC 2.0) client for HSP Agentic Payment at https://mcp-qa.hashkeymerchant.com/.
 * No App Key / Secret: HSP manages them server-side. The QA endpoint only answers allow-listed egress IPs.
 */
public class McpHspGateway implements HspGateway {
    static final List<String> REQUIRED_TOOLS = List.of(
            "get_chain_config", "create_order", "get_order", "prepare_payment",
            "finalize_payment", "estimate_fee", "submit_payment", "query_payment");

    private static final Logger log = LoggerFactory.getLogger(McpHspGateway.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final String url;
    private final AtomicLong ids = new AtomicLong(1);
    private volatile String sessionId;
    private volatile String protocolVersion = "2025-03-26";
    private volatile List<String> tools = List.of();

    public McpHspGateway(String url) {
        this.url = url;
    }

    @Override public String mode() { return "mcp"; }

    @Override
    public String health() {
        try {
            String base = url.endsWith("/") ? url : url + "/";
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "health")).timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 403) return "blocked (HTTP 403): this server's egress IP is not on the HSP QA allowlist yet";
            return "HTTP " + r.statusCode() + " " + r.body().substring(0, Math.min(80, r.body().length()));
        } catch (Exception e) {
            return "unreachable: " + e.getMessage();
        }
    }

    public synchronized List<String> connect() {
        if (sessionId != null && !tools.isEmpty()) return tools;
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", protocolVersion);
        params.putObject("capabilities");
        params.putObject("clientInfo").put("name", "hsk-a2a-pay").put("version", "0.1.0");
        JsonNode init = rpc("initialize", params, true);
        if (init.has("protocolVersion")) protocolVersion = init.get("protocolVersion").asText();
        notifyInitialized();
        JsonNode listed = rpc("tools/list", mapper.createObjectNode(), false);
        List<String> names = new ArrayList<>();
        listed.path("tools").forEach(t -> names.add(t.path("name").asText()));
        List<String> missing = REQUIRED_TOOLS.stream().filter(t -> !names.contains(t)).toList();
        if (!missing.isEmpty()) throw new HspException("HSP MCP is missing tools " + missing + "; refusing to pay");
        tools = names;
        log.info("HSP MCP connected, {} tools", names.size());
        return names;
    }

    public JsonNode call(String tool, ObjectNode args) {
        connect();
        ObjectNode params = mapper.createObjectNode();
        params.put("name", tool);
        params.set("arguments", args);
        JsonNode result;
        try {
            result = rpc("tools/call", params, false);
        } catch (HspException e) {
            if (e.getMessage().contains("HTTP 404")) { // session expired -> reconnect once
                sessionId = null;
                tools = List.of();
                connect();
                result = rpc("tools/call", params, false);
            } else {
                throw e;
            }
        }
        StringBuilder text = new StringBuilder();
        result.path("content").forEach(c -> { if (c.has("text")) text.append(c.get("text").asText()); });
        if (result.path("isError").asBoolean(false)) throw new HspException(tool + ": " + text);
        if (result.has("structuredContent") && !result.get("structuredContent").isNull()) return result.get("structuredContent");
        try {
            return mapper.readTree(text.toString());
        } catch (Exception e) {
            throw new HspException(tool + ": non-JSON result: " + text.substring(0, Math.min(200, text.length())));
        }
    }

    private void notifyInitialized() {
        ObjectNode msg = mapper.createObjectNode().put("jsonrpc", "2.0").put("method", "notifications/initialized");
        try {
            http.send(request(msg.toString()), HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            throw new HspException("MCP initialized notification failed", e);
        }
    }

    private JsonNode rpc(String method, JsonNode params, boolean captureSession) {
        long id = ids.getAndIncrement();
        ObjectNode msg = mapper.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", method);
        msg.set("params", params);
        HttpResponse<String> r;
        try {
            r = http.send(request(msg.toString()), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new HspException("MCP " + method + " failed: " + e.getMessage(), e);
        }
        if (r.statusCode() == 403) throw new HspException("HSP QA returned HTTP 403: egress IP not allow-listed. Ask HSP to allowlist this server's IP.");
        if (r.statusCode() >= 400) throw new HspException("MCP " + method + " HTTP " + r.statusCode() + ": " + r.body());
        if (captureSession) r.headers().firstValue("mcp-session-id").ifPresent(s -> sessionId = s);
        JsonNode envelope = parseBody(r, id);
        if (envelope.has("error")) throw new HspException("MCP " + method + " error: " + envelope.get("error"));
        return envelope.path("result");
    }

    private JsonNode parseBody(HttpResponse<String> r, long id) {
        String ct = r.headers().firstValue("content-type").orElse("");
        try {
            if (ct.contains("text/event-stream")) {
                for (String line : r.body().split("\n")) {
                    if (!line.startsWith("data:")) continue;
                    JsonNode n = mapper.readTree(line.substring(5).trim());
                    if (n.path("id").asLong(-1) == id) return n;
                }
                throw new HspException("no JSON-RPC response for id " + id + " in event stream");
            }
            return mapper.readTree(r.body());
        } catch (HspException e) {
            throw e;
        } catch (Exception e) {
            throw new HspException("cannot parse MCP response: " + e.getMessage(), e);
        }
    }

    private HttpRequest request(String body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", protocolVersion)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (sessionId != null) b.header("Mcp-Session-Id", sessionId);
        return b.build();
    }

    @Override public JsonNode getChainConfig() { return call("get_chain_config", mapper.createObjectNode()); }

    @Override
    public JsonNode createOrder(String merchantName, String payTo, ObjectNode cartMandate) {
        ObjectNode a = mapper.createObjectNode().put("merchant_name", merchantName).put("pay_to", payTo);
        a.set("cart_mandate", cartMandate);
        return call("create_order", a);
    }

    @Override public JsonNode getOrder(String flowId) { return call("get_order", mapper.createObjectNode().put("flow_id", flowId)); }

    @Override
    public JsonNode preparePayment(String flowId, String payer, String protocol) {
        return call("prepare_payment", mapper.createObjectNode().put("flow_id", flowId).put("payer", payer).put("protocol", protocol));
    }

    @Override
    public JsonNode finalizePayment(String signature, JsonNode paymentPayload, JsonNode paymentMandateContents, String cartHash) {
        ObjectNode a = mapper.createObjectNode().put("signature", signature).put("cart_hash", cartHash);
        a.set("payment_payload", paymentPayload);
        a.set("payment_mandate_contents", paymentMandateContents);
        return call("finalize_payment", a);
    }

    @Override
    public JsonNode submitPayment(JsonNode paymentMandateJson) {
        ObjectNode a = mapper.createObjectNode();
        a.set("payment_mandate_json", paymentMandateJson); // passed through unchanged (string or object, as returned)
        return call("submit_payment", a);
    }

    @Override
    public JsonNode queryPayment(String flowId, String expectedMerchantName) {
        return call("query_payment", mapper.createObjectNode().put("flow_id", flowId).put("expected_merchant_name", expectedMerchantName));
    }
}
