package com.cloudkitchen.snapshot;

import java.io.ObjectInputFilter;

/**
 * The deserialization allowlist (SDD 9.2, 9.4, FR-SER-04). A snapshot stream may contain exactly the classes below;
 * every other class (including proxies, collections other than {@code ArrayList}, and any gadget class) is rejected
 * by the final {@code !*}, and the stream is also bounded in depth, references, array length and size.
 *
 * <ul>
 *   <li>{@code OrderSnapshotDTO} and its nested {@code ItemLine}: the data;</li>
 *   <li>{@code java.util.ArrayList}: the container of the orders and of the lines;</li>
 *   <li>{@code java.lang.String}: the text fields;</li>
 *   <li>{@code java.lang.Object}: allowed only because {@code ArrayList} reads its elements through an
 *       {@code Object[]} that the filter checks as an array of {@code Object}; {@code Object} is not
 *       {@code Serializable}, so no stream can instantiate it as an object.</li>
 * </ul>
 *
 * Limits: depth 5 (list, order, item list, item, text), 100,000 references, arrays of at most 100,000 elements and a
 * stream of at most {@code snapshot.max.bytes} bytes. The filter only ever runs on bytes whose HMAC has been verified.
 */
public final class SnapshotFilterFactory {

    static final String ALLOWED_CLASSES = "com.cloudkitchen.snapshot.OrderSnapshotDTO;"
            + "com.cloudkitchen.snapshot.OrderSnapshotDTO$ItemLine;java.util.ArrayList;java.lang.String;java.lang.Object";
    static final int MAX_DEPTH = 5;
    static final int MAX_REFERENCES = 100_000;
    static final int MAX_ARRAY_LENGTH = 100_000;

    private SnapshotFilterFactory() {
    }

    /** The filter pattern text (documented and tested). */
    public static String pattern(long maxBytes) {
        return ALLOWED_CLASSES + ";maxdepth=" + MAX_DEPTH + ";maxrefs=" + MAX_REFERENCES + ";maxbytes=" + maxBytes
                + ";maxarray=" + MAX_ARRAY_LENGTH + ";!*";
    }

    public static ObjectInputFilter create(long maxBytes) {
        return ObjectInputFilter.Config.createFilter(pattern(maxBytes));
    }
}
