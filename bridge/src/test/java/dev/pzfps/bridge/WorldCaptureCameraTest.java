package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class WorldCaptureCameraTest {
    @Test
    void keepsNativeMouseCameraIndependentFromBodyLocomotionFacing() {
        WorldCapture.CameraDirection direction = WorldCapture.cameraDirection(
                -1.0f,
                0.0f,
                InputState.Sample.inactive(),
                true,
                0.0f);

        assertEquals(1.0f, direction.x(), 0.0001f);
        assertEquals(0.0f, direction.y(), 0.0001f);
    }

    @Test
    void externalViewYawAlsoOverridesBodyLocomotionFacing() {
        InputState.Sample input = new InputState.Sample(
                        true, 12, 1, 0, (float) (Math.PI / 2.0), 0, 0, 0)
                .sanitized();

        WorldCapture.CameraDirection direction =
                WorldCapture.cameraDirection(1.0f, 0.0f, input, false, 0.0f);

        assertEquals(0.0f, direction.x(), 0.0001f);
        assertEquals(1.0f, direction.y(), 0.0001f);
    }

    @Test
    void usesBodyFacingOutsidePerspectiveMode() {
        WorldCapture.CameraDirection direction = WorldCapture.cameraDirection(
                0.25f,
                -0.75f,
                InputState.Sample.inactive(),
                false,
                1.4f);

        assertEquals(0.25f, direction.x(), 0.0001f);
        assertEquals(-0.75f, direction.y(), 0.0001f);
    }
}
