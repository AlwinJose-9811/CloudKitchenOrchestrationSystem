package com.cloudkitchen.db;

import com.cloudkitchen.config.AppConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/** Builds the HikariCP pool from {@link AppConfig}; the only place where pool settings are chosen. */
public final class DataSourceFactory {

    private static final String URL_OPTIONS =
            "?sslMode=DISABLED&characterEncoding=UTF-8&connectionCollation=utf8mb4_unicode_ci";

    private DataSourceFactory() {
    }

    /** URL of the embedded server (always loopback). */
    public static String embeddedUrl(String host, int port, String database) {
        return "jdbc:mysql://" + host + ":" + port + "/" + database + URL_OPTIONS;
    }

    /** The URL for the configured mode: {@code db.url} when external, else the embedded server on {@code embeddedPort}. */
    public static String urlFor(AppConfig config, int embeddedPort) {
        return config.dbUrl().orElseGet(() -> embeddedUrl("127.0.0.1", embeddedPort, config.dbName()));
    }

    public static HikariDataSource create(AppConfig config, String jdbcUrl) {
        HikariConfig pool = new HikariConfig();
        pool.setJdbcUrl(jdbcUrl);
        pool.setUsername(config.dbUser());
        pool.setPassword(config.dbPassword());
        pool.setMaximumPoolSize(config.dbPoolMaxSize());
        pool.setConnectionTimeout(config.dbPoolConnectionTimeoutMs());
        pool.setConnectionInitSql("SET time_zone = '+00:00'");
        pool.setLeakDetectionThreshold(30_000);
        pool.setPoolName("ck-pool");
        return new HikariDataSource(pool);
    }
}
