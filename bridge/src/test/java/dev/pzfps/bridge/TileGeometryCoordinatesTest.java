package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

final class TileGeometryCoordinatesTest {
    @Test
    void primitiveTransformMatchesInstalledJomlConvention() {
        for (float[] angles : new float[][] {{20, 35, 70}, {179.9854f, 89.9802f, -179.9854f}, {270, 0, 0}}) {
            TileGeometryRegistry.Primitive primitive = new TileGeometryRegistry.Primitive(
                    "box", .2f, .3f, .4f, angles[0], angles[1], angles[2],
                    -.5f, 0, -.2f, .5f, 1, .2f, 0, 0, 0, "", List.of());
            Matrix4f nativeTransform = new Matrix4f().translation(.2f, .3f, .4f).rotateXYZ(
                    (float) Math.toRadians(angles[0]), (float) Math.toRadians(angles[1]),
                    (float) Math.toRadians(angles[2]));
            for (Vector3f point : new Vector3f[] {new Vector3f(-.5f, 0, -.2f), new Vector3f(.5f, 1, .2f)}) {
                Vector3f expected = nativeTransform.transformPosition(new Vector3f(point));
                float[] actual = WorldMeshBuilder.transformLocal(primitive, point.x, point.y, point.z);
                assertEquals(expected.x, actual[0], .00001);
                assertEquals(expected.y, actual[1], .00001);
                assertEquals(expected.z, actual[2], .00001);
            }
        }
    }

    @Test
    void sourcePixelsMatchInstalledTileDepthProjection() throws Exception {
        // Invoke the installed game's actual projection setup, not a copy of our own formula.
        var method = Class.forName("zombie.tileDepth.TileGeometryUtils")
                .getDeclaredMethod("calcMatricesForSquare", Matrix4f.class, Matrix4f.class);
        method.setAccessible(true);
        Matrix4f projection = new Matrix4f();
        Matrix4f view = new Matrix4f();
        method.invoke(null, projection, view);
        Matrix4f nativeMvp = projection.mul(view);
        for (float[] point : new float[][] {{0, 0, 0}, {.3f, 1.2f, -.4f}, {-.5f, 2.4494898f, .5f}}) {
            Vector3f projected = nativeMvp.project(point[0], point[1], point[2],
                    new int[] {0, 0, 128, 256}, new Vector3f());
            float[] actual = WorldMeshBuilder.sourcePixel(point);
            assertEquals(projected.x, actual[0], .0001);
            assertEquals(256 - projected.y, actual[1], .0001);
        }
        assertEquals(3, 2.4494898f * WorldMeshBuilder.AUTHORED_HEIGHT_TO_WORLD, .00001);
    }
}
