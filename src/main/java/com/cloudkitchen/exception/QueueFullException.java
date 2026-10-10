package com.cloudkitchen.exception;

/** A station queue did not take the order id, so the order cannot be processed and must be compensated (RR-11). */
public class QueueFullException extends CloudKitchenException {

    private static final long serialVersionUID = 1L;

    /** Why the id was not inserted. */
    public enum Reason { QUEUE_FULL, STATION_UNAVAILABLE, QUEUE_CLOSED, INTERRUPTED }

    private final String stationCode;
    private final Reason reason;

    public QueueFullException(String stationCode, Reason reason) {
        super("QUEUE_FULL", "Station " + stationCode + " did not accept the order (" + reason + ")");
        this.stationCode = stationCode;
        this.reason = reason;
    }

    public QueueFullException(String stationCode, Reason reason, Throwable cause) {
        super("QUEUE_FULL", "Station " + stationCode + " did not accept the order (" + reason + ")", cause);
        this.stationCode = stationCode;
        this.reason = reason;
    }

    public String getStationCode() {
        return stationCode;
    }

    public Reason getReason() {
        return reason;
    }
}
