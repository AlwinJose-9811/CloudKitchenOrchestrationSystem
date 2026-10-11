package com.cloudkitchen.snapshot;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One in-flight order as written to a snapshot (SDD 9.4, FR-SER-01). Plain data only: primitives, {@code String},
 * and a list of {@link ItemLine}; money is a {@code String} (exact decimal text), time is epoch milliseconds. No
 * service, connection, thread, lock, logger or credential can be part of it, and a test checks the field types.
 *
 * <p><b>Versioning.</b> {@code serialVersionUID} is declared and so is {@link #formatVersion()}. Adding an optional
 * field keeps the UID and raises {@link #CURRENT_FORMAT_VERSION}; removing, renaming or re-typing a field changes the
 * UID <em>and</em> the version. A reader accepts exactly {@link #CURRENT_FORMAT_VERSION}: any other version, and any
 * stream whose class UID differs, is rejected (see {@code SnapshotService}); there is no migration.
 *
 * <p>The customer name is a personal datum that restoring an order needs ({@code orders.customer_name} is not
 * nullable). A snapshot file is therefore as sensitive as the database table and lives in the git-ignored
 * {@code snapshot.dir}, to be protected by operating-system permissions; it is signed, not encrypted.
 */
public final class OrderSnapshotDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The only format this code writes and accepts. */
    public static final int CURRENT_FORMAT_VERSION = 1;

    private final int formatVersion;
    private final long orderId;
    private final String source;
    private final String externalRef;
    private final String trackingCode;
    private final long stationId;
    private final String brandCode;
    private final String status;
    private final String customerName;
    private final String deliveryDistanceKm;
    private final String paymentMode;
    private final String paymentStatus;
    private final String platformFee;
    private final String totalAmount;
    private final long createdAtEpochMillis;
    private final long estimatedDeliveryAtEpochMillis;
    private final boolean stockDeducted;
    private final ArrayList<ItemLine> items;

    public OrderSnapshotDTO(int formatVersion, long orderId, String source, String externalRef, String trackingCode,
                            long stationId, String brandCode, String status, String customerName,
                            String deliveryDistanceKm, String paymentMode, String paymentStatus, String platformFee,
                            String totalAmount, long createdAtEpochMillis, long estimatedDeliveryAtEpochMillis,
                            boolean stockDeducted, List<ItemLine> items) {
        this.formatVersion = formatVersion;
        this.orderId = orderId;
        this.source = source;
        this.externalRef = externalRef;
        this.trackingCode = trackingCode;
        this.stationId = stationId;
        this.brandCode = brandCode;
        this.status = status;
        this.customerName = customerName;
        this.deliveryDistanceKm = deliveryDistanceKm;
        this.paymentMode = paymentMode;
        this.paymentStatus = paymentStatus;
        this.platformFee = platformFee;
        this.totalAmount = totalAmount;
        this.createdAtEpochMillis = createdAtEpochMillis;
        this.estimatedDeliveryAtEpochMillis = estimatedDeliveryAtEpochMillis;
        this.stockDeducted = stockDeducted;
        this.items = new ArrayList<>(items);
    }

    public int formatVersion() {
        return formatVersion;
    }

    public long orderId() {
        return orderId;
    }

    public String source() {
        return source;
    }

    public String externalRef() {
        return externalRef;
    }

    public String trackingCode() {
        return trackingCode;
    }

    public long stationId() {
        return stationId;
    }

    public String brandCode() {
        return brandCode;
    }

    public String status() {
        return status;
    }

    public String customerName() {
        return customerName;
    }

    public String deliveryDistanceKm() {
        return deliveryDistanceKm;
    }

    public String paymentMode() {
        return paymentMode;
    }

    public String paymentStatus() {
        return paymentStatus;
    }

    public String platformFee() {
        return platformFee;
    }

    public String totalAmount() {
        return totalAmount;
    }

    public long createdAtEpochMillis() {
        return createdAtEpochMillis;
    }

    public long estimatedDeliveryAtEpochMillis() {
        return estimatedDeliveryAtEpochMillis;
    }

    public boolean stockDeducted() {
        return stockDeducted;
    }

    /** An unmodifiable view of the lines. */
    public List<ItemLine> items() {
        return items == null ? List.of() : Collections.unmodifiableList(items);
    }

    /** One ordered menu item. */
    public static final class ItemLine implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private final long itemId;
        private final String itemName;
        private final String unitPrice;
        private final int quantity;

        public ItemLine(long itemId, String itemName, String unitPrice, int quantity) {
            this.itemId = itemId;
            this.itemName = itemName;
            this.unitPrice = unitPrice;
            this.quantity = quantity;
        }

        public long itemId() {
            return itemId;
        }

        public String itemName() {
            return itemName;
        }

        public String unitPrice() {
            return unitPrice;
        }

        public int quantity() {
            return quantity;
        }
    }
}
