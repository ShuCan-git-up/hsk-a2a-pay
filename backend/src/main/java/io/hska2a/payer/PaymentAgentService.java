package io.hska2a.payer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hska2a.chain.ChainService;
import io.hska2a.common.Amounts;
import io.hska2a.common.ApiException;
import io.hska2a.common.CanonicalJson;
import io.hska2a.common.Eth;
import io.hska2a.config.HskProperties;
import io.hska2a.crypto.Signers;
import io.hska2a.crypto.TypedDataFirewall;
import io.hska2a.hsp.HspGateway;
import io.hska2a.payer.PaymentRun.State;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Credentials;

/**
 * Paying agent. Discovers the receiving agent, verifies its signed terms, checks spend policy (off-chain caps and
 * the on-chain A2ASettlement policy), builds the HSP cart mandate, runs prepare_payment -> firewall -> human
 * approval -> EIP-712 signature -> finalize_payment -> human approval -> submit_payment -> query_payment, and then
 * hands the receipt to the receiving agent, which verifies with HSP on its own before delivering.
 */
@Service
public class PaymentAgentService {
    private static final Set<String> STOP = Set.of("payment-safe", "payment-finalized", "payment-failed");
    private static final Logger log = LoggerFactory.getLogger(PaymentAgentService.class);

    private final HskProperties props;
    private final HspGateway hsp;
    private final ChainService chain;
    private final Environment env;
    private final Credentials payer;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Map<String, PaymentRun> runs = new ConcurrentHashMap<>();
    private final Map<LocalDate, BigDecimal> spentUsd = new ConcurrentHashMap<>();

    public PaymentAgentService(HskProperties props, HspGateway hsp, ChainService chain, Environment env) {
        this.props = props;
        this.hsp = hsp;
        this.chain = chain;
        this.env = env;
        this.payer = Signers.credentials(props.getPayer().getPrivateKey());
    }

    public String payerAddress() {
        return payer == null ? null : Eth.checksum(payer.getAddress());
    }

    public PaymentRun start(String target) {
        if (payer == null) throw ApiException.unavailable("payer key not configured (PAYER_PRIVATE_KEY)");
        if (!Eth.isAddress(target)) throw ApiException.badRequest("target must be a 0x address");
        PaymentRun run = new PaymentRun(UUID.randomUUID().toString().substring(0, 8), Eth.checksum(target));
        runs.put(run.getId(), run);
        pool.submit(() -> guarded(run, () -> discoverAndPrepare(run)));
        return run;
    }

    public PaymentRun approve(String id, String stage) {
        PaymentRun run = get(id);
        if ("sign".equals(stage) && run.getState() == State.AWAITING_SIGN_APPROVAL) {
            run.step("approval", "Human approved the signature", Map.of("stage", "sign"));
            pool.submit(() -> guarded(run, () -> signAndFinalize(run)));
        } else if ("submit".equals(stage) && run.getState() == State.AWAITING_SUBMIT_APPROVAL) {
            run.step("approval", "Human approved the submission", Map.of("stage", "submit"));
            pool.submit(() -> guarded(run, () -> submitAndSettle(run)));
        } else {
            throw ApiException.conflict("run " + id + " is " + run.getState() + ", cannot approve " + stage);
        }
        return run;
    }

    public PaymentRun reject(String id) {
        PaymentRun run = get(id);
        if (run.getState() != State.AWAITING_SIGN_APPROVAL && run.getState() != State.AWAITING_SUBMIT_APPROVAL) {
            throw ApiException.conflict("nothing to reject in state " + run.getState());
        }
        run.step("rejected", "Human rejected. Nothing was signed or submitted after this point.", null);
        run.state(State.REJECTED);
        return run;
    }

    public PaymentRun get(String id) {
        PaymentRun r = runs.get(id);
        if (r == null) throw ApiException.notFound("run " + id);
        return r;
    }

