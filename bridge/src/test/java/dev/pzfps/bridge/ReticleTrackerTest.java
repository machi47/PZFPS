package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class ReticleTrackerTest {
    @Test
    void distinguishesActionableAndOrdinaryRayHits() {
        assertEquals("none", ReticleTracker.kindFor(null));
        assertEquals("world", ReticleTracker.kindFor(reference(false, false, false, -1)));
        assertEquals("interact", ReticleTracker.kindFor(reference(true, false, false, -1)));
        assertEquals("interact", ReticleTracker.kindFor(reference(false, false, true, -1)));
        assertEquals("interact", ReticleTracker.kindFor(reference(false, false, false, 42)));
    }

    private static InteractionTarget.Reference reference(
            boolean door, boolean window, boolean container, int worldItemId) {
        return new InteractionTarget.Reference(
                1,
                2,
                3,
                4,
                "zombie.iso.IsoObject",
                "",
                "fixture",
                worldItemId,
                door,
                window,
                container);
    }
}
