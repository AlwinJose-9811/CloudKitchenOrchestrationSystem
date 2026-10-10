package com.cloudkitchen.domain.enums;

/** Where an order comes from. Each source has its own delivery and payment rules. */
public enum OrderSource {
    SWIGGY("Swiggy"), ZOMATO("Zomato"), IN_HOUSE("In-house");

    private final String label;

    OrderSource(String label) {
        this.label = label;
    }

    /** Human-readable name for screens and logs. */
    public String label() {
        return label;
    }
}