    public List<PaymentRun> list() {
        List<PaymentRun> l = new ArrayList<>(runs.values());
        l.sort(Comparator.comparing(PaymentRun::getCreatedAt).reversed());
        return l;
    }

    // ------------------------------------------------------------------ steps

    private void discoverAndPrepare(PaymentRun run) throws Exception {
        String base = receiverBase();
        JsonNode card = getJson(base + "/.well-known/agent-card.json");
        JsonNode pay = card.path("payment");
        run.step("discover", "Fetched agent card of " + card.path("name").asText(), Map.of("url", base + "/.well-known/agent-card.json",
                "payTo", pay.path("payTo").asText(), "termsSigner", pay.path("termsSigner").asText()));

        ObjectNode qreq = mapper.createObjectNode().put("skill", "wallet-insight").put("target", run.getTarget());
        JsonNode quote = postJson(card.path("endpoints").path("quote").asText(base + "/a2a/receiver/quote"), qreq);
        JsonNode terms = quote.path("terms");
        run.terms = terms;
        String recovered = Signers.recoverPersonal(CanonicalJson.write(terms), quote.path("signature").asText());
        if (!Eth.sameAddress(recovered, pay.path("termsSigner").asText())) fail("terms signature is not from the agent card's signer");
        if (!Eth.sameAddress(terms.path("payTo").asText(), pay.path("payTo").asText())) fail("terms payTo differs from agent card");
        Map<String, Object> qd = new LinkedHashMap<>();
        qd.put("termsId", terms.path("termsId").asText());
        qd.put("priceUsd", terms.path("priceUsd").asText());
        qd.put("payTo", terms.path("payTo").asText());
        qd.put("signer", recovered);
        if (quote.has("anchor")) qd.put("publishTx", quote.path("anchor").path("txHash").asText());
        if (quote.has("anchorError")) qd.put("anchorError", quote.path("anchorError").asText());
        run.step("quote", "Received signed payment terms (signature verified)", qd);
        run.put("termsId", terms.path("termsId").asText());
        run.put("priceUsd", terms.path("priceUsd").asText());
        run.put("payTo", terms.path("payTo").asText());
        run.put("payer", payerAddress());
        if (quote.has("anchor")) run.put("publishTermsTx", quote.path("anchor").path("explorerUrl").asText());
        run.state(State.QUOTED);

        policyCheck(run, terms);

        // Network / token / protocol only from get_chain_config.
        JsonNode cfg = hsp.getChainConfig();
        JsonNode token = null;
        for (JsonNode t : cfg.path("tokens")) {
            if (t.path("chain_id").asLong() == terms.path("chainId").asLong() && Eth.sameAddress(t.path("address").asText(), terms.path("token").asText())) token = t;
        }
        if (token == null) fail("HSP get_chain_config has no token " + terms.path("token").asText() + " on chain " + terms.path("chainId").asLong());
        if (!"eip3009".equals(token.path("payment_protocol").asText())) fail("token protocol is " + token.path("payment_protocol").asText() + "; this MVP signs EIP-3009 only");
        String network = terms.path("network").asText();
        for (JsonNode n : cfg.path("networks")) if (n.path("chain_id").asLong() == terms.path("chainId").asLong()) network = n.path("name").asText(network);
        run.step("chain_config", "HSP get_chain_config: " + token.path("symbol").asText() + " on " + network + " via " + token.path("payment_protocol").asText(),
                Map.of("token", token.path("address").asText(), "decimals", token.path("decimals").asInt()));

        ObjectNode cart = mapper.createObjectNode();
        cart.set("contents", cartContents(terms, network, token));
        JsonNode order = hsp.createOrder(terms.path("merchantName").asText(), terms.path("payTo").asText(), cart);
        run.flowId = order.path("flow_id").asText();
        run.put("flowId", run.flowId);
        run.put("paymentRequestId", order.path("payment_request_id").asText());
        run.put("paymentUrl", order.path("payment_url").asText());
        run.step("create_order", "HSP cart mandate created", Map.of("flow_id", run.flowId,
                "payment_request_id", order.path("payment_request_id").asText(), "payment_url", order.path("payment_url").asText()));
        run.state(State.ORDER_CREATED);

        JsonNode info = hsp.getOrder(run.flowId);
        boolean gasAdvanced = true;
        for (JsonNode g : info.path("gas_fee_configs")) if (g.path("network").asText().equals(network)) gasAdvanced = g.path("gas_fee_advanced").asBoolean(true);
        run.put("gasFeeAdvanced", gasAdvanced);

        JsonNode prep = hsp.preparePayment(run.flowId, payer.getAddress(), "eip3009");
        run.prepared = prep;
        BigInteger price = new BigInteger(terms.path("amountRaw").asText());
        BigInteger cap = price.add(price.multiply(BigInteger.valueOf(props.getPayer().getMaxOverPricePct())).divide(BigInteger.valueOf(100)));
        List<String> violations = TypedDataFirewall.check(prep.path("eip712"), new TypedDataFirewall.Expectation(
                terms.path("chainId").asLong(), token.path("address").asText(), payer.getAddress(), terms.path("payTo").asText(),
                cap, prep.path("token_total_raw").asText(null), props.getPayer().getMaxAuthorizationSeconds(), Instant.now()));
        if (!violations.isEmpty()) fail("signing firewall refused: " + String.join("; ", violations));
        run.put("tokenTotalRaw", prep.path("token_total_raw").asText());
        run.step("prepare_payment", "EIP-712 TransferWithAuthorization received and checked by the signing firewall", Map.of(
                "pay_to", prep.path("pay_to").asText(), "payer", prep.path("payer").asText(),
                "token_total_raw", prep.path("token_total_raw").asText(), "quote_expires_at", prep.path("quote_expires_at").asText()));

        if (hsp.mode().equals("mcp") && chain != null) {
            BigInteger bal = chain.erc20Balance(token.path("address").asText(), payer.getAddress());
            if (bal.compareTo(new BigInteger(prep.path("token_total_raw").asText())) < 0) {
                fail("payer holds " + bal + " raw " + token.path("symbol").asText() + ", needs " + prep.path("token_total_raw").asText() + ". Fund the payer wallet manually.");
            }
        }
        run.state(State.AWAITING_SIGN_APPROVAL);
        run.step("awaiting_approval", "Waiting for approval #1 (sign). Recipient, payer and raw amount are shown above.", null);
        if (props.getPayer().isAutoApprove()) approve(run.getId(), "sign");
    }

