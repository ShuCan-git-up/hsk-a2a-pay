package io.hska2a.receiver;

import com.fasterxml.jackson.databind.JsonNode;
import io.hska2a.chain.ChainService;
import io.hska2a.chain.InsightService;
import io.hska2a.common.Amounts;
import io.hska2a.common.ApiException;
import io.hska2a.common.CanonicalJson;
import io.hska2a.common.Eth;
import io.hska2a.config.HskProperties;
import io.hska2a.crypto.Signers;
import io.hska2a.hsp.HspGateway;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Credentials;

/**
 * Receiving agent: quotes signed payment terms, anchors them on HashKey Chain, and delivers the paid resource only
 * after it has verified the payment with HSP itself (never on the payer's word), then anchors the settlement.
 */
@Service
public class ReceivingAgentService {
    public static final String SKILL = "wallet-insight";
    private static final Set<String> SETTLED = Set.of("payment-safe", "payment-finalized");
    private static final Logger log = LoggerFactory.getLogger(ReceivingAgentService.class);

    private final HskProperties props;
    private final HspGateway hsp;
    private final ChainService chain;
    private final InsightService insight;
    private final Credentials signer;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Map<String, Object>> book = new ConcurrentHashMap<>();

    public ReceivingAgentService(HskProperties props, HspGateway hsp, ChainService chain, InsightService insight) {
        this.props = props;
        this.hsp = hsp;
        this.chain = chain;
        this.insight = insight;
        this.signer = Signers.credentials(props.getReceiver().getSigningPrivateKey());
    }

    public String signerAddress() {
        return signer == null ? null : Eth.checksum(signer.getAddress());
    }

    public String payTo() {
        String p = props.getReceiver().getPayTo();
        return Eth.isAddress(p) ? Eth.checksum(p) : signerAddress();
    }

