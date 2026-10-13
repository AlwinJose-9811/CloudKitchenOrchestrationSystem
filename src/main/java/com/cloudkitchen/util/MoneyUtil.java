package com.cloudkitchen.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Objects;

/**
 * Money arithmetic. Every amount is a {@link BigDecimal} with exactly two decimals, rounded {@code HALF_UP}. There is
 * deliberately no method that accepts a {@code double} or a {@code float}: binary floating point cannot represent
 * 0.01 and would corrupt totals.
 */
public final class MoneyUtil {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    public static final BigDecimal ZERO = new BigDecimal("0.00");

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private MoneyUtil() {
    }

    /** Rounds to two decimals, {@code HALF_UP}. */
    public static BigDecimal scale(BigDecimal amount) {
        return Objects.requireNonNull(amount, "amount").setScale(SCALE, ROUNDING);
    }

    /** Parses decimal text such as {@code "19.99"} and rounds to two decimals. */
    public static BigDecimal of(String text) {
        return scale(new BigDecimal(Objects.requireNonNull(text, "text").trim()));
    }

    /** Unit price times a whole quantity, exact (a two-decimal price times an integer needs no rounding). */
    public static BigDecimal times(BigDecimal unitPrice, int quantity) {
        return scale(Objects.requireNonNull(unitPrice, "unitPrice").multiply(BigDecimal.valueOf(quantity)));
    }

    /** Sum of the amounts; an empty collection gives {@code 0.00}. */
    public static BigDecimal sum(Collection<BigDecimal> amounts) {
        BigDecimal total = ZERO;
        for (BigDecimal amount : Objects.requireNonNull(amounts, "amounts")) {
            total = total.add(scale(amount));
        }
        return scale(total);
    }

    public static BigDecimal subtract(BigDecimal minuend, BigDecimal subtrahend) {
        return scale(scale(minuend).subtract(scale(subtrahend)));
    }

    /** {@code percent} percent of {@code amount}, for example {@code percentOf(100.00, 12.5)} is {@code 12.50}. */
    public static BigDecimal percentOf(BigDecimal amount, BigDecimal percent) {
        Objects.requireNonNull(percent, "percent");
        return scale(scale(amount).multiply(percent).divide(HUNDRED, 6, RoundingMode.HALF_UP));
    }

    /** Whole-number convenience for the fixed commission rates (20 percent, 22 percent). */
    public static BigDecimal percentOf(BigDecimal amount, int percent) {
        return percentOf(amount, BigDecimal.valueOf(percent));
    }

    /** True iff the amount has at most two decimals once trailing zeros are ignored, so scaling it loses nothing. */
    public static boolean fitsTwoDecimals(BigDecimal amount) {
        return amount != null && amount.stripTrailingZeros().scale() <= SCALE;
    }
}