    private void signAndFinalize(PaymentRun run) {
        JsonNode prep = run.prepared;
        String exp = prep.path("quote_expires_at").asText("");
        if (!exp.isBlank() && Instant.parse(exp).isBefore(Instant.now())) fail("quote expired at " + exp + "; start a new run (old signature is never reused)");
        String sig = Signers.signTypedData(prep.path("eip712"), payer);
        run.step("sign", "Payer wallet signed the EIP-712 authorization locally (key never leaves the server)", Map.of("signer", payer.getAddress()));
        JsonNode fin = hsp.finalizePayment(sig, prep.path("payment_payload"), prep.path("payment_mandate_contents"), prep.path("cart_hash").asText());
        run.finalized = fin;
        run.step("finalize_payment", "HSP returned the Payment Mandate", Map.of("payment_hash", fin.path("payment_hash").asText("")));
        run.state(State.AWAITING_SUBMIT_APPROVAL);
        run.step("awaiting_approval", "Waiting for approval #2 (submit to HSP Gateway)", null);
        if (props.getPayer().isAutoApprove()) approve(run.getId(), "submit");
    }

    private void submitAndSettle(PaymentRun run) throws Exception {
        JsonNode sub = hsp.submitPayment(run.finalized.path("payment_mandate_json"));
        run.step("submit_payment", "Payment Mandate submitted to HSP Gateway: " + sub.path("status").asText("submitted"), null);
        run.state(State.SUBMITTED);
        BigDecimal price = new BigDecimal(run.terms.path("priceUsd").asText());
        spentUsd.merge(LocalDate.now(ZoneOffset.UTC), price, BigDecimal::add);

        String merchant = run.terms.path("merchantName").asText();
        long started = System.currentTimeMillis();
        long pendingAfter = props.getHsp().getPendingAfterMinutes() * 60_000L;
        boolean pendingReported = false;
        JsonNode q;
        String status;
        while (true) {
            q = hsp.queryPayment(run.flowId, merchant);
            status = q.path("status").asText();
            if (STOP.contains(status)) break;
            if (!pendingReported && System.currentTimeMillis() - started > pendingAfter) {
                run.state(State.PENDING_CONFIRMATION);
                run.step("pending", "Pending confirmation after " + props.getHsp().getPendingAfterMinutes() + " min; still polling the same payment", Map.of("flow_id", run.flowId));
                pendingReported = true;
            }
            if (System.currentTimeMillis() - started > 3 * pendingAfter) fail("no terminal HSP status after " + 3 * props.getHsp().getPendingAfterMinutes() + " min; resume with flow_id " + run.flowId);
            Thread.sleep(props.getHsp().getPollSeconds() * 1000L);
        }
        Map<String, Object> qd = new LinkedHashMap<>();
        qd.put("status", status);
        qd.put("attribution_verified", q.path("attribution_verified").asBoolean(false));
        qd.put("tx_signature", q.path("payment").path("tx_signature").asText(""));
        qd.put("simulated", q.path("simulated").asBoolean(false));
        if ("payment-failed".equals(status)) {
            run.step("query_payment", "HSP payment failed: " + q.path("payment").path("status_reason").asText(), qd);
            run.state(State.FAILED);
            return;
        }
        String note = "payment-safe".equals(status)
                ? "On-chain with half of the required confirmations (not yet finalized)"
                : "Payment reached final confirmation";
        run.step("query_payment", note, qd);
        run.put("hspStatus", status);
        String tx = q.path("payment").path("tx_signature").asText("");
        if (Eth.isTxHash(tx)) run.put("hspTx", tx);
        run.put("simulated", q.path("simulated").asBoolean(false));
        run.state(State.SETTLED);

        ObjectNode notice = mapper.createObjectNode().put("termsId", run.terms.path("termsId").asText()).put("flowId", run.flowId)
                .put("paymentRequestId", run.getSummary().get("paymentRequestId").toString()).put("payer", payer.getAddress());
        JsonNode delivered = postJson(receiverBase() + "/a2a/receiver/settle", notice);
        Map<String, Object> dd = new LinkedHashMap<>();
        dd.put("status", delivered.path("status").asText());
        if (delivered.has("anchor")) {
            dd.put("settlementTx", delivered.path("anchor").path("txHash").asText());
            run.put("recordSettlementTx", delivered.path("anchor").path("explorerUrl").asText());
        }
        if (delivered.has("anchorError")) dd.put("anchorError", delivered.path("anchorError").asText());
        run.step("settle_notice", "Receiving agent re-verified with HSP and delivered", dd);
        run.put("resource", mapper.convertValue(delivered.path("resource"), Map.class));
        run.state("DELIVERED".equals(delivered.path("status").asText()) ? State.DELIVERED : State.SETTLED);
    }

