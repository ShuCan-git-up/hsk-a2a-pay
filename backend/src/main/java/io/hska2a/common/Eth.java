package io.hska2a.common;

import java.nio.charset.StandardCharsets;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.utils.Numeric;

public final class Eth {
    public static final String ZERO_BYTES32 = "0x" + "0".repeat(64);

    private Eth() {}

    public static boolean isAddress(String a) {
        return a != null && a.matches("^0x[0-9a-fA-F]{40}$");
    }

    public static boolean sameAddress(String a, String b) {
        return isAddress(a) && isAddress(b) && a.equalsIgnoreCase(b);
    }

    public static String checksum(String a) {
        return Keys.toChecksumAddress(a);
    }

    /** keccak256 of a UTF-8 string, 0x-hex. */
    public static String keccak(String s) {
        return Numeric.toHexString(Hash.sha3(s.getBytes(StandardCharsets.UTF_8)));
    }

    public static byte[] bytes32(String hex) {
        if (hex == null || hex.isBlank()) return new byte[32];
        byte[] b = Numeric.hexStringToByteArray(hex);
        if (b.length != 32) throw new IllegalArgumentException("not bytes32: " + hex);
        return b;
    }

    public static boolean isTxHash(String h) {
        return h != null && h.matches("^0x[0-9a-fA-F]{64}$");
    }
}
