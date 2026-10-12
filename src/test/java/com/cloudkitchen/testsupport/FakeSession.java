package com.cloudkitchen.testsupport;

import jakarta.servlet.http.HttpSession;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** An {@link HttpSession} that only stores attributes, for unit tests of the web support classes (no Mockito). */
public final class FakeSession {

    private FakeSession() {
    }

    public static HttpSession create() {
        Map<String, Object> attributes = new ConcurrentHashMap<>();
        return (HttpSession) Proxy.newProxyInstance(FakeSession.class.getClassLoader(), new Class<?>[] {HttpSession.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getAttribute":
                    return attributes.get((String) args[0]);
                case "setAttribute":
                    attributes.put((String) args[0], args[1]);
                    return null;
                case "removeAttribute":
                    attributes.remove((String) args[0]);
                    return null;
                case "toString":
                    return "FakeSession" + attributes.keySet();
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    throw new UnsupportedOperationException(method.getName());
            }
        });
    }
}