    // ------------------------------------------------------------------ helpers

    private void policyCheck(PaymentRun run, JsonNode terms) {
        BigDecimal price = new BigDecimal(terms.path("priceUsd").asText());
        HskProperties.Payer p = props.getPayer();
        if (price.compareTo(p.getMaxPerPaymentUsd()) > 0) fail("price " + price + " USD over per-payment cap " + p.getMaxPerPaymentUsd());
        BigDecimal today = spentUsd.getOrDefault(LocalDate.now(ZoneOffset.UTC), BigDecimal.ZERO);
        if (today.add(price).compareTo(p.getDailyCapUsd()) > 0) fail("daily cap " + p.getDailyCapUsd() + " USD would be exceeded");
        List<String> allowed = new ArrayList<>(p.getAllowedPayTo());
        if (allowed.isEmpty() && Eth.isAddress(props.getReceiver().getPayTo())) allowed.add(props.getReceiver().getPayTo());
        if (!allowed.isEmpty() && allowed.stream().noneMatch(a -> Eth.sameAddress(a, terms.path("payTo").asText()))) fail("payTo " + terms.path("payTo").asText() + " is not on the allowlist");
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("maxPerPaymentUsd", p.getMaxPerPaymentUsd());
        d.put("dailyCapUsd", p.getDailyCapUsd());
        d.put("spentTodayUsd", today);
        if (chain.enabled()) {
            var onchain = chain.checkSpend(payer.getAddress(), terms.path("payTo").asText(), new BigInteger(terms.path("amountRaw").asText()));
            if (onchain != null) {
                d.put("onchainCheckSpend", onchain.getValue());
                if (!onchain.getKey()) fail("on-chain spend policy refused: " + onchain.getValue());
            }
        }
        run.step("policy", "Spend policy passed", d);
    }

