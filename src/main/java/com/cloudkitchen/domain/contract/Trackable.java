package com.cloudkitchen.domain.contract;

import com.cloudkitchen.domain.StatusHistoryEntry;
import com.cloudkitchen.domain.enums.OrderStatus;
import java.util.List;

/** Something whose progress can be followed: its current status, how it got there, and a public code to look it up. */
public interface Trackable {

    OrderStatus getStatus();

    /** The status changes so far, oldest first. The list cannot be modified by the caller. */
    List<StatusHistoryEntry> getHistory();

    /** An unguessable code that identifies the order without exposing its internal id. */
    String getTrackingCode();
}
