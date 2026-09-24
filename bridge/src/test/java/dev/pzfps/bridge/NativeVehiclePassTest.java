package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import zombie.core.skinnedmodel.model.ModelSlotRenderData;

final class NativeVehiclePassTest {
    @Test
    void retainsAllHeadingsWithinVehicleRangeForExactRenderFrustum() {
        WorldState.Player player = new WorldState.Player(
                1, 1, 1, 0, 10, 20, 0, 1, 0,
                0, "Idle", false, false, false, 1.62f);

        assertTrue(NativeVehiclePass.withinHorizontalRange(player, 20.0f, 20.0f));
        assertTrue(NativeVehiclePass.withinHorizontalRange(player, 9.0f, 20.0f));
        assertTrue(NativeVehiclePass.withinHorizontalRange(player, 0.0f, 20.0f));
        assertFalse(NativeVehiclePass.withinHorizontalRange(player, 90.0f, 20.0f));
    }

    @Test
    void perspectiveVehicleSnapshotDoesNotInheritIsometricVisibilityFade() {
        ModelSlotRenderData data = new ModelSlotRenderData();
        data.alpha = 0.0f;

        NativeVehiclePresentation.usePerspectiveVisibility(data);

        assertEquals(1.0f, data.alpha);
    }
}
