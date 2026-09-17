package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import zombie.iso.Vector2;

final class FirstPersonInputTest {
    @Test
    void makesForwardInputFollowCameraYaw() {
        Vector2 input = FirstPersonInput.rotateDigitalMovement(
                new Vector2(0.0f, -1.95f), (float) (Math.PI / 2.0), 1.0f, 0.0f);
        Vector2 world = pzWorldMovement(input);
        assertEquals(0.0f, world.x, 0.0001f);
        assertEquals(1.95f, world.y, 0.0001f);
    }

    @Test
    void makesStrafeInputPerpendicularToCamera() {
        Vector2 input = FirstPersonInput.rotateDigitalMovement(
                new Vector2(1.95f, 0.0f), 0.0f, 0.0f, 1.0f);
        Vector2 world = pzWorldMovement(input);
        assertEquals(0.0f, world.x, 0.0001f);
        assertEquals(1.95f, world.y, 0.0001f);
    }

    @Test
    void mapsAllFourDigitalDirectionsWithoutCollapsingThemToForward() {
        float yaw = 0.37f;
        Vector2 forward = pzWorldMovement(FirstPersonInput.rotateDigitalMovement(
                new Vector2(0, -1), yaw, 1, 0));
        Vector2 backward = pzWorldMovement(FirstPersonInput.rotateDigitalMovement(
                new Vector2(0, 1), yaw, -1, 0));
        Vector2 right = pzWorldMovement(FirstPersonInput.rotateDigitalMovement(
                new Vector2(1, 0), yaw, 0, 1));
        Vector2 left = pzWorldMovement(FirstPersonInput.rotateDigitalMovement(
                new Vector2(-1, 0), yaw, 0, -1));

        assertEquals(-forward.x, backward.x, 0.0001f);
        assertEquals(-forward.y, backward.y, 0.0001f);
        assertEquals(-right.x, left.x, 0.0001f);
        assertEquals(-right.y, left.y, 0.0001f);
        assertEquals(0.0f, forward.x * right.x + forward.y * right.y, 0.0001f);
    }

    private static Vector2 pzWorldMovement(Vector2 input) {
        return new Vector2(input.y + input.x, input.y - input.x);
    }
}
