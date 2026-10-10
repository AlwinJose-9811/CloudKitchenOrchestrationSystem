package com.cloudkitchen.bootstrap;

import com.cloudkitchen.config.AppConfig;
import com.cloudkitchen.dao.StaffUserDao;
import com.cloudkitchen.db.Database;
import com.cloudkitchen.domain.enums.Role;
import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.exception.ConfigurationException;
import com.cloudkitchen.service.PasswordHasher;
import java.util.Arrays;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates the first ADMIN and KITCHEN_STAFF accounts from the git-ignored configuration, once (FR-AUT-07). It acts
 * only when {@code staff_users} is empty, so a later start with different configuration values changes nothing.
 * Passwords exist only in the configuration; they are hashed with PBKDF2 here and neither they nor the hashes are
 * logged. Missing credentials with an empty table stop the start-up with a clear message.
 */
public final class UserSeeder {

    private static final Logger LOG = LoggerFactory.getLogger(UserSeeder.class);

    private final Database database;
    private final StaffUserDao users;
    private final PasswordHasher hasher;

    public UserSeeder(Database database, StaffUserDao users, PasswordHasher hasher) {
        this.database = Objects.requireNonNull(database, "database");
        this.users = Objects.requireNonNull(users, "users");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
    }

    /** @return the number of accounts created (0 when users already exist) */
    public int seedIfEmpty(AppConfig config) throws CloudKitchenException {
        return database.inTransaction(c -> {
            if (users.count(c) > 0) {
                return 0;
            }
            Credentials admin = credentials(config.bootstrapAdminUsername().orElse(null), config.bootstrapAdminPassword().orElse(null), "admin");
            Credentials staff = credentials(config.bootstrapStaffUsername().orElse(null), config.bootstrapStaffPassword().orElse(null), "staff");
            if (admin.username.equalsIgnoreCase(staff.username)) {
                throw new ConfigurationException("bootstrap.admin.username and bootstrap.staff.username must differ");
            }
            users.insert(c, admin.username, hasher.hash(admin.password), Role.ADMIN);
            users.insert(c, staff.username, hasher.hash(staff.password), Role.KITCHEN_STAFF);
            Arrays.fill(admin.password, '\0');
            Arrays.fill(staff.password, '\0');
            LOG.info("Created the initial accounts from the configuration (admin and staff)");
            return 2;
        });
    }

    private static Credentials credentials(String username, String password, String kind) {
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            throw new ConfigurationException("No staff accounts exist and bootstrap." + kind
                    + ".username / bootstrap." + kind + ".password are not both set in the configuration file");
        }
        return new Credentials(username.strip(), password.toCharArray());
    }

    private record Credentials(String username, char[] password) {
    }
}
