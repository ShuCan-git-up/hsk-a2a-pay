package io.hska2a;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hska2a.common.CanonicalJson;
import io.hska2a.crypto.Es256kJwt;
import io.hska2a.crypto.HmacAuth;
import io.hska2a.crypto.Signers;
import io.hska2a.crypto.TypedDataFirewall;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Credentials;

class CryptoTest {
    // Anvil's public, well-known development keys. Never use them for real funds.
    static final Credentials PAYER = Credentials.create("0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d");
    static final String PAY_TO = "0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC";
    static final String USDC = "0x8FE3cB719Ee4410E236Cd6b72ab1fCDC06eF53c6";
    final ObjectMapper mapper = new ObjectMapper();

    @Test
    void canonicalJsonMatchesHspDocExample() throws Exception {
        JsonNode n = mapper.readTree("{\"merchant_name\":\"My Store\",\"id\":\"cart-123\",\"cart_expiry\":\"2024-03-01T12:00:00Z\"}");
        String c = CanonicalJson.write(n);
        assertEquals("{\"cart_expiry\":\"2024-03-01T12:00:00Z\",\"id\":\"cart-123\",\"merchant_name\":\"My Store\"}", c);
        assertEquals("1508eef075e15b83a4b03a9329c22ce39ecaac6fe203c8bef57de727fb72df16", CanonicalJson.sha256Hex(c));
        assertEquals("{\"a\":1,\"b\":2,\"nested\":{\"m\":\"middle\",\"z\":\"last\"}}",
                CanonicalJson.write(mapper.readTree("{\"b\":2,\"a\":1,\"nested\":{\"z\":\"last\",\"m\":\"middle\"}}")));
    }

    @Test
    void hmacRequestAndWebhook() {
        String msg = HmacAuth.requestMessage("post", "/api/v1/merchant/orders", "", "abc123", 1709123456L, "a3f1b2c4d5e6");
        assertEquals("ae300a0abc4c2aa0d041c84254d214297d23241c7c69f2d479f2fcf81e156028", HmacAuth.hmacHex("test_secret", msg));
        String header = "t=1709123456,v1=69a49de5c806828b3cf3675f741b75ed381586ed1fd274e786c079710d2b7a81";
        assertTrue(HmacAuth.verifyWebhook(header, "{\"a\":1}", "test_secret", 1709123500L));
        assertFalse(HmacAuth.verifyWebhook(header, "{\"a\":2}", "test_secret", 1709123500L));
        assertFalse(HmacAuth.verifyWebhook(header, "{\"a\":1}", "test_secret", 1709123456L + 301));
    }

    @Test
    void es256kJwtSignsAndVerifies() {
        BigInteger key = new BigInteger("59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d", 16);
        String jwt = Es256kJwt.sign("{\"alg\":\"ES256K\",\"typ\":\"JWT\"}", "{\"aud\":\"HashkeyMerchant\"}", key);
        String[] parts = jwt.split("\\.");
        assertEquals(3, parts.length);
        assertEquals(64, Base64.getUrlDecoder().decode(parts[2]).length);
        assertTrue(Es256kJwt.verify(jwt, key));
        assertFalse(Es256kJwt.verify(parts[0] + "." + parts[0] + "." + parts[2], key));
    }

    @Test
    void es256kReadsOpensslPem() {
        // generated with: openssl ecparam -name secp256k1 -genkey -noout (test-only key)
        String pem = "-----BEGIN EC PRIVATE KEY-----\n" + "MHQCAQEEIPwk6HslcaUDbVTPIXsxTR6KVBJSbA/dMoHQ9AZ6DgA2oAcGBSuBBAAK\n" + "oUQDQgAEsBUI8rT19iKk/0ovrVOBDyNvblgZz7LI6HBobzEtk/Fh0DYZLp6lZYxx\n" + "CodPnUWVI2KxR7UqIABKwYc1yIffIA==\n" + "-----END EC PRIVATE KEY-----\n";
        BigInteger k = Es256kJwt.privateKeyFromPem(pem);
        assertTrue(k.signum() > 0);
        assertTrue(Es256kJwt.verify(Es256kJwt.sign("{}", "{}", k), k));
    }

    ObjectNode typedData(String from, String to, String value, long validBefore, long chainId) {
        ObjectNode d = mapper.createObjectNode();
        d.putObject("domain").put("name", "USD Coin").put("version", "2").put("chainId", chainId).put("verifyingContract", USDC);
        var arr = d.putObject("types").putArray("TransferWithAuthorization");
        for (String[] f : new String[][] {{"from", "address"}, {"to", "address"}, {"value", "uint256"}, {"validAfter", "uint256"}, {"validBefore", "uint256"}, {"nonce", "bytes32"}}) {
            arr.addObject().put("name", f[0]).put("type", f[1]);
        }
        d.put("primaryType", "TransferWithAuthorization");
        d.putObject("message").put("from", from).put("to", to).put("value", value).put("validAfter", "0")
                .put("validBefore", String.valueOf(validBefore)).put("nonce", "0x" + "11".repeat(32));
        return d;
    }

    @Test
    void eip712SignRecover() {
        ObjectNode d = typedData(PAYER.getAddress(), PAY_TO, "100000", Instant.now().getEpochSecond() + 600, 133);
        String sig = Signers.signTypedData(d, PAYER);
        assertEquals(132, sig.length());
        assertTrue(Signers.recoverTypedData(d, sig).equalsIgnoreCase(PAYER.getAddress()));
        String p = Signers.personalSign("hello", PAYER);
        assertTrue(Signers.recoverPersonal("hello", p).equalsIgnoreCase(PAYER.getAddress()));
    }

    @Test
    void firewallAcceptsExactTransferAndRejectsTampering() {
        long now = Instant.now().getEpochSecond();
        var x = new TypedDataFirewall.Expectation(133, USDC, PAYER.getAddress(), PAY_TO, BigInteger.valueOf(110000), "100000", 3600, Instant.now());
        assertTrue(TypedDataFirewall.check(typedData(PAYER.getAddress(), PAY_TO, "100000", now + 600, 133), x).isEmpty());
        assertFalse(TypedDataFirewall.check(typedData(PAYER.getAddress(), "0x000000000000000000000000000000000000dEaD", "100000", now + 600, 133), x).isEmpty());
        assertFalse(TypedDataFirewall.check(typedData(PAYER.getAddress(), PAY_TO, "999999", now + 600, 133), x).isEmpty());
        assertFalse(TypedDataFirewall.check(typedData(PAYER.getAddress(), PAY_TO, "100000", now + 86400, 133), x).isEmpty());
        assertFalse(TypedDataFirewall.check(typedData(PAYER.getAddress(), PAY_TO, "100000", now + 600, 177), x).isEmpty());
        ObjectNode permit = typedData(PAYER.getAddress(), PAY_TO, "100000", now + 600, 133);
        permit.put("primaryType", "PermitTransferFrom");
        assertFalse(TypedDataFirewall.check(permit, x).isEmpty());
    }
}
