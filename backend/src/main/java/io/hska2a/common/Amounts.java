package io.hska2a.common;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

public final class Amounts {
    private Amounts() {}

    public static BigInteger toRaw(BigDecimal value, int decimals) {
        return value.setScale(decimals, RoundingMode.UNNECESSARY).movePointRight(decimals).toBigIntegerExact();
    }

    public static BigDecimal fromRaw(BigInteger raw, int decimals) {
        return new BigDecimal(raw).movePointLeft(decimals);
    }

    /** "0.10" style, at least 2 decimals, as HSP USD amounts are written. */
    public static String usd(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        if (s.scale() < 2) s = s.setScale(2);
        return s.toPlainString();
    }
}
