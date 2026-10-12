package com.cloudkitchen.dto;

import com.cloudkitchen.domain.enums.Role;
import java.time.Instant;

/** One account as the administrator page lists it: no password hash, no counters, no session data. */
public record UserSummary(long id, String username, Role role, boolean enabled, Instant lockedUntil) {
}
