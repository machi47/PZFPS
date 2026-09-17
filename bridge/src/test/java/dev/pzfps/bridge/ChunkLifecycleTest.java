package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

final class ChunkLifecycleTest {
    @Test
    void keepsAcceptedChunkAcrossTransientMissingCaptures() {
        ChunkLifecycle lifecycle = new ChunkLifecycle(3, 2);

        assertEquals(Set.of(7L), lifecycle.observePresence(Set.of(7L)).added());
        assertTrue(lifecycle.observePresence(Set.of()).removed().isEmpty());
        assertTrue(lifecycle.observePresence(Set.of()).removed().isEmpty());
        assertEquals(Set.of(7L), lifecycle.observePresence(Set.of()).removed());
    }

    @Test
    void returningChunkClearsItsMissingStreak() {
        ChunkLifecycle lifecycle = new ChunkLifecycle(2, 2);

        lifecycle.observePresence(Set.of(11L));
        lifecycle.observePresence(Set.of());
        lifecycle.observePresence(Set.of(11L));

        assertTrue(lifecycle.observePresence(Set.of()).removed().isEmpty());
        assertEquals(Set.of(11L), lifecycle.observePresence(Set.of()).removed());
    }

    @Test
    void acceptsInitialFingerprintAndRequiresStableReplacement() {
        ChunkLifecycle lifecycle = new ChunkLifecycle(2, 2);

        assertTrue(lifecycle.acceptFingerprint(3L, 100L));
        assertFalse(lifecycle.acceptFingerprint(3L, 101L));
        assertFalse(lifecycle.acceptFingerprint(3L, 102L));
        assertFalse(lifecycle.acceptFingerprint(3L, 101L));
        assertTrue(lifecycle.acceptFingerprint(3L, 101L));
        assertFalse(lifecycle.acceptFingerprint(3L, 101L));
    }
}
