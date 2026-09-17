package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zombie.tileDepth.CylinderUtils;
import zombie.vehicles.UI3DScene;

final class TileGeometryCoordinatesTest {
    @TempDir Path temporary;

    @Test
    void uprightCylinderUsesNativeCenteredZBeforeAuthoredRotation() throws Exception {
        // Verify the native convention with its real intersection routine.
        UI3DScene.Ray ray = new UI3DScene.Ray();
        ray.origin.set(2, 0, .8f);
        ray.direction.set(-1, 0, .01f);
        CylinderUtils.IntersectionRecord hit = new CylinderUtils.IntersectionRecord();
        org.junit.jupiter.api.Assertions.assertTrue(CylinderUtils.intersect(.25f, 2, ray, hit));
        assertEquals(.25, hit.location.x, .00001);

        Path path = temporary.resolve("cylinder.json");
        Files.writeString(path, """
                {"schema_version":1,"source_sha256":"test","tiles":{"fixture":{
                  "geometry":[{"kind":"cylinder","translate":[0,1,0],
                    "rotate_degrees":[270,0,0],"radius1":0.25,"radius2":0.25,"height":2}]
                }}}
                """);
        WorldState.TileObject object = new WorldState.TileObject(
                0, "IsoObject", "normal", "fixture", false, false, false, false, false, false, false);
        WorldState.Square square = new WorldState.Square(
                0, 0, 0, 0, 0, 255, 255, 255,
                false, false, false, false, false, false, List.of(object));
        WorldMeshBuilder.MeshData mesh = new WorldMeshBuilder(TileGeometryRegistry.load(path))
                .build(new WorldState.Chunk(0, 0, 1, 1, List.of(square)));
        float low = Float.POSITIVE_INFINITY;
        float high = Float.NEGATIVE_INFINITY;
        float[] vertices = mesh.texturedBatches().getFirst().vertices();
        for (int i = 1; i < vertices.length; i += WorldMeshBuilder.TEXTURED_FLOATS_PER_VERTEX) {
            low = Math.min(low, vertices[i]);
            high = Math.max(high, vertices[i]);
        }
        assertEquals(0, low, .00001);
        assertEquals(2 * WorldMeshBuilder.AUTHORED_HEIGHT_TO_WORLD, high, .00001);
    }

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