    public Map<String, Object> agentCard(String baseUrl) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("name", props.getReceiver().getAgentName());
        card.put("description", "Sells a live HashKey Chain wallet insight. Paid per call through HSP (HashKey Settlement Platform).");
        card.put("url", baseUrl + "/a2a/receiver");
        card.put("version", "0.1.0");
        card.put("protocolVersion", "a2a-lite/0.1");
        card.put("skills", List.of(Map.of("id", SKILL, "name", "Wallet insight", "priceUsd", Amounts.usd(props.getReceiver().getPriceUsd()),
                "input", "target: 0x address", "output", "balance, tx count, contract flag, activity tier")));
        Map<String, Object> pay = new LinkedHashMap<>();
        pay.put("rail", "HSP");
        pay.put("payTo", payTo());
        pay.put("merchantName", props.getHsp().getMerchantName());
        pay.put("network", props.getToken().getNetwork());
        pay.put("chainId", props.getChain().getChainId());
        pay.put("token", props.getToken().getAddress());
        pay.put("tokenSymbol", props.getToken().getSymbol());
        pay.put("termsSigner", signerAddress());
        pay.put("settlementContract", props.getChain().getContractAddress());
        card.put("payment", pay);
        card.put("endpoints", Map.of("quote", baseUrl + "/a2a/receiver/quote", "settle", baseUrl + "/a2a/receiver/settle"));
        return card;
    }

    public Map<String, Object> quote(String skill, String target) {
        if (signer == null) throw ApiException.unavailable("receiver signing key not configured (RECEIVER_SIGNING_PRIVATE_KEY)");
        if (!SKILL.equals(skill)) throw ApiException.badRequest("unknown skill " + skill);
        if (!Eth.isAddress(target)) throw ApiException.badRequest("target must be a 0x address");
        HskProperties.Token t = props.getToken();
        BigDecimal price = props.getReceiver().getPriceUsd();
        String stamp = String.valueOf(System.currentTimeMillis());
        String rnd = HexFormat.of().formatHex(bytes(4));
        String cartId = "HSKA2A-" + stamp + "-" + rnd;
        Instant expires = Instant.now().plus(props.getReceiver().getTermsTtlMinutes(), ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        PaymentTerms terms = new PaymentTerms(
                Eth.keccak(cartId), cartId, "PAYREQ-" + stamp + "-" + rnd, props.getHsp().getMerchantName(),
                props.getReceiver().getAgentName(), skill, Eth.checksum(target), payTo(), Amounts.usd(price),
                t.getNetwork(), props.getChain().getChainId(), t.getAddress(), t.getSymbol(), t.getDecimals(), t.getProtocol(),
                Amounts.toRaw(price, t.getDecimals()).toString(), Eth.keccak(skill + ":" + target.toLowerCase()),
                expires.toString(), props.getChain().getContractAddress());
        String canonical = CanonicalJson.write(terms);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("terms", terms);
        res.put("signature", Signers.personalSign(canonical, signer));
        res.put("signer", signer.getAddress());
        if (chain.enabled()) {
            try {
                ChainService.TxResult tx = chain.publishTerms(terms.termsId(), terms.payTo(), terms.token(), new BigInteger(terms.amountRaw()),
                        expires.getEpochSecond(), terms.serviceHash());
                res.put("anchor", tx);
            } catch (Exception e) {
                log.warn("publishTerms failed: {}", e.getMessage());
                res.put("anchorError", e.getMessage());
            }
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("terms", terms);
        entry.put("status", "OPEN");
        entry.put("quote", res);
        book.put(terms.termsId(), entry);
        return res;
    }

    /** Called by the paying agent after submit. The receiver re-checks the payment with HSP before delivering. */
    public Map<String, Object> settle(String termsId, String flowId, String paymentRequestId, String payer) {
        Map<String, Object> entry = book.get(termsId);
        if (entry == null) throw ApiException.notFound("unknown terms " + termsId);
        PaymentTerms terms = (PaymentTerms) entry.get("terms");
        if ("DELIVERED".equals(entry.get("status"))) throw ApiException.conflict("already delivered for terms " + termsId);
        if (!terms.paymentRequestId().equals(paymentRequestId)) throw ApiException.badRequest("payment_request_id does not match the terms");

        JsonNode q = hsp.queryPayment(flowId, terms.merchantName());
        String status = q.path("status").asText();
        JsonNode p = q.path("payment");
        boolean simulated = q.path("simulated").asBoolean(false);
        Map<String, Object> verification = new LinkedHashMap<>();
        verification.put("hspStatus", status);
        verification.put("attributionVerified", q.path("attribution_verified").asBoolean(false));
        verification.put("payTo", p.path("to_pay_address").asText());
        verification.put("payer", p.path("payer_address").asText());
        verification.put("txSignature", p.path("tx_signature").asText(""));
        verification.put("simulated", simulated);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("verification", verification);

        if (!SETTLED.contains(status)) {
            res.put("status", "NOT_SETTLED");
            return res;
        }
        if (!q.path("attribution_verified").asBoolean(false)) throw ApiException.forbidden("HSP attribution not verified for " + terms.merchantName());
        if (!Eth.sameAddress(p.path("to_pay_address").asText(), terms.payTo())) throw ApiException.forbidden("HSP pay_to differs from terms");
        if (!Eth.sameAddress(p.path("payer_address").asText(), payer)) throw ApiException.forbidden("HSP payer differs from claimed payer");
        if (simulated && props.getChain().getChainId() == 177) throw ApiException.forbidden("simulated HSP payments are refused on mainnet");

        if (chain.enabled()) {
            try {
                String tx = p.path("tx_signature").asText("");
                String ref = Eth.keccak((simulated ? "SIMULATED:" : "") + paymentRequestId);
                ChainService.TxResult anchor = chain.recordHspSettlement(termsId, payer, new BigInteger(terms.amountRaw()), ref,
                        Eth.isTxHash(tx) ? tx : Eth.ZERO_BYTES32);
                res.put("anchor", anchor);
            } catch (Exception e) {
                log.warn("recordHspSettlement failed: {}", e.getMessage());
                res.put("anchorError", e.getMessage());
            }
        }
        res.put("status", "DELIVERED");
        res.put("resource", insight.walletInsight(terms.target()));
        entry.put("status", "DELIVERED");
        entry.put("settlement", res);
        return res;
    }

    public Map<String, Map<String, Object>> book() {
        return book;
    }

    private byte[] bytes(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return b;
    }
}
