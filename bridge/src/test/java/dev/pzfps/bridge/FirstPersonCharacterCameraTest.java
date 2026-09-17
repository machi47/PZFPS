package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

final class FirstPersonCharacterCameraTest {
    @Test
    void placesStandingCharacterAtPerspectiveWorldCoordinates() {
        Matrix4f transform = FirstPersonCharacterCamera.modelTransform(
                12.5f, 7.25f, 3.0f, 0.0f, false, new Matrix4f());
        Vector3f feet = transform.transformPosition(new Vector3f(0.0f, 0.48f, 0.0f));

        assertEquals(12.5f, feet.x, 0.0001f);
        assertEquals(7.25f, feet.z, 0.0001f);
        assertEquals(9.0f, feet.y, 0.0001f);
    }

    @Test
    void leavesVehicleOccupantScaleForPzSeatTransform() {
        Matrix4f transform = FirstPersonCharacterCamera.modelTransform(
                2.0f, 4.0f, 1.0f, 0.0f, true, new Matrix4f());
        Vector3f oneModelUnit = transform.transformDirection(new Vector3f(0.0f, 1.0f, 0.0f));

        assertEquals(1.0f, oneModelUnit.length(), 0.0001f);
    }
}
