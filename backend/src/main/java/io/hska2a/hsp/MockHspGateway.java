package io.hska2a.hsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hska2a.common.Amounts;
import io.hska2a.common.CanonicalJson;
import io.hska2a.common.Eth;
import io.hska2a.config.HskProperties;
import io.hska2a.crypto.Signers;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local stand-in for HSP Agentic Payment with the same 8 tools and response shapes, used until HSP allow-lists
 * the server IP. It really checks the payer's EIP-712 signature, but it does NOT move tokens and never invents a
 * transaction hash: query_payment returns an empty tx_signature and "simulated": true.
 */
public class MockHspGateway implements HspGateway {
    private static final String[] PROGRESSION = {
        "payment-submitted", "payment-verified", "payment-processing", "payment-included", "payment-safe"
    };

    private final ObjectMapper mapper = new ObjectMapper();
    private final HskProperties props;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, ObjectNode> orders = new ConcurrentHashMap<>();
    private final long stepMillis;

    public MockHspGateway(HskProperties props, long stepMillis) {
        this.props = props;
        this.stepMillis = stepMillis;
    }

    @Override public String mode() { return "mock"; }
    @Override public String health() { return "simulated locally (no HSP network calls)"; }

    @Override
    public JsonNode getChainConfig() {
        HskProperties.Token t = props.getToken();
        ObjectNode cfg = mapper.createObjectNode();
        cfg.put("simulated", true);
        ArrayNode nets = cfg.putArray("networks");
        nets.addObject().put("chain_id", props.getChain().getChainId()).put("name", t.getNetwork())
                .put("display_name", "HashKey Chain" + (props.getChain().getChainId() == 177 ? "" : " Testnet"))
                .put("is_testnet", props.getChain().getChainId() != 177);
        cfg.putArray("tokens").addObject().put("chain_id", props.getChain().getChainId()).put("symbol", t.getSymbol())
                .put("name", "USD Coin").put("address", t.getAddress()).put("decimals", t.getDecimals())
                .put("version", "2").put("payment_protocol", t.getProtocol());
        return cfg;
    }

    @Override
    public JsonNode createOrder(String merchantName, String payTo, ObjectNode cartMandate) {
        JsonNode contents = cartMandate.path("contents");
        if (!Eth.isAddress(payTo)) throw new HspException("create_order: pay_to must be a 0x address");
        if (contents.path("id").asText().isBlank()) throw new HspException("create_order: contents.id required");
        String currency = contents.path("payment_request").path("details").path("total").path("amount").path("currency").asText();
        if (!"USD".equals(currency)) throw new HspException("create_order: amount.currency must be USD");
        for (ObjectNode o : orders.values()) {
            if (o.path("cart_mandate_id").asText().equals(contents.path("id").asText())) throw new HspException("create_order: 40001 duplicate cart_mandate_id");
        }
        String flowId = UUID.randomUUID().toString();
        ObjectNode order = mapper.createObjectNode();
        order.put("flow_id", flowId);
        order.put("merchant_name", merchantName);
        order.put("pay_to", payTo);
        order.put("cart_mandate_id", contents.path("id").asText());
        order.put("payment_request_id", contents.path("payment_request").path("details").path("id").asText());
        order.put("usd_total", contents.path("payment_request").path("details").path("total").path("amount").path("value").asText());
        order.put("cart_hash", CanonicalJson.sha256Hex(CanonicalJson.write(contents)));
        order.put("status", "payment-required");
        order.set("method_data", contents.path("payment_request").path("method_data"));
        orders.put(flowId, order);
        ObjectNode res = mapper.createObjectNode();
        res.put("merchant_name", merchantName).put("flow_id", flowId)
                .put("payment_request_id", order.get("payment_request_id").asText())
                .put("payment_url", "mock://hsp/flow/" + flowId).put("simulated", true);
        return res;
    }

    @Override
    public JsonNode getOrder(String flowId) {
        ObjectNode o = order(flowId);
        ObjectNode res = o.deepCopy();
        res.putArray("gas_fee_configs").addObject().put("network", props.getToken().getNetwork()).put("gas_fee_advanced", true);
        res.put("simulated", true);
        return res;
    }

