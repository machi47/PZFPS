package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

final class FenceCoverageTest {
    @Test void opaqueFenceFamiliesDoNotClassifyGlassOrBloodAsCoverage() {
        for (String sprite : new String[]{"fencing_01_0", "fencing_damaged_04_4",
                "fencing_burnt_01_2", "fixtures_doors_fences_01_7"})
            assertTrue(InProcessWorldRenderer.cutoutFence(sprite), sprite);
        for (String sprite : new String[]{"fixtures_windows_01_0", "fixtures_doors_01_1",
                "overlay_blood_fence_01_0", "walls_exterior_house_01_0", ""})
            assertFalse(InProcessWorldRenderer.cutoutFence(sprite), sprite);
    }
}
