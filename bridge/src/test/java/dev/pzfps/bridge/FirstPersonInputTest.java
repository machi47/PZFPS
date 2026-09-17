package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import zombie.iso.Vector2;

final class FirstPersonInputTest {
    @Test
    void makesForwardInputFollowCameraYaw() {
        Vector2 value = FirstPersonInput.rotateMovement(
                new Vector2(0.0f, -1.95f), (float) (Math.PI / 2.0));
        assertEquals(0.0f, value.x, 0.0001f);
        assertEquals(1.95f, value.y, 0.0001f);
    }

    @Test
    void makesStrafeInputPerpendicularToCamera() {
        Vector2 value = FirstPersonInput.rotateMovement(new Vector2(1.95f, 0.0f), 0.0f);
        assertEquals(0.0f, value.x, 0.0001f);
        assertEquals(1.95f, value.y, 0.0001f);
    }
}
