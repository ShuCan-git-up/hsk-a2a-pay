package io.hska2a.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import io.hska2a.common.Eth;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The payment agent never signs blindly. Before signing what HSP's prepare_payment returned, it checks that the
 * EIP-712 payload is exactly the transfer that was agreed: right struct, chain, token, payer, payee, amount cap
 * and a short validity window. Any mismatch = refuse.
 */
public final class TypedDataFirewall {

    public record Expectation(long chainId, String token, String payer, String payTo, BigInteger maxRaw,
                              String tokenTotalRaw, int maxValiditySeconds, Instant now) {}

    private static final String[][] TWA = {
        {"from", "address"}, {"to", "address"}, {"value", "uint256"},
        {"validAfter", "uint256"}, {"validBefore", "uint256"}, {"nonce", "bytes32"}
    };

    private TypedDataFirewall() {}

    /** @return list of violations; empty = safe to sign. */
    public static List<String> check(JsonNode eip712, Expectation x) {
        List<String> v = new ArrayList<>();
        if (eip712 == null || !eip712.has("domain") || !eip712.has("message") || !eip712.has("types")) {
            v.add("eip712 is missing domain, types or message");
            return v;
        }
        String primary = eip712.path("primaryType").asText();
        if (!"TransferWithAuthorization".equals(primary)) {
            v.add("primaryType is " + primary + ", expected TransferWithAuthorization (EIP-3009)");
            return v;
        }
        JsonNode fields = eip712.path("types").path("TransferWithAuthorization");
        if (!fields.isArray() || fields.size() != TWA.length) {
            v.add("TransferWithAuthorization struct has unexpected fields");
        } else {
            for (int i = 0; i < TWA.length; i++) {
                if (!TWA[i][0].equals(fields.get(i).path("name").asText()) || !TWA[i][1].equals(fields.get(i).path("type").asText())) {
                    v.add("TransferWithAuthorization field " + i + " differs from EIP-3009");
                }
            }
        }
        JsonNode d = eip712.get("domain");
        if (d.path("chainId").asLong(-1) != x.chainId()) v.add("domain.chainId " + d.path("chainId").asText() + " != " + x.chainId());
        if (!Eth.sameAddress(d.path("verifyingContract").asText(), x.token())) v.add("domain.verifyingContract is not the agreed token " + x.token());
        JsonNode m = eip712.get("message");
        if (!Eth.sameAddress(m.path("from").asText(), x.payer())) v.add("message.from is not the confirmed payer");
        if (!Eth.sameAddress(m.path("to").asText(), x.payTo())) v.add("message.to is not the agreed pay_to");
        BigInteger value = parse(m.path("value"));
        if (value == null) {
            v.add("message.value is not an unsigned integer");
        } else {
            if (value.compareTo(x.maxRaw()) > 0) v.add("message.value " + value + " exceeds cap " + x.maxRaw());
            if (x.tokenTotalRaw() != null && !value.toString().equals(x.tokenTotalRaw())) v.add("message.value differs from token_total_raw");
        }
        BigInteger validBefore = parse(m.path("validBefore"));
        long now = x.now().getEpochSecond();
        if (validBefore == null || validBefore.longValue() <= now) v.add("authorization already expired");
        else if (validBefore.longValue() > now + x.maxValiditySeconds()) v.add("authorization valid for longer than " + x.maxValiditySeconds() + "s");
        return v;
    }

    private static BigInteger parse(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        String s = n.asText();
        if (!s.matches("^\\d+$")) return null;
        return new BigInteger(s);
    }
}
