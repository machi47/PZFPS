package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import zombie.iso.Vector3;

final class PerspectiveViewRayTest {
    @Test
    void snapshotRayAndPzMuzzleDirectionDescribeTheSameView() {
        float yaw = 0.83f;
        float pitch = -0.41f;
        WorldState.Player player = new WorldState.Player(
                1,
                2,
                3,
                0,
                11,
                13,
                4,
                (float) Math.cos(yaw),
                (float) Math.sin(yaw),
                pitch,
                "Idle",
                false,
                false,
                false,
                1.62f);

        PerspectiveViewRay.Ray ray = PerspectiveViewRay.fromPlayer(player);
        Vector3 pz = PerspectiveBallistics.pzDirection(yaw, pitch, new Vector3());

        assertEquals(ray.directionX(), pz.x, 0.0001f);
        assertEquals(ray.directionZ(), pz.y, 0.0001f);
        assertEquals(ray.directionY(), pz.z * PerspectiveViewRay.LEVEL_HEIGHT, 0.0001f);
        assertEquals(11.0f, ray.originX(), 0.0001f);
        assertEquals(4 * PerspectiveViewRay.LEVEL_HEIGHT + 1.62f, ray.originY(), 0.0001f);
        assertEquals(13.0f, ray.originZ(), 0.0001f);
    }

    @Test
    void centreRayRemainsNormalizedAtSteepPitch() {
        WorldState.Player player = new WorldState.Player(
                1, 2, 3, 0, 0, 0, 0, 1, 0, 1.5f,
                "Idle", false, false, false, 1.62f);

        PerspectiveViewRay.Ray ray = PerspectiveViewRay.fromPlayer(player);
        float length = (float) Math.sqrt(
                ray.directionX() * ray.directionX()
                        + ray.directionY() * ray.directionY()
                        + ray.directionZ() * ray.directionZ());

        assertEquals(1.0f, length, 0.0001f);
    }
}
