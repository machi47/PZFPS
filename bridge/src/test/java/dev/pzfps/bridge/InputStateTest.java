package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import zombie.iso.Vector2;

final class InputStateTest {
    @AfterEach
    void reset() {
        InputState.deactivate();
    }

    @Test
    void convertsCameraRelativeMovementToWorldVector() {
        InputState.set(new InputState.Sample(true, 4, 0, 1, (float) (Math.PI / 2), 0, 0, 0));
        Vector2 result = pzWorldMovement(InputState.movementVector(new Vector2()));
        assertEquals(0.0f, result.x, 0.0001f);
        assertEquals(1.0f, result.y, 0.0001f);
    }

    @Test
    void normalizesDiagonalMovementAndTracksButtonEdges() {
        InputState.set(new InputState.Sample(
                true, 8, 1, 1, 0, 0, InputState.CROUCH, 0));
        Vector2 result = pzWorldMovement(InputState.movementVector(new Vector2()));
        assertEquals(1.0f, result.getLength(), 0.0001f);
        assertTrue(InputState.isActionPressed("Crouch"));
        assertTrue(InputState.isActionDown("Crouch"));
        assertFalse(InputState.isActionDown("Main Menu"));
    }

    @Test
    void inactiveInputDoesNotModifyGameVector() {
        Vector2 value = new Vector2(0.25f, -0.75f);
        assertEquals(value, InputState.movementVector(value));
        assertEquals(0.25f, value.x, 0.0001f);
        assertEquals(-0.75f, value.y, 0.0001f);
    }

    private static Vector2 pzWorldMovement(Vector2 input) {
        return new Vector2(input.y + input.x, input.y - input.x);
    }
}
