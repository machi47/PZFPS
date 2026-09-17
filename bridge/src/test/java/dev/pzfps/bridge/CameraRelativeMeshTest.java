package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

final class CameraRelativeMeshTest {
    private static WorldState.Player player(float x, float z, float yaw) {
        return new WorldState.Player(1, 1, 1, 0, x, z, 0,
                (float) Math.cos(yaw), (float) Math.sin(yaw), .13f,
                "Idle", false, false, false, 1.62f);
    }

    @Test
    void mapTranslationDoesNotQuantizeMouseDirectionOrLocalProjection() {
        for (float yaw : new float[] {.001f, .00101f, .73f, 2.83f}) {
            var near = player(.25f, .5f, yaw);
            var distant = player(8192.25f, 8192.5f, yaw);
            var ca = InProcessWorldRenderer.perspectiveCamera(near, 1.62f, 1920, 1080);
            var cb = InProcessWorldRenderer.perspectiveCamera(distant, 1.62f, 1920, 1080);
            Matrix4f a = InProcessWorldRenderer.relativeMatrix(ca, near, 1.62f, 0, 0);
            Matrix4f b = InProcessWorldRenderer.relativeMatrix(cb, distant, 1.62f, 8192, 8192);
            Vector4f expected = a.transform(new Vector4f(3, 1, 2, 1));
            Vector4f actual = b.transform(new Vector4f(3, 1, 2, 1));
            assertEquals(expected.x, actual.x, .000001);
            assertEquals(expected.y, actual.y, .000001);
            assertEquals(expected.z, actual.z, .000001);
            assertEquals(expected.w, actual.w, .000001);
        }
    }

    @Test
    void rebaseChangesOnlyPositionAndNeverMutatesSnapshot() {
        float[] source = {8193, 2, 8195, 0, 1, 0, .7f, .8f, .9f, 64, 224, 3};
        float[] result = InProcessWorldRenderer.relativeVertices(source, 12, 8192, 8192);
        assertEquals(1, result[0]);
        assertEquals(2, result[1]);
        assertEquals(3, result[2]);
        assertEquals(8193, source[0]);
        for (int i = 3; i < source.length; i++) assertEquals(source[i], result[i]);
    }
}
