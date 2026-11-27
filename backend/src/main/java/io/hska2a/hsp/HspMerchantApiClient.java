package io.hska2a.hsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hska2a.common.CanonicalJson;
import io.hska2a.config.HskProperties;
import io.hska2a.crypto.Es256kJwt;
import io.hska2a.crypto.HmacAuth;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * HSP Merchant REST API (https://hashkey-merchant.github.io): for teams with their own merchant account
 * (app_key / app_secret + secp256k1 merchant key). Creates one-time orders signed with HMAC-SHA256 and an
 * ES256K merchant_authorization JWT, and queries payments. Optional: the agent flow uses the MCP gateway.
 */
public class HspMerchantApiClient {
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final SecureRandom random = new SecureRandom();
    private final HskProperties.Hsp cfg;

    public HspMerchantApiClient(HskProperties.Hsp cfg) {
        this.cfg = cfg;
    }

    public boolean configured() {
        return !cfg.getAppKey().isBlank() && !cfg.getAppSecret().isBlank() && !cfg.getMerchantPrivateKeyPem().isBlank();
    }

    /** Builds merchant_authorization for the given cart contents. */
    public String merchantAuthorization(JsonNode contents, String merchantName, BigInteger merchantKey) {
        long iat = Instant.now().getEpochSecond();
        ObjectNode claims = mapper.createObjectNode();
        claims.put("iss", merchantName).put("sub", merchantName).put("aud", "HashkeyMerchant")
                .put("iat", iat).put("exp", iat + 3600)
                .put("jti", "JWT-" + iat + "-" + HexFormat.of().formatHex(bytes(6)))
                .put("cart_hash", CanonicalJson.sha256Hex(CanonicalJson.write(contents)));
        return Es256kJwt.sign("{\"alg\":\"ES256K\",\"typ\":\"JWT\"}", claims.toString(), merchantKey);
    }

    public JsonNode createOrder(ObjectNode contents, String redirectUrl) {
        if (!configured()) throw new HspException("HSP merchant API not configured (HSP_APP_KEY / HSP_APP_SECRET / HSP_MERCHANT_PRIVATE_KEY_PEM)");
        BigInteger key = Es256kJwt.privateKeyFromPem(cfg.getMerchantPrivateKeyPem());
        ObjectNode body = mapper.createObjectNode();
        ObjectNode cm = body.putObject("cart_mandate");
        cm.set("contents", contents);
        cm.put("merchant_authorization", merchantAuthorization(contents, contents.path("merchant_name").asText(), key));
        if (redirectUrl != null) body.put("redirect_url", redirectUrl);
        return send("POST", "/api/v1/merchant/orders", "", CanonicalJson.write(body));
    }

    public JsonNode queryByFlow(String flowId) {
        return send("GET", "/api/v1/merchant/payments", "flow_id=" + flowId, null);
    }

    JsonNode send(String method, String path, String query, String canonicalBody) {
        long ts = Instant.now().getEpochSecond();
        String nonce = HexFormat.of().formatHex(bytes(16));
        String bodyHash = canonicalBody == null ? "" : CanonicalJson.sha256Hex(canonicalBody);
        String sig = HmacAuth.hmacHex(cfg.getAppSecret(), HmacAuth.requestMessage(method, path, query, bodyHash, ts, nonce));
        String uri = cfg.getRestBaseUrl() + path + (query.isEmpty() ? "" : "?" + query);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(30))
                .header("X-App-Key", cfg.getAppKey()).header("X-Timestamp", String.valueOf(ts))
                .header("X-Nonce", nonce).header("X-Signature", sig);
        if (canonicalBody != null) b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(canonicalBody));
        else b.GET();
        try {
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode env = mapper.readTree(r.body());
            if (env.path("code").asInt(-1) != 0) throw new HspException("HSP " + path + " code " + env.path("code") + ": " + env.path("msg").asText());
            return env.path("data");
        } catch (HspException e) {
            throw e;
        } catch (Exception e) {
            throw new HspException("HSP " + path + " failed: " + e.getMessage(), e);
        }
    }

    private byte[] bytes(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return b;
    }
}
