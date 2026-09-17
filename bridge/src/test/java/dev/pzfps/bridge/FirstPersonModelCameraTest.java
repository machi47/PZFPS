package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

final class FirstPersonModelCameraTest {
    @Test
    void mapsPzWorldCoordinatesIntoPerspectiveAxes() {
        Matrix4f transform = FirstPersonModelCamera.modelTransform(
                12.5f, 7.25f, 3.0f, new Vector3f(), new Matrix4f());
        Vector3f transformedOrigin = transform.transformPosition(new Vector3f());

        assertEquals(12.5f, transformedOrigin.x, 0.0001f);
        assertEquals(7.25f, transformedOrigin.z, 0.0001f);
        assertEquals(8.28f, transformedOrigin.y, 0.0001f);
    }
}
