package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.FrustumIntersection;
import org.junit.jupiter.api.Test;

final class PerspectiveVisibilityTest {
    @Test
    void steepDownwardTowerViewIncludesGroundWithoutSameFloorGate() {
        float pitchToGround = (float) -Math.atan2(12.0f * 3.0f + 1.62f, 10.0f);
        WorldState.Player player = new WorldState.Player(
                1, 1, 1, 0, 10, 20, 12, 1, 0,
                pitchToGround, "Idle", false, false, false, 1.62f);
        InProcessWorldRenderer.CameraMatrices camera =
                InProcessWorldRenderer.perspectiveCamera(player, player.eyeHeight(), 1920, 1080);
        FrustumIntersection frustum = new FrustumIntersection(camera.combined());

        assertTrue(frustum.testSphere(20.0f, 0.5f, 20.0f, 1.0f));
        assertFalse(frustum.testSphere(0.0f, 36.5f, 20.0f, 1.0f));
    }
}
