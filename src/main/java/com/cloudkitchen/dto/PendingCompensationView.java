package com.cloudkitchen.dto;

import java.time.Instant;

/**
 * One order that is awaiting compensation (SDD 7.10, 7.10.11): the row of the administrator's compensation page. The age is
 * counted from the creation of the order, as on the dashboard; {@code nextAttemptAt} is when the sweeper will try again
 * ({@code null} if it may do so at once).
 */
public record PendingCompensationView(long orderId, String brandCode, String reason, int attempts, long ageSeconds,
                                      Instant createdAt, Instant nextAttemptAt) {
}
