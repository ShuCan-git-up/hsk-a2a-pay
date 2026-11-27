package io.hska2a.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HSP Merchant API request signing and webhook verification (HMAC-SHA256, hex). */
public final class HmacAuth {
    private HmacAuth() {}

    /** message = METHOD\nPATH\nQUERY\nbodyHash\ntimestamp\nnonce */
    public static String requestMessage(String method, String path, String query, String bodyHash, long ts, String nonce) {
        return method.toUpperCase() + "\n" + path + "\n" + (query == null ? "" : query) + "\n"
                + (bodyHash == null ? "" : bodyHash) + "\n" + ts + "\n" + nonce;
    }

    public static String hmacHex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** X-Signature: t=<ts>,v1=<hex>; message = ts + "." + rawBody; tolerance 300s. */
    public static boolean verifyWebhook(String header, String rawBody, String secret, long nowSeconds) {
        if (header == null) return false;
        long ts = -1;
        String v1 = null;
        for (String part : header.split(",")) {
            part = part.trim();
            if (part.startsWith("t=")) {
                try { ts = Long.parseLong(part.substring(2)); } catch (NumberFormatException e) { return false; }
            } else if (part.startsWith("v1=")) {
                v1 = part.substring(3);
            }
        }
        if (ts < 0 || v1 == null || Math.abs(nowSeconds - ts) > 300) return false;
        String expected = hmacHex(secret, ts + "." + rawBody);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), v1.getBytes(StandardCharsets.UTF_8));
    }
}
