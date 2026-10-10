package com.cloudkitchen.testsupport;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * A data source whose connections fail the next {@code n} calls of {@code commit()} (after the statements ran), to
 * force a commit failure for real. Everything else is passed to the wrapped pool.
 */
public final class FailingCommitDataSource implements DataSource {

    private final DataSource delegate;
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicInteger lostReplies = new AtomicInteger();

    public FailingCommitDataSource(DataSource delegate) {
        this.delegate = delegate;
    }

    public void failNextCommits(int count) {
        failures.set(count);
    }

    /**
     * The next {@code count} commits really happen and then report a connection failure: the transaction is durable
     * but the caller never learns it (a lost reply, SDD 7.13.4 FI-3, FI-6).
     */
    public void loseNextReplies(int count) {
        lostReplies.set(count);
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection real = delegate.getConnection();
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
            if (method.getName().equals("commit") && failures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new SQLException("injected commit failure", "08S01");
            }
            if (method.getName().equals("commit") && lostReplies.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                real.commit();
                throw new SQLException("injected lost reply after commit", "08S01");
            }
            try {
                return method.invoke(real, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return getConnection();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return delegate.isWrapperFor(iface);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }
}
