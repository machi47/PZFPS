package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import zombie.iso.Vector3;

final class PerspectiveBallisticsTest {
    @Test
    void convertsVisualPitchToPzFloorCoordinates() {
        Vector3 direction = PerspectiveBallistics.pzDirection(
                0.0f, (float) Math.toRadians(30.0), new Vector3());

        assertEquals((float) Math.cos(Math.toRadians(30.0)), direction.x, 0.0001f);
        assertEquals(0.0f, direction.y, 0.0001f);
        assertEquals(0.5f / PerspectiveBallistics.LEVEL_HEIGHT, direction.z, 0.0001f);
    }

    @Test
    void nativeCameraNegativeZAxisFollowsPerspectiveRay() {
        float yaw = 0.73f;
        float pitch = -0.31f;
        Quaternionf rotation = PerspectiveBallistics.bulletCameraRotation(
                yaw, pitch, new Quaternionf());
        Vector3f actual = rotation.transform(new Vector3f(0.0f, 0.0f, -1.0f));

        float horizontal = (float) Math.cos(pitch);
        Vector3f expected = new Vector3f(
                        (float) Math.cos(yaw) * horizontal,
                        (float) Math.sin(pitch)
                                * PerspectiveBallistics.BALLISTICS_VERTICAL_SCALE
                                / PerspectiveBallistics.LEVEL_HEIGHT,
                        (float) Math.sin(yaw) * horizontal)
                .normalize();
        assertEquals(expected.x, actual.x, 0.0001f);
        assertEquals(expected.y, actual.y, 0.0001f);
        assertEquals(expected.z, actual.z, 0.0001f);
    }
}
