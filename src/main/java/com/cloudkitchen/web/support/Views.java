package com.cloudkitchen.web.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the read-only results of the services into what a JSP can read. Jakarta EL 5.0 (Tomcat 10.1) reads JavaBean
 * getters and map entries, not record accessors, so a record becomes a read-only {@code Map} (component name to value),
 * a collection becomes a list, an {@link Instant} becomes UTC text with the label "UTC" (assumption A-07), a
 * {@link BigDecimal} becomes plain decimal text and a {@code double} becomes text with one decimal place. Strings,
 * numbers, booleans and enums are left as they are; a view never receives a service, a domain object or a connection:
 * any other type is refused, so a controller must say explicitly what a page may see. Escaping stays the job of the JSP
 * ({@code <c:out>}); nothing here encodes for HTML.
 */
public final class Views {

    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    private Views() {
    }

    /** The view form of {@code value}; {@code null} stays {@code null}. */
    public static Object of(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Enum<?>
                || value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return value;
        }
        if (value instanceof Instant instant) {
            return UTC.format(instant);
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof Double || value instanceof Float) {
            return BigDecimal.valueOf(((Number) value).doubleValue()).setScale(1, RoundingMode.HALF_UP).toPlainString();
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(k, of(v)));
            return Collections.unmodifiableMap(out);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> out = new ArrayList<>(collection.size());
            collection.forEach(v -> out.add(of(v)));
            return Collections.unmodifiableList(out);
        }
        if (value instanceof Object[] array) {
            return of(java.util.Arrays.asList(array));
        }
        if (value.getClass().isRecord()) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (RecordComponent component : value.getClass().getRecordComponents()) {
                try {
                    var accessor = component.getAccessor();
                    accessor.setAccessible(true);
                    out.put(component.getName(), of(accessor.invoke(value)));
                } catch (IllegalAccessException | InvocationTargetException e) {
                    throw new IllegalStateException("Cannot read " + component.getName() + " of " + value.getClass().getSimpleName(), e);
                }
            }
            return Collections.unmodifiableMap(out);
        }
        throw new IllegalArgumentException("A view may not contain a " + value.getClass().getName());
    }
}
