package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;

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
    void geometryDoesNotLoseSmallOffsetsAtLargeMapCoordinates(@TempDir Path temporary) throws Exception {
        Path path = temporary.resolve("precision.json");
        Files.writeString(path, """
                {"schema_version":1,"tiles":{"test_precision":{"geometry":[
                {"kind":"polygon","points":[[0.00013,0],[0.00027,0],[0.00027,1],[0.00013,1]]},
                {"kind":"polygon","points":[[-1.00013,0],[-0.33327,0.37513],[0.77117,1.00013]]}
                ]}}}
                """);
        var object = new WorldState.TileObject(0,"IsoObject","normal","test_precision",
                false,false,false,false,false,false,false);
        var square = new WorldState.Square(0,0,0,-1,0,255,255,255,
                false,true,false,false,false,false,List.of(object));
        var builder = new WorldMeshBuilder(TileGeometryRegistry.load(path));
        var near = builder.build(new WorldState.Chunk(0,0,1,1,List.of(square)));
        assertTrue(near.vertexCount() > 0);
        for (int chunk : new int[] {1024, 1364, -1364, 4096}) {
            var distant = builder.build(new WorldState.Chunk(chunk,chunk,1,1,List.of(square)));
            assertArrayEquals(near.vertices(), distant.vertices());
            assertArrayEquals(near.texturedBatches().getFirst().vertices(),
                    distant.texturedBatches().getFirst().vertices(),
                    "Position, normals, UV and layers must not depend on map origin");
            double origin = chunk * (double) zombie.iso.IsoChunkMap.CHUNK_SIZE_IN_SQUARES;
            assertTrue(distant.minX() <= origin + near.minX());
            assertTrue(distant.maxX() >= origin + near.maxX());
            assertEquals(near.minY(), distant.minY());
            assertEquals(near.maxY(), distant.maxY());
        }
    }
}
