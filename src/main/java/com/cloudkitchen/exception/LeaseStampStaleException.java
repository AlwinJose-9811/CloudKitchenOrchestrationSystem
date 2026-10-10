package com.cloudkitchen.exception;

/**
 * A statement or its commit took longer than the guard budget allows, so the lease timestamp it used may be stale
 * (SDD 7.11.15). The transaction is rolled back and the operation retried with a fresh stamp.
 */
public class LeaseStampStaleException extends PersistenceException {

    private static final long serialVersionUID = 1L;

    public LeaseStampStaleException(String message) {
        super("LEASE_STAMP_STALE", message, null);
    }
}
