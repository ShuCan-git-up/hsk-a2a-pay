package io.hska2a.crypto;

import java.io.StringReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.sec.ECPrivateKey;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.signers.HMacDSAKCalculator;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;

/** ES256K (secp256k1 + SHA-256) compact JWS, as HSP requires for cart_mandate.merchant_authorization. */
public final class Es256kJwt {
    private static final X9ECParameters CURVE = CustomNamedCurves.getByName("secp256k1");
    private static final ECDomainParameters DOMAIN =
            new ECDomainParameters(CURVE.getCurve(), CURVE.getG(), CURVE.getN(), CURVE.getH());
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private Es256kJwt() {}

    public static BigInteger privateKeyFromPem(String pem) {
        try (PEMParser parser = new PEMParser(new StringReader(pem.replace("\\n", "\n")))) {
            Object obj = parser.readObject();
            if (obj instanceof PEMKeyPair kp) {
                return ECPrivateKey.getInstance(kp.getPrivateKeyInfo().parsePrivateKey()).getKey();
            }
            if (obj instanceof PrivateKeyInfo info) {
                return ECPrivateKey.getInstance(info.parsePrivateKey()).getKey();
            }
            throw new IllegalArgumentException("unsupported PEM object: " + (obj == null ? "null" : obj.getClass().getSimpleName()));
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("cannot read merchant key PEM", e);
        }
    }

    public static String sign(String headerJson, String payloadJson, BigInteger privateKey) {
        String signingInput = B64.encodeToString(headerJson.getBytes(StandardCharsets.UTF_8)) + "."
                + B64.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        byte[] hash = sha256(signingInput.getBytes(StandardCharsets.US_ASCII));
        ECDSASigner signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()));
        signer.init(true, new ECPrivateKeyParameters(privateKey, DOMAIN));
        BigInteger[] rs = signer.generateSignature(hash);
        BigInteger s = rs[1];
        if (s.compareTo(CURVE.getN().shiftRight(1)) > 0) s = CURVE.getN().subtract(s); // low-s
        byte[] sig = new byte[64];
        copy32(rs[0], sig, 0);
        copy32(s, sig, 32);
        return signingInput + "." + B64.encodeToString(sig);
    }

    public static boolean verify(String jwt, BigInteger privateKeyForPub) {
        String[] parts = jwt.split("\\.");
        byte[] sig = Base64.getUrlDecoder().decode(parts[2]);
        BigInteger r = new BigInteger(1, java.util.Arrays.copyOfRange(sig, 0, 32));
        BigInteger s = new BigInteger(1, java.util.Arrays.copyOfRange(sig, 32, 64));
        ECDSASigner verifier = new ECDSASigner();
        verifier.init(false, new ECPublicKeyParameters(DOMAIN.getG().multiply(privateKeyForPub).normalize(), DOMAIN));
        return verifier.verifySignature(sha256((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII)), r, s);
    }

    private static void copy32(BigInteger v, byte[] out, int off) {
        byte[] b = v.toByteArray();
        int start = Math.max(0, b.length - 32);
        int len = b.length - start;
        System.arraycopy(b, start, out, off + 32 - len, len);
    }

    private static byte[] sha256(byte[] in) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(in);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
