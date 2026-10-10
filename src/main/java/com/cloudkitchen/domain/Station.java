package com.cloudkitchen.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/** One kitchen station, which serves exactly one brand. Inactive stations get no worker and take no orders. */
public final class Station {

    public static final int MAX_BRAND_NAME_LENGTH = 80;
    private static final Pattern CODE = Pattern.compile("[A-Z0-9_]{1,20}");

    private final long id;
    private final String code;
    private final String brandName;
    private final boolean active;

    public Station(long id, String code, String brandName, boolean active) {
        if (id <= 0) {
            throw new IllegalArgumentException("id must be positive");
        }
        String normalised = code == null ? null : code.strip().toUpperCase(Locale.ROOT);
        if (normalised == null || !CODE.matcher(normalised).matches()) {
            throw new IllegalArgumentException("station code must match " + CODE.pattern());
        }
        if (brandName == null || brandName.isBlank() || brandName.length() > MAX_BRAND_NAME_LENGTH) {
            throw new IllegalArgumentException("brandName must be 1-" + MAX_BRAND_NAME_LENGTH + " characters");
        }
        this.id = id;
        this.code = normalised;
        this.brandName = brandName.strip();
        this.active = active;
    }

    public long getId() {
        return id;
    }

    /** Upper-case brand code, used for routing and in thread names. */
    public String getCode() {
        return code;
    }

    public String getBrandName() {
        return brandName;
    }

    public boolean isActive() {
        return active;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Station that && id == that.id && code.equals(that.code)
                && brandName.equals(that.brandName) && active == that.active;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id) * 31 + code.hashCode();
    }

    @Override
    public String toString() {
        return "Station[id=" + id + ", code=" + code + ", active=" + active + "]";
    }
}
