package com.cloudkitchen.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Phase 8 defect P8-D6: the scratch directories of the application tests were never deleted. */
class AppFixtureCleanupTest {

    @Test
    void theCleanupDeletesTheDirectoriesTheFixtureCreatedAndNothingElse() throws Exception {
        Path parent = Path.of("data", "test").toAbsolutePath();
        Files.createDirectories(parent);
        Path ours = AppFixture.scratchDirectory();
        Files.createDirectories(ours.resolve("logs"));
        Files.writeString(ours.resolve("logs").resolve("orders.log"), "x");
        // look-alikes that this JVM did not create: same prefix, other prefix, a file, and a sibling of ours
        Path foreignSamePrefix = Files.createTempDirectory(parent, AppFixture.SCRATCH_PREFIX + "foreign-");
        Path unrelated = Files.createTempDirectory(parent, "cleanup-test-keep-");
        Path outside = Files.createTempDirectory("cleanup-test-outside-");
        try {
            assertTrue(Files.isDirectory(ours));
            int deleted = AppFixture.deleteScratchDirectories();
            assertTrue(deleted >= 1, "at least the directory of this test");
            assertFalse(Files.exists(ours), "a directory the fixture created is gone with its content");
            assertTrue(Files.isDirectory(foreignSamePrefix), "a look-alike that the fixture did not create is kept");
            assertTrue(Files.isDirectory(unrelated), "an unrelated directory is kept");
            assertTrue(Files.isDirectory(outside), "a directory outside data/test is kept");
            assertEquals(0, AppFixture.deleteScratchDirectories(), "nothing is left to delete: the second call does nothing");
        } finally {
            TestDatabase.deleteTree(foreignSamePrefix);
            TestDatabase.deleteTree(unrelated);
            TestDatabase.deleteTree(outside);
        }
    }
}
