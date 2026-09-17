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
    void usesAuthoredGeometryBeforeStructuralFallbackAndKeepsOnlyObservedFaces() throws Exception {
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
                0, "zombie.iso.IsoObject", "wall", "fixture_chair_0",
                false, false, false, true, false, false, false);
        WorldState.Square square = new WorldState.Square(
                2, 3, 1, 4, 7, 255, 224, 192,
                true, false, true, false, false, false, List.of(object));
        WorldState.Chunk chunk = new WorldState.Chunk(10, 12, 7, 99, List.of(square));

        WorldMeshBuilder.MeshData mesh = new WorldMeshBuilder(registry).build(chunk);

        assertEquals(1, registry.tileCount());
        assertEquals("fixture-sha", registry.sourceSha256());
        assertEquals(1, mesh.primitiveCount());
        assertEquals(1, mesh.coverage().authoredGeometryObjects());
        assertEquals(1, mesh.coverage().flatFallbackFloors());
        assertEquals(0, mesh.coverage().unsupportedObjects());
        assertEquals(24, mesh.vertexCount());
        assertTrue(mesh.vertices().length > 0);
        assertEquals(1, mesh.texturedBatches().size());
        assertEquals(18, mesh.texturedBatches().getFirst().vertexCount());
    }

    @Test
    void placesNorthAndWestWallsOnTileEdgesInsteadOfThroughTheTileCenter() throws Exception {
        Path registryPath = temporary.resolve("empty-walls.json");
        Files.writeString(
                registryPath,
                "{\"schema_version\":1,\"source_sha256\":\"x\",\"tiles\":{}}");
        WorldState.TileObject corner = new WorldState.TileObject(
                0, "zombie.iso.IsoObject", "wall", "walls_fixture_01_0",
                false, false, false, true, true, false, false);
        WorldState.Square square = new WorldState.Square(
                2, 3, 1, 0, 0, 255, 255, 255,
                false, false, true, false, false, false, List.of(corner));
        WorldState.Chunk chunk = new WorldState.Chunk(0, 0, 1, 1, List.of(square));

        WorldMeshBuilder.MeshData mesh =
                new WorldMeshBuilder(TileGeometryRegistry.load(registryPath)).build(chunk);

        assertEquals(2, mesh.primitiveCount());
        assertEquals(1, mesh.coverage().structuralFallbackObjects());
        assertEquals(12, mesh.vertexCount());
        float[] vertices = mesh.texturedBatches().getFirst().vertices();
        for (int vertex = 0; vertex < 6; vertex++) {
            assertEquals(3.0f, vertices[vertex * WorldMeshBuilder.TEXTURED_FLOATS_PER_VERTEX + 2]);
        }
        for (int vertex = 6; vertex < 12; vertex++) {
            assertEquals(2.0f, vertices[vertex * WorldMeshBuilder.TEXTURED_FLOATS_PER_VERTEX]);
        }
    }

    @Test
    void projectsRealFloorSpriteCoordinatesOntoTheKnownTopSurface() throws Exception {
        Path registryPath = temporary.resolve("floor.json");
        Files.writeString(
                registryPath,
                "{\"schema_version\":1,\"source_sha256\":\"x\",\"tiles\":{}}");
        WorldState.TileObject floor = new WorldState.TileObject(
                0, "zombie.iso.IsoObject", "floor", "floors_fixture_01_13",
                false, false, false, false, false, false, false);
        WorldState.Square square = new WorldState.Square(
                0, 0, 0, -1, 0, 255, 255, 255,
                true, true, false, false, false, false, List.of(floor));
        WorldState.Chunk chunk = new WorldState.Chunk(0, 0, 1, 1, List.of(square));

        WorldMeshBuilder.MeshData mesh =
                new WorldMeshBuilder(TileGeometryRegistry.load(registryPath)).build(chunk);

        assertEquals(0, mesh.vertices().length);
        assertEquals(1, mesh.coverage().sourceTexturedFloors());
        assertEquals(6, mesh.vertexCount());
        assertEquals("floors_fixture_01_13", mesh.texturedBatches().getFirst().sprite());
        float[] vertices = mesh.texturedBatches().getFirst().vertices();
        assertEquals(64.0f, vertices[9]);
        assertEquals(192.0f, vertices[10]);
        assertEquals(0.0f, vertices[20]);
        assertEquals(224.0f, vertices[21]);
    }

    @Test
    void retainsLoadedVerticalWorldInsteadOfApplyingIsometricSeenGating() throws Exception {
        Path registryPath = temporary.resolve("empty.json");
        Files.writeString(
                registryPath,
                "{\"schema_version\":1,\"source_sha256\":\"x\",\"tiles\":{}}");
        WorldState.Square square = new WorldState.Square(
                0, 0, 0, -1, 0, 255, 255, 255,
                true, true, false, false, false, false, List.of());
        WorldState.Chunk chunk = new WorldState.Chunk(0, 0, 1, 1, List.of(square));

        WorldMeshBuilder.MeshData mesh =
                new WorldMeshBuilder(TileGeometryRegistry.load(registryPath)).build(chunk);

        assertEquals(6, mesh.vertexCount());
    }

    @Test
    void doesNotInterpretWorldItemSpriteAsMapTileGeometry() throws Exception {
        Path registryPath = temporary.resolve("world-item.json");
        Files.writeString(
                registryPath,
                """
                {
                  "schema_version": 1,
                  "source_sha256": "fixture-sha",
                  "tiles": {
                    "Item_Pot": {
                      "geometry": [{
                        "kind": "box",
                        "min": [-0.5, 0.0, -0.5],
                        "max": [0.5, 1.0, 0.5],
                        "translate": [0.0, 0.0, 0.0],
                        "rotate_degrees": [0.0, 0.0, 0.0]
                      }]
                    }
                  }
                }
                """);
        WorldState.WorldItem placement = new WorldState.WorldItem(
                true,
                42,
                "Base.Hammer",
                "Hammer",
                "WorldItem_Hammer",
                "",
                "media/inventory/world/WItem_Hammer.png",
                2.25f,
                3.75f,
                1.0f,
                0.0f,
                0.0f,
                31.0f,
                1.0f,
                false);
        WorldState.TileObject item = new WorldState.TileObject(
                0,
                "zombie.iso.objects.IsoWorldInventoryObject",
                "WorldInventoryItem",
                "Item_Pot",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                placement);
        WorldState.Square square = new WorldState.Square(
                2, 3, 1, -1, 0, 255, 255, 255,
                false, true, false, false, false, false, List.of(item));
        WorldState.Chunk chunk = new WorldState.Chunk(0, 0, 1, 1, List.of(square));

        WorldMeshBuilder.MeshData mesh =
                new WorldMeshBuilder(TileGeometryRegistry.load(registryPath)).build(chunk);

        assertEquals(0, mesh.primitiveCount());
        assertEquals(0, mesh.vertexCount());
        assertEquals(1, mesh.coverage().nativeWorldItems());
        assertEquals(0, mesh.coverage().unsupportedObjects());
    }

    @Test
    void accountsForUnsupportedObjectsAsVisibleHoles() throws Exception {
        Path registryPath = temporary.resolve("unsupported.json");
        Files.writeString(
                registryPath,
                "{\"schema_version\":1,\"source_sha256\":\"x\",\"tiles\":{}}");
        WorldState.TileObject decor = new WorldState.TileObject(
                0, "zombie.iso.IsoObject", "normal", "fixtures_unknown_01_7",
                false, false, false, false, false, false, false);
        WorldState.Square square = new WorldState.Square(
                0, 0, 0, -1, 0, 255, 255, 255,
                false, true, false, false, false, false, List.of(decor));

        WorldMeshBuilder.MeshData mesh = new WorldMeshBuilder(
                TileGeometryRegistry.load(registryPath))
                .build(new WorldState.Chunk(0, 0, 1, 1, List.of(square)));

        assertEquals(0, mesh.vertexCount());
        assertEquals(1, mesh.coverage().unsupportedObjects());
        assertEquals(0, mesh.coverage().structuralFallbackObjects());
    }
}
