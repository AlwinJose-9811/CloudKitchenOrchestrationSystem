package com.cloudkitchen.web.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.testsupport.FakeSession;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SDD 10.4: one-time messages kept in the session and consumed by the first render. */
class FlashTest {

    @Test
    void messagesAreReturnedInOrderAndOnlyOnce() {
        HttpSession session = FakeSession.create();
        Flash.add(session, Flash.Type.SUCCESS, "Saved.");
        Flash.add(session, Flash.Type.ERROR, "Not saved.");
        assertEquals(List.of(new Flash(Flash.Type.SUCCESS, "Saved."), new Flash(Flash.Type.ERROR, "Not saved.")), Flash.consume(session));
        assertEquals(List.of(), Flash.consume(session), "consumed messages are gone");
        assertNull(session.getAttribute(Flash.ATTRIBUTE));
    }

    @Test
    void noSessionOrNoMessagesGivesAnEmptyList() {
        assertEquals(List.of(), Flash.consume(null));
        assertEquals(List.of(), Flash.consume(FakeSession.create()));
    }

    @Test
    void theCssClassIsTheLowerCaseTypeName() {
        assertEquals("success", Flash.Type.SUCCESS.cssClass());
        assertEquals("error", Flash.Type.ERROR.cssClass());
        assertEquals("info", Flash.Type.INFO.cssClass());
    }

    @Test
    void aMessageNeedsATypeAndATextAndTheQueueIsBounded() {
        assertThrows(NullPointerException.class, () -> new Flash(null, "x"));
        assertThrows(NullPointerException.class, () -> new Flash(Flash.Type.INFO, null));
        HttpSession session = FakeSession.create();
        for (int i = 0; i < 50; i++) {
            Flash.add(session, Flash.Type.INFO, "m" + i);
        }
        List<Flash> kept = Flash.consume(session);
        assertEquals(20, kept.size());
        assertEquals("m30", kept.get(0).text(), "the oldest messages are dropped");
        assertEquals("m49", kept.get(19).text());
    }

    @Test
    void messagesAddedFromManyThreadsAreNotLost() throws Exception {
        HttpSession session = FakeSession.create();
        int threads = 8;
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads, r -> new Thread(r, "flash-test"));
        try {
            java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int n = i;
                futures.add(pool.submit(() -> {
                    go.await();
                    Flash.add(session, Flash.Type.INFO, "t" + n);
                    return null;
                }));
            }
            go.countDown();
            for (var future : futures) {
                future.get(10, java.util.concurrent.TimeUnit.SECONDS);
            }
            assertEquals(threads, Flash.consume(session).size());
        } finally {
            pool.shutdownNow();
        }
        assertTrue(Flash.consume(session).isEmpty());
    }
}
