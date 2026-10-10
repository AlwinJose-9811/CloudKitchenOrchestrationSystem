package com.cloudkitchen.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MoneyUtilTest {

    private static BigDecimal d(String text) {
        return new BigDecimal(text);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"0.005,0.01", "0.004,0.00", "2.675,2.68", "2.674,2.67", "19.99,19.99", "19.991,19.99", "19.995,20.00",
            "0,0.00", "100,100.00", "-0.005,-0.01", "-0.004,0.00"})
    void scaleRoundsHalfUpToTwoDecimals(String input, String expected) {
        BigDecimal result = MoneyUtil.scale(d(input));
        assertEquals(expected, result.toPlainString());
        assertEquals(2, result.scale());
    }

    @Test
    void scaleOfNullIsRejected() {
        assertThrows(NullPointerException.class, () -> MoneyUtil.scale(null));
    }

    @Test
    void ofParsesTextAndTrimsIt() {
        assertEquals("19.99", MoneyUtil.of("19.99").toPlainString());
        assertEquals("1.50", MoneyUtil.of("  1.5 ").toPlainString());
        assertEquals("99999999.99", MoneyUtil.of("99999999.99").toPlainString());
    }

    @Test
    void ofRejectsGarbage() {
        assertThrows(NumberFormatException.class, () -> MoneyUtil.of("abc"));
        assertThrows(NumberFormatException.class, () -> MoneyUtil.of(""));
        assertThrows(NullPointerException.class, () -> MoneyUtil.of((String) null));
    }

    @Test
    void timesIsExactForTwoDecimalPrices() {
        assertEquals("59.97", MoneyUtil.times(d("19.99"), 3).toPlainString());
        assertEquals("0.03", MoneyUtil.times(d("0.01"), 3).toPlainString());
        assertEquals("4980.00", MoneyUtil.times(d("249.00"), 20).toPlainString());
    }

    @Test
    void sumOfAnEmptyListIsZeroWithScaleTwo() {
        BigDecimal total = MoneyUtil.sum(List.of());
        assertEquals("0.00", total.toPlainString());
        assertEquals(0, MoneyUtil.ZERO.compareTo(total));
    }

    @Test
    void sumAddsAmountsOfDifferentScales() {
        assertEquals("30.31", MoneyUtil.sum(List.of(d("10"), d("20.3"), d("0.01"))).toPlainString());
        assertEquals("0.30", MoneyUtil.sum(List.of(d("0.1"), d("0.2"))).toPlainString(),
                "0.1 + 0.2 is exactly 0.3 with BigDecimal, unlike with double");
    }

    @Test
    void subtractKeepsScaleTwo() {
        assertEquals("557.60", MoneyUtil.subtract(d("697.00"), d("139.40")).toPlainString());
        assertEquals("-0.01", MoneyUtil.subtract(d("1.00"), d("1.01")).toPlainString());
    }

    @Test
    void percentOfRoundsHalfUp() {
        assertEquals("139.40", MoneyUtil.percentOf(d("697.00"), 20).toPlainString());
        assertEquals("153.34", MoneyUtil.percentOf(d("697.00"), 22).toPlainString());
        assertEquals("73.33", MoneyUtil.percentOf(d("333.33"), 22).toPlainString(), "73.3326 rounds down");
        assertEquals("0.01", MoneyUtil.percentOf(d("0.10"), 5).toPlainString(), "0.005 rounds up (HALF_UP)");
        assertEquals("0.00", MoneyUtil.percentOf(d("0.00"), 22).toPlainString());
    }

    @Test
    void thePercentOverloadsAgree() {
        for (String amount : List.of("0.01", "9.99", "100.00", "697.00", "12345.67", "99999999.99")) {
            for (int percent : new int[] {0, 1, 5, 20, 22, 100}) {
                assertEquals(MoneyUtil.percentOf(d(amount), new BigDecimal(percent)),
                        MoneyUtil.percentOf(d(amount), percent), amount + " x " + percent + "%");
            }
        }
    }

    @Test
    void theDecimalPercentOverloadHandlesFractionalRates() {
        assertEquals("12.50", MoneyUtil.percentOf(d("100.00"), d("12.5")).toPlainString());
        assertEquals("0.11", MoneyUtil.percentOf(d("3.00"), d("3.5")).toPlainString(), "0.105 rounds up");
    }

    @Test
    void fitsTwoDecimalsIgnoresTrailingZeros() {
        assertTrue(MoneyUtil.fitsTwoDecimals(d("1.5")));
        assertTrue(MoneyUtil.fitsTwoDecimals(d("1.500")));
        assertTrue(MoneyUtil.fitsTwoDecimals(d("100")));
        assertTrue(MoneyUtil.fitsTwoDecimals(d("1E+3")));
        assertFalse(MoneyUtil.fitsTwoDecimals(d("1.505")));
        assertFalse(MoneyUtil.fitsTwoDecimals(null));
    }

    @Test
    void zeroConstantHasScaleTwo() {
        assertEquals("0.00", MoneyUtil.ZERO.toPlainString());
    }

    @Test
    void noPublicMethodTakesOrReturnsDoubleOrFloat() {
        for (Method method : MoneyUtil.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            assertFalse(isFloating(method.getReturnType()), method + " returns a floating-point type");
            for (Class<?> parameter : method.getParameterTypes()) {
                assertFalse(isFloating(parameter), method + " takes a floating-point parameter");
            }
        }
    }

    private static boolean isFloating(Class<?> type) {
        return type == double.class || type == float.class || type == Double.class || type == Float.class;
    }
}
