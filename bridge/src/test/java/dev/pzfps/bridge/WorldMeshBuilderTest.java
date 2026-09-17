package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorldMeshBuilderTest {
    @TempDir Path temporary;

    @Test
    void compilesVisiblePzSquareIntoFloorAndTileGeometry() throws Exception {
        Path registryPath = temporary.resolve("tile-geometry.json");
        Files.writeString(
                registryPath,
                """
                {
                  "schema_version": 1,
                  "source_sha256": "fixture-sha",
                  "tiles": {
                    "fixture_chair_0": {
                      "geometry": [{
                        "kind": "box",
                        "min": [-0.25, 0.0, -0.25],
                        "max": [0.25, 0.75, 0.25],
                        "translate": [0.0, 0.0, 0.0],
                        "rotate_degrees": [0.0, 0.0, 0.0]
                      }]
                    }
                  }
                }
                """);
        TileGeometryRegistry registry = TileGeometryRegistry.load(registryPath);
        WorldState.TileObject object = new WorldState.TileObject(
                0, "zombie.iso.IsoObject", "normal", "fixture_chair_0",
                false, false, false, false, false);
        WorldState.Square square = new WorldState.Square(
                2, 3, 1, 4, 7, 255, 224, 192, true, false, true, List.of(object));
        WorldState.Chunk chunk = new WorldState.Chunk(10, 12, 7, 99, List.of(square));

        WorldMeshBuilder.MeshData mesh = new WorldMeshBuilder(registry).build(chunk);

        assertEquals(1, registry.tileCount());
        assertEquals("fixture-sha", registry.sourceSha256());
        assertEquals(1, mesh.primitiveCount());
        assertEquals(42, mesh.vertexCount());
        assertTrue(mesh.vertices().length > 0);
    }

    @Test
    void doesNotRenderSquaresPzSaysAreUndiscovered() throws Exception {
        Path registryPath = temporary.resolve("empty.json");
        Files.writeString(
                registryPath,
                "{\"schema_version\":1,\"source_sha256\":\"x\",\"tiles\":{}}");
        WorldState.Square square = new WorldState.Square(
                0, 0, 0, -1, 0, 255, 255, 255, true, true, false, List.of());
        WorldState.Chunk chunk = new WorldState.Chunk(0, 0, 1, 1, List.of(square));

        WorldMeshBuilder.MeshData mesh =
                new WorldMeshBuilder(TileGeometryRegistry.load(registryPath)).build(chunk);

        assertEquals(0, mesh.vertexCount());
    }
}
