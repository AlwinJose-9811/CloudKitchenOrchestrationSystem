package com.cloudkitchen.dto;

import com.cloudkitchen.domain.enums.Role;
import java.util.Objects;

/**
 * The logged-in user as kept in the HTTP session. The {@code sessionVersion} is compared with the database on every
 * request, so disabling an account or resetting its password ends its sessions.
 */
public record SessionUser(long userId, String username, Role role, int sessionVersion) {

    public SessionUser {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(role, "role");
    }
}
