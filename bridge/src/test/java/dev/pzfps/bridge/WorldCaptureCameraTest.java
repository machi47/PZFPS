package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class WorldCaptureCameraTest {
    @Test
    void keepsNativeMouseCameraIndependentFromBodyLocomotionFacing() {
        PerspectiveViewRay.Orientation direction = PerspectiveViewRay.captureOrientation(
                -1.0f,
                0.0f,
                0.0f,
                InputState.Sample.inactive(),
                true,
                0.0f,
                0.25f);

        assertEquals(1.0f, direction.horizontalX(), 0.0001f);
        assertEquals(0.0f, direction.horizontalY(), 0.0001f);
        assertEquals(0.25f, direction.pitch(), 0.0001f);
    }

    @Test
    void externalViewYawAlsoOverridesBodyLocomotionFacing() {
        InputState.Sample input = new InputState.Sample(
                        true, 12, 1, 0, (float) (Math.PI / 2.0), 0, 0, 0)
                .sanitized();

        PerspectiveViewRay.Orientation direction = PerspectiveViewRay.captureOrientation(
                1.0f, 0.0f, -0.2f, input, false, 0.0f, 0.0f);

        assertEquals(0.0f, direction.horizontalX(), 0.0001f);
        assertEquals(1.0f, direction.horizontalY(), 0.0001f);
        assertEquals(0.0f, direction.pitch(), 0.0001f);
    }

    @Test
    void usesBodyFacingOutsidePerspectiveMode() {
        PerspectiveViewRay.Orientation direction = PerspectiveViewRay.captureOrientation(
                0.25f,
                -0.75f,
                -0.4f,
                InputState.Sample.inactive(),
                false,
                1.4f,
                0.8f);

        float length = (float) Math.hypot(0.25f, -0.75f);
        assertEquals(0.25f / length, direction.horizontalX(), 0.0001f);
        assertEquals(-0.75f / length, direction.horizontalY(), 0.0001f);
        assertEquals(-0.4f, direction.pitch(), 0.0001f);
    }
}
