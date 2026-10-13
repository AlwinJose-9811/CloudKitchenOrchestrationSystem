package com.cloudkitchen.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The first-start server must never be reachable from outside the machine, and on Windows (no Unix socket) it has
 * to listen, on the loopback address only. Found on a real Windows start: "Either TCP connections or named pipe
 * connections must be enabled" with {@code --skip-networking}.
 */
class DbBootstrapNetworkArgumentTest {

    @Test
    void unixFirstStartServerHasNoNetwork() {
        assertEquals("--skip-networking", DbBootstrap.networkArgument(false, false));
    }

    @Test
    void windowsFirstStartServerListensOnLoopbackOnly() {
        assertEquals("--bind-address=127.0.0.1", DbBootstrap.networkArgument(false, true));
    }

    @Test
    void normalServerListensOnLoopbackOnEveryPlatform() {
        assertEquals("--bind-address=127.0.0.1", DbBootstrap.networkArgument(true, false));
        assertEquals("--bind-address=127.0.0.1", DbBootstrap.networkArgument(true, true));
    }
}
