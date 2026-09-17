package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class NativeVehiclePassTest {
    @Test
    void cullsByPerspectiveHeadingAndVehicleDistance() {
        WorldState.Player player = new WorldState.Player(
                1, 1, 1, 0, 10, 20, 0, 1, 0,
                0, "Idle", false, false, false, 1.62f);

        assertTrue(NativeVehiclePass.visible(player, 20.0f, 20.0f));
        assertTrue(NativeVehiclePass.visible(player, 9.0f, 20.0f));
        assertFalse(NativeVehiclePass.visible(player, 0.0f, 20.0f));
        assertFalse(NativeVehiclePass.visible(player, 90.0f, 20.0f));
    }
}
