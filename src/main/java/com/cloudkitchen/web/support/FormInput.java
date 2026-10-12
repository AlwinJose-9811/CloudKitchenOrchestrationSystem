package com.cloudkitchen.web.support;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.regex.Pattern;

/**
 * Strict parsing of form text. Every method answers {@code null} for text that is not in the exact form, so a controller can
 * turn it into a message for the user (or a 400 where the value never comes from a person). Only ASCII digits are accepted:
 * {@code Integer.parseInt} and {@code new BigDecimal} would also take digits of other scripts.
 */
public final class FormInput {

    private static final Pattern WHOLE = Pattern.compile("-?[0-9]{1,9}");
    private static final Pattern DECIMAL = Pattern.compile("[0-9]{1,8}(\\.[0-9]{1,6})?");
    private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final DateTimeFormatter STRICT_DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    private FormInput() {
    }

    /** A whole number of at most nine digits with an optional minus sign, or {@code null}. */
    public static Integer wholeNumber(String text) {
        return text != null && WHOLE.matcher(text.strip()).matches() ? Integer.valueOf(text.strip()) : null;
    }

    /** A non-negative decimal with a dot, at most eight integer and six fraction digits, or {@code null}. */
    public static BigDecimal decimal(String text) {
        return text != null && DECIMAL.matcher(text.strip()).matches() ? new BigDecimal(text.strip()) : null;
    }

    /** A real calendar date written {@code yyyy-MM-dd}, or {@code null} (so {@code 2026-02-30}, {@code 2026-2-3} and a date with spaces around it are refused). */
    public static LocalDate isoDate(String text) {
        // no strip(): the date comes from a date input or a link, and surrounding text is not part of the exact form
        if (text == null || !DATE.matcher(text).matches()) {
            return null;
        }
        try {
            return LocalDate.parse(text, STRICT_DATE);
        } catch (java.time.DateTimeException e) {
            return null;
        }
    }
}
