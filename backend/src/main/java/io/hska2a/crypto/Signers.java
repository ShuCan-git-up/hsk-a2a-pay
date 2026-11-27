package io.hska2a.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.crypto.StructuredDataEncoder;
import org.web3j.utils.Numeric;

/** EIP-712 and EIP-191 signing / recovery with web3j. Private keys never leave this process. */
public final class Signers {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Signers() {}

    public static Credentials credentials(String privateKeyHex) {
        if (privateKeyHex == null || privateKeyHex.isBlank()) return null;
        return Credentials.create(privateKeyHex.trim());
    }

    /** web3j wants EIP712Domain inside "types"; HSP may omit it, so derive it from the domain keys (canonical order). */
    public static String normalizeTypedData(JsonNode typedData) {
        ObjectNode copy = typedData.deepCopy();
        ObjectNode types = (ObjectNode) copy.get("types");
        if (!types.has("EIP712Domain")) {
            JsonNode domain = copy.get("domain");
            ArrayNode fields = MAPPER.createArrayNode();
            String[][] order = {
                {"name", "string"}, {"version", "string"}, {"chainId", "uint256"},
                {"verifyingContract", "address"}, {"salt", "bytes32"}
            };
            for (String[] f : order) {
                if (domain.has(f[0])) fields.addObject().put("name", f[0]).put("type", f[1]);
            }
            types.set("EIP712Domain", fields);
        }
        return copy.toString();
    }

    public static byte[] typedDataHash(JsonNode typedData) {
        try {
            return new StructuredDataEncoder(normalizeTypedData(typedData)).hashStructuredData();
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid EIP-712 data: " + e.getMessage(), e);
        }
    }

    public static String signTypedData(JsonNode typedData, Credentials creds) {
        return toHex(Sign.signMessage(typedDataHash(typedData), creds.getEcKeyPair(), false));
    }

    public static String recoverTypedData(JsonNode typedData, String signatureHex) {
        return recoverHash(typedDataHash(typedData), signatureHex);
    }

    public static String personalSign(String message, Credentials creds) {
        return toHex(Sign.signPrefixedMessage(message.getBytes(StandardCharsets.UTF_8), creds.getEcKeyPair()));
    }

    public static String recoverPersonal(String message, String signatureHex) {
        try {
            BigInteger pub = Sign.signedPrefixedMessageToKey(message.getBytes(StandardCharsets.UTF_8), fromHex(signatureHex));
            return Keys.toChecksumAddress("0x" + Keys.getAddress(pub));
        } catch (Exception e) {
            throw new IllegalArgumentException("bad signature", e);
        }
    }

    private static String recoverHash(byte[] hash, String signatureHex) {
        try {
            BigInteger pub = Sign.signedMessageHashToKey(hash, fromHex(signatureHex));
            return Keys.toChecksumAddress("0x" + Keys.getAddress(pub));
        } catch (Exception e) {
            throw new IllegalArgumentException("bad signature", e);
        }
    }

    static String toHex(Sign.SignatureData sig) {
        byte[] out = new byte[65];
        System.arraycopy(sig.getR(), 0, out, 0, 32);
        System.arraycopy(sig.getS(), 0, out, 32, 32);
        out[64] = sig.getV()[0];
        return Numeric.toHexString(out);
    }

    static Sign.SignatureData fromHex(String hex) {
        byte[] b = Numeric.hexStringToByteArray(hex);
        if (b.length != 65) throw new IllegalArgumentException("signature must be 65 bytes");
        byte v = b[64];
        if (v < 27) v += 27;
        return new Sign.SignatureData(v, Arrays.copyOfRange(b, 0, 32), Arrays.copyOfRange(b, 32, 64));
    }
}
