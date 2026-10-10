package com.cloudkitchen.testsupport;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wraps a real DAO in a proxy that records every call and fails the N-th call of a chosen method, either before the
 * real call runs or after it ran (so the database already holds the statement's effect, which the enclosing
 * transaction must then roll back). Used to prove that every workflow is atomic (TXR-1..TXR-10).
 */
public final class FaultInjector<T> {

    private record Fault(boolean after, int nth, Throwable error) {
    }

    private final T proxy;
    private final Map<String, Fault> faults = new HashMap<>();
    private final Map<String, Integer> counts = new HashMap<>();
    private final List<String> sequence = Collections.synchronizedList(new ArrayList<>());
    private final List<String> detailed = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Throwable> always = new HashMap<>();
    private final Map<String, Map<Integer, Runnable>> before = new HashMap<>();

    @SuppressWarnings("unchecked")
    private FaultInjector(Class<T> type, T real) {
        InvocationHandler handler = (ignored, method, args) -> invoke(real, method, args);
        this.proxy = (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    public static <T> FaultInjector<T> wrap(Class<T> type, T real) {
        return new FaultInjector<>(type, real);
    }

    public T dao() {
        return proxy;
    }

    /** The real call runs (and its effect is in the open transaction), then the error is thrown. */
    public FaultInjector<T> failAfter(String method, int nthCall, Throwable error) {
        faults.put(method, new Fault(true, nthCall, error));
        return this;
    }

    /** The error is thrown instead of running the call. */
    public FaultInjector<T> failBefore(String method, int nthCall, Throwable error) {
        faults.put(method, new Fault(false, nthCall, error));
        return this;
    }

    /** Every call of the method fails before it runs (a persistent outage of that statement). */
    public FaultInjector<T> failAlways(String method, Throwable error) {
        always.put(method, error);
        return this;
    }

    /** Runs {@code action} just before the N-th call of the method (to simulate a competing actor at an exact point). */
    public FaultInjector<T> beforeCall(String method, int nthCall, Runnable action) {
        before.computeIfAbsent(method, k -> new HashMap<>()).put(nthCall, action);
        return this;
    }

    public void clearFaults() {
        faults.clear();
        always.clear();
        before.clear();
    }

    /** {@code name(arg,arg)} per call, without the connection argument. */
    public List<String> detailedSequence() {
        synchronized (detailed) {
            return new ArrayList<>(detailed);
        }
    }

    public synchronized int calls(String method) {
        return counts.getOrDefault(method, 0);
    }

    /** Method names in call order (all methods, all threads). */
    public List<String> sequence() {
        synchronized (sequence) {
            return new ArrayList<>(sequence);
        }
    }

    public void resetCounters() {
        synchronized (this) {
            counts.clear();
        }
        sequence.clear();
        detailed.clear();
    }

    private Object invoke(T real, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        Fault fault;
        int count;
        Throwable permanent;
        Runnable action;
        synchronized (this) {
            count = counts.merge(name, 1, Integer::sum);
            fault = faults.get(name);
            permanent = always.get(name);
            action = before.getOrDefault(name, Map.of()).get(count);
        }
        sequence.add(name);
        StringBuilder label = new StringBuilder(name).append('(');
        if (args != null) {
            boolean first = true;
            for (Object arg : args) {
                if (arg instanceof java.sql.Connection) {
                    continue;
                }
                label.append(first ? "" : ",").append(arg);
                first = false;
            }
        }
        detailed.add(label.append(')').toString());
        if (action != null) {
            action.run();
        }
        if (permanent != null) {
            throw permanent;
        }
        boolean fires = fault != null && fault.nth() == count;
        if (fires && !fault.after()) {
            throw fault.error();
        }
        Object result;
        try {
            result = method.invoke(real, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
        if (fires) {
            throw fault.error();
        }
        return result;
    }
}
