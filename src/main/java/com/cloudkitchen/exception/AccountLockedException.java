package com.cloudkitchen.exception;

/** Login refused because the account is locked. For internal use: users see the same generic message. */
public class AccountLockedException extends AuthenticationException {

    private static final long serialVersionUID = 1L;
}