    @Override
    public JsonNode preparePayment(String flowId, String payer, String protocol) {
        ObjectNode o = order(flowId);
        if (!"eip3009".equals(protocol)) throw new HspException("prepare_payment: mock supports eip3009 only");
        if (!Eth.isAddress(payer)) throw new HspException("prepare_payment: payer must be a 0x address");
        HskProperties.Token t = props.getToken();
        BigInteger raw = Amounts.toRaw(new BigDecimal(o.get("usd_total").asText()), t.getDecimals());
        long validBefore = Instant.now().getEpochSecond() + 600;
        byte[] nonce = new byte[32];
        random.nextBytes(nonce);

        ObjectNode eip712 = mapper.createObjectNode();
        eip712.putObject("domain").put("name", "USD Coin").put("version", "2")
                .put("chainId", props.getChain().getChainId()).put("verifyingContract", t.getAddress());
        ObjectNode types = eip712.putObject("types");
        ArrayNode twa = types.putArray("TransferWithAuthorization");
        for (String[] f : new String[][] {{"from", "address"}, {"to", "address"}, {"value", "uint256"},
                {"validAfter", "uint256"}, {"validBefore", "uint256"}, {"nonce", "bytes32"}}) {
            twa.addObject().put("name", f[0]).put("type", f[1]);
        }
        eip712.put("primaryType", "TransferWithAuthorization");
        eip712.putObject("message").put("from", payer).put("to", o.get("pay_to").asText()).put("value", raw.toString())
                .put("validAfter", "0").put("validBefore", String.valueOf(validBefore))
                .put("nonce", "0x" + HexFormat.of().formatHex(nonce));

        o.put("payer", payer);
        o.put("token_total_raw", raw.toString());
        o.set("eip712", eip712);

        ObjectNode res = mapper.createObjectNode();
        res.set("eip712", eip712);
        res.put("cart_hash", o.get("cart_hash").asText());
        res.put("token_total_raw", raw.toString());
        res.put("quote_expires_at", Instant.now().plusSeconds(300).toString());
        res.put("pay_to", o.get("pay_to").asText());
        res.put("payer", payer);
        res.put("payment_request_id", o.get("payment_request_id").asText());
        res.putObject("payment_payload").put("x402Version", 2).put("scheme", "exact")
                .put("network", t.getNetwork()).putObject("payload").put("signature", "");
        res.putObject("payment_mandate_contents").put("payment_mandate_id", o.get("payment_request_id").asText())
                .put("payment_details_id", o.get("payment_request_id").asText()).put("flow_id", flowId);
        res.put("simulated", true);
        return res;
    }

    @Override
    public JsonNode finalizePayment(String signature, JsonNode paymentPayload, JsonNode contents, String cartHash) {
        String flowId = contents.path("flow_id").asText();
        ObjectNode o = order(flowId);
        if (!o.get("cart_hash").asText().equals(cartHash)) throw new HspException("finalize_payment: cart_hash mismatch");
        String signer = Signers.recoverTypedData(o.get("eip712"), signature);
        if (!Eth.sameAddress(signer, o.get("payer").asText())) throw new HspException("finalize_payment: signature is not from payer");
        ObjectNode mandate = mapper.createObjectNode();
        mandate.set("payment_mandate_contents", contents);
        ObjectNode payload = paymentPayload.deepCopy();
        ((ObjectNode) payload.with("payload")).put("signature", signature);
        mandate.set("payment_payload", payload);
        mandate.put("user_authorization", "simulated-jwt");
        String mandateJson = mandate.toString();
        o.put("payment_mandate_json", mandateJson);
        ObjectNode res = mapper.createObjectNode();
        res.put("payment_mandate_json", mandateJson);
        res.put("payment_hash", CanonicalJson.sha256Hex(mandateJson));
        res.put("cart_hash", cartHash);
        res.put("simulated", true);
        return res;
    }

    @Override
    public JsonNode submitPayment(JsonNode paymentMandateJson) {
        String json = paymentMandateJson.isTextual() ? paymentMandateJson.asText() : paymentMandateJson.toString();
        for (ObjectNode o : orders.values()) {
            if (json.equals(o.path("payment_mandate_json").asText())) {
                if (o.has("submitted_at")) throw new HspException("submit_payment: already submitted");
                o.put("submitted_at", System.currentTimeMillis());
                return mapper.createObjectNode().put("status", "payment-submitted").put("flow_id", o.get("flow_id").asText()).put("simulated", true);
            }
        }
        throw new HspException("submit_payment: unknown payment mandate");
    }

    @Override
    public JsonNode queryPayment(String flowId, String expectedMerchantName) {
        ObjectNode o = order(flowId);
        String status = "payment-required";
        if (o.has("submitted_at")) {
            int step = (int) Math.min(PROGRESSION.length - 1, (System.currentTimeMillis() - o.get("submitted_at").asLong()) / stepMillis);
            status = PROGRESSION[step];
        }
        ObjectNode res = mapper.createObjectNode();
        res.put("merchant_name", o.get("merchant_name").asText());
        res.put("expected_merchant_name", expectedMerchantName);
        res.put("attribution_verified", o.get("merchant_name").asText().equals(expectedMerchantName));
        res.put("flow_id", flowId);
        res.put("payment_request_id", o.get("payment_request_id").asText());
        res.put("status", status);
        res.put("simulated", true);
        ObjectNode p = res.putObject("payment");
        p.put("status", status).put("gas_fee_advanced", true)
                .put("chain", "eip155:" + props.getChain().getChainId()).put("network", props.getToken().getNetwork())
                .put("payer_address", o.path("payer").asText()).put("to_pay_address", o.get("pay_to").asText())
                .put("token", props.getToken().getSymbol()).put("token_address", props.getToken().getAddress())
                .put("pay_amount", o.get("usd_total").asText()).put("tx_signature", "")
                .put("status_reason", "simulated: no tokens moved, no transaction broadcast");
        return res;
    }

    private ObjectNode order(String flowId) {
        ObjectNode o = orders.get(flowId);
        if (o == null) throw new HspException("unknown flow_id " + flowId);
        return o;
    }
}
