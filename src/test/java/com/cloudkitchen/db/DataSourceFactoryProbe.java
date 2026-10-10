package com.cloudkitchen.db;

import com.cloudkitchen.testsupport.TestDatabase;

/** Builds a JDBC URL that nothing listens on (a just-released free port). */
final class DataSourceFactoryProbe {

    private DataSourceFactoryProbe() {
    }

    static String deadUrl() {
        return DataSourceFactory.embeddedUrl("127.0.0.1", TestDatabase.freePort(), "ckdb");
    }
}