    private ObjectNode cartContents(JsonNode terms, String network, JsonNode token) {
        ObjectNode c = mapper.createObjectNode();
        c.put("id", terms.path("cartMandateId").asText());
        c.put("user_cart_confirmation_required", true);
        ObjectNode pr = c.putObject("payment_request");
        ArrayNode md = pr.putArray("method_data");
        ObjectNode m = md.addObject();
        m.put("supported_methods", "https://www.x402.org/");
        m.putObject("data").put("x402Version", 2).put("network", network).put("chain_id", terms.path("chainId").asLong())
                .put("contract_address", token.path("address").asText()).put("pay_to", terms.path("payTo").asText())
                .put("coin", token.path("symbol").asText());
        ObjectNode details = pr.putObject("details");
        details.put("id", terms.path("paymentRequestId").asText());
        String usd = Amounts.usd(new BigDecimal(terms.path("priceUsd").asText()));
        ObjectNode item = details.putArray("display_items").addObject();
        item.put("label", "Wallet insight " + terms.path("target").asText());
        item.putObject("amount").put("currency", "USD").put("value", usd);
        ObjectNode total = details.putObject("total");
        total.put("label", "Total");
        total.putObject("amount").put("currency", "USD").put("value", usd);
        String expiry = Instant.parse(terms.path("expiresAt").asText()).toString();
        c.put("cart_expiry", expiry);
        c.put("merchant_name", terms.path("merchantName").asText());
        return c;
    }

    private String receiverBase() {
        String u = props.getPayer().getReceiverAgentUrl();
        if (u != null && !u.isBlank()) return u.replaceAll("/+$", "");
        return "http://127.0.0.1:" + env.getProperty("local.server.port", env.getProperty("server.port", "8080"));
    }

    private JsonNode getJson(String url) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() >= 400) fail("GET " + url + " -> HTTP " + r.statusCode() + " " + r.body());
        return mapper.readTree(r.body());
    }

    private JsonNode postJson(String url, JsonNode body) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() >= 400) fail("POST " + url + " -> HTTP " + r.statusCode() + " " + r.body());
        return mapper.readTree(r.body());
    }

    private static void fail(String msg) {
        throw new IllegalStateException(msg);
    }

    private interface Body { void run() throws Exception; }

    private void guarded(PaymentRun run, Body body) {
        try {
            body.run();
        } catch (Throwable e) {
            log.warn("run {} failed: {}", run.getId(), e.toString());
            run.step("error", e.getMessage() == null ? e.toString() : e.getMessage(), null);
            run.state(State.FAILED);
        }
    }
}
