package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
                true, false, false, false, false, false, List.of(object));
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
                false, false, false, false, false, false, List.of(corner));
        WorldState.Chunk chunk = new WorldState.Chunk(0, 0, 1, 1, List.of(square));

        WorldMeshBuilder.MeshData mesh =
                new WorldMeshBuilder(TileGeometryRegistry.load(registryPath)).build(chunk);

        assertEquals(2, mesh.primitiveCount());
        assertEquals(1, mesh.coverage().structuralFallbackObjects());
        assertEquals(2, mesh.coverage().mirroredStructuralFaces());
        assertEquals(12, mesh.vertexCount()); // GPU two-sided draw: no coplanar reverse duplicates
        assertTrue(mesh.texturedBatches().getFirst().wallEdges());
        float[] vertices = mesh.texturedBatches().getFirst().vertices();
        assertEquals(1, vertices[11]); // shared source-object layer, not a physical plane shift
        for (int vertex = 0; vertex < 6; vertex++) {
            assertEquals(3.0f, vertices[vertex * WorldMeshBuilder.TEXTURED_FLOATS_PER_VERTEX + 2]);
        }
        for (int vertex = 6; vertex < 12; vertex++) {
            assertEquals(2.0f, vertices[vertex * WorldMeshBuilder.TEXTURED_FLOATS_PER_VERTEX]);
        }
    }

    @Test
    void doesNotAddDuplicateGeometryOrFloorEdgeFillToDoorAndWindowFallbacks() throws Exception {
        Path registryPath = temporary.resolve("empty-openings.json");
        Files.writeString(
                registryPath,
                "{\"schema_version\":1,\"source_sha256\":\"x\",\"tiles\":{}}");
        WorldState.TileObject door = new WorldState.TileObject(
                0, "zombie.iso.objects.IsoDoor", "door", "fixtures_doors_01_0",
                true, false, true, true, false, false, false);
        WorldState.TileObject window = new WorldState.TileObject(
                1, "zombie.iso.objects.IsoWindow", "window", "fixtures_windows_01_0",
                false, true, false, false, true, false, false);
        WorldState.Square square = new WorldState.Square(
                0, 0, 0, 1, 0, 255, 255, 255,
                false, false, false, false, false, false, List.of(door, window));

        WorldMeshBuilder.MeshData mesh = new WorldMeshBuilder(
                        TileGeometryRegistry.load(registryPath))
                .build(new WorldState.Chunk(0, 0, 1, 1, List.of(square)));

        assertEquals(2, mesh.coverage().structuralFallbackObjects());
        assertEquals(0, mesh.coverage().mirroredStructuralFaces());
        assertEquals(12, mesh.vertexCount());
        assertTrue(mesh.texturedBatches().stream().noneMatch(WorldMeshBuilder.TexturedBatch::solidFloor));
        assertTrue(mesh.texturedBatches().stream().noneMatch(WorldMeshBuilder.TexturedBatch::wallEdges));
    }

    @Test
    void replacesLightSwitchSupportVolumeWithClosedWallOwnedHousing() throws Exception {
        Path registryPath = temporary.resolve("wall-attachment.json");
        Files.writeString(
                registryPath,
                """
                {"schema_version":1,"source_sha256":"x","tiles":{
                  "lighting_indoor_01_1":{"geometry":[{
                    "kind":"box","min":[-0.45,0,-0.5],"max":[-0.4,2.4495,0.5]
                  }]}
                }}
                """);
        WorldState.TileObject fixture = new WorldState.TileObject(
                2,"zombie.iso.objects.IsoLightSwitch","lightswitch","lighting_indoor_01_1",
                false,false,false,false,false,false,false);
        WorldState.Square square = new WorldState.Square(
                0,0,0,1,0,255,255,255,
                false,false,false,false,false,false,List.of(fixture), StructuralPropClip.WEST);

        WorldMeshBuilder.MeshData mesh = new WorldMeshBuilder(
                        TileGeometryRegistry.load(registryPath))
                .build(new WorldState.Chunk(0,0,1,1,List.of(square)));

        assertEquals(1, mesh.texturedBatches().size());
        WorldMeshBuilder.TexturedBatch batch = mesh.texturedBatches().getFirst();
        assertTrue(batch.wallAttachment());
        assertEquals(36, batch.vertexCount());
        float[] vertices = batch.vertices();
        for (int i = 0; i < vertices.length; i += WorldMeshBuilder.TEXTURED_FLOATS_PER_VERTEX) {
            assertTrue(vertices[i] >= .002f && vertices[i] <= .045f);
            assertTrue(vertices[i + 1] >= 1.02f && vertices[i + 1] <= 1.24f);
            assertTrue(vertices[i + 2] >= .29f && vertices[i + 2] <= .71f);
            assertEquals(0, vertices[i + 11]);
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
        assertTrue(mesh.texturedBatches().getFirst().solidFloor());
        float[] vertices = mesh.texturedBatches().getFirst().vertices();
        assertEquals(64.0f, vertices[9]);
        assertEquals(192.0f, vertices[10]);
        assertEquals(0.0f, vertices[WorldMeshBuilder.TEXTURED_FLOATS_PER_VERTEX + 9]);
        assertEquals(224.0f, vertices[WorldMeshBuilder.TEXTURED_FLOATS_PER_VERTEX + 10]);
    }

    @Test
    void usesActualFloorObjectEvenWhenOccupiedSquaresLazyCollisionCacheIsFalse() throws Exception {
        Path registryPath = temporary.resolve("semantic-floor.json");
        Files.writeString(
                registryPath,
                "{\"schema_version\":1,\"source_sha256\":\"x\",\"tiles\":{}}");
        WorldState.TileObject floor = new WorldState.TileObject(
                0,
                "zombie.iso.IsoObject",
                "normal",
                "modded_marble_surface_0",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                true,
                WorldState.WorldItem.none());
        WorldState.Square square = new WorldState.Square(
                0, 0, 0, -1, 0, 255, 255, 255,
                false, true, false, false, false, false, List.of(floor));

        WorldMeshBuilder.MeshData mesh = new WorldMeshBuilder(
                        TileGeometryRegistry.load(registryPath))
                .build(new WorldState.Chunk(0, 0, 1, 1, List.of(square)));

        assertEquals(1, mesh.coverage().sourceTexturedFloors());
        assertEquals(0, mesh.coverage().unsupportedObjects());
        assertEquals(6, mesh.vertexCount());
        assertEquals("modded_marble_surface_0", mesh.texturedBatches().getFirst().sprite());
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
    void leavesAuthoritativeOpeningWhereStairsEnterFromTheLevelBelow() throws Exception {
        Path registryPath = temporary.resolve("stair-opening.json");
        Files.writeString(
                registryPath,
                "{\"schema_version\":1,\"source_sha256\":\"x\",\"tiles\":{}}");
        WorldState.TileObject floor = new WorldState.TileObject(
                0, "zombie.iso.IsoObject", "floor", "floors_fixture_01_13",
                false, false, false, false, false, false, false);
        WorldState.Square opening = new WorldState.Square(
                0, 0, 1, 4, 7, 255, 255, 255,
                true, false, false, false, true, false, List.of(floor));
        WorldState.Square stairsOnThisLevel = new WorldState.Square(
                1, 0, 1, 4, 7, 255, 255, 255,
                true, false, false, true, false, true, List.of(floor));
        WorldState.Chunk chunk = new WorldState.Chunk(
                0, 0, 1, 1, List.of(opening, stairsOnThisLevel));

        WorldMeshBuilder.MeshData mesh =
                new WorldMeshBuilder(TileGeometryRegistry.load(registryPath)).build(chunk);

        assertEquals(1, mesh.coverage().stairFloorOpenings());
        assertEquals(1, mesh.coverage().sourceTexturedFloors());
        assertEquals(6, mesh.vertexCount());
    }

    @Test
    void completesAnInteriorCeilingOnlyWhereAnUpperFloorProvesTheBoundary() throws Exception {
        Path registryPath = temporary.resolve("stacked-room.json");
        Files.writeString(
                registryPath,
                "{\"schema_version\":1,\"source_sha256\":\"x\",\"tiles\":{}}");
        WorldState.Square lower = new WorldState.Square(
                2, 3, 0, 9, 0, 240, 230, 220,
                true, false, false, false, false, false, List.of());
        WorldState.Square upper = new WorldState.Square(
                2, 3, 1, -1, 0, 240, 230, 220,
                true, true, false, false, false, false, List.of());

        WorldMeshBuilder.MeshData mesh = new WorldMeshBuilder(
                        TileGeometryRegistry.load(registryPath))
                .build(new WorldState.Chunk(0, 0, 1, 1, List.of(lower, upper)));

        assertEquals(1, mesh.coverage().completedInteriorCeilings());
        assertEquals(1, mesh.materialBatches().size());
        assertEquals("interior-plaster", mesh.materialBatches().getFirst().material());
        assertEquals(6, mesh.materialBatches().getFirst().vertexCount());
        float[] vertices = mesh.materialBatches().getFirst().vertices();
        assertEquals(3.0f, vertices[1]);
        assertEquals(-1.0f, vertices[4]);
    }

    @Test
    void keepsCeilingOpenWhereUpperSquareReportsStairsBelow() throws Exception {
        Path registryPath = temporary.resolve("ceiling-stair-opening.json");
        Files.writeString(
                registryPath,
                "{\"schema_version\":1,\"source_sha256\":\"x\",\"tiles\":{}}");
        WorldState.Square lower = new WorldState.Square(
                2, 3, 0, 9, 0, 255, 255, 255,
                true, false, true, false, false, false, List.of());
        WorldState.Square upperOpening = new WorldState.Square(
                2, 3, 1, 10, 0, 255, 255, 255,
                true, false, false, false, true, false, List.of());

        WorldMeshBuilder.MeshData mesh = new WorldMeshBuilder(
                        TileGeometryRegistry.load(registryPath))
                .build(new WorldState.Chunk(0, 0, 1, 1, List.of(lower, upperOpening)));

        assertEquals(0, mesh.coverage().completedInteriorCeilings());
        assertTrue(mesh.materialBatches().isEmpty());
    }

    @Test
    void completesTopStoreyCeilingFromAuthoritativeRoofFlag() throws Exception {
        Path registryPath = temporary.resolve("roofed-room.json");
        Files.writeString(
                registryPath,
                "{\"schema_version\":1,\"source_sha256\":\"x\",\"tiles\":{}}");
        WorldState.Square roofed = new WorldState.Square(
                1, 1, 4, 21, 0, 255, 255, 255,
                true, false, true, false, false, false, List.of());

        WorldMeshBuilder.MeshData mesh = new WorldMeshBuilder(
                        TileGeometryRegistry.load(registryPath))
                .build(new WorldState.Chunk(0, 0, 1, 1, List.of(roofed)));

        assertEquals(1, mesh.coverage().completedInteriorCeilings());
        assertEquals(6, mesh.materialBatches().getFirst().vertexCount());
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
        WorldState.TileObject repeatedDecor = new WorldState.TileObject(
                1, "zombie.iso.IsoObject", "normal", "fixtures_unknown_01_7",
                false, false, false, false, false, false, false);
        WorldState.Square square = new WorldState.Square(
                0, 0, 0, -1, 0, 255, 255, 255,
                false, true, false, false, false, false, List.of(decor, repeatedDecor));

        WorldMeshBuilder.MeshData mesh = new WorldMeshBuilder(
                TileGeometryRegistry.load(registryPath))
                .build(new WorldState.Chunk(2, 3, 1, 1, List.of(square)));

        assertEquals(0, mesh.vertexCount());
        assertEquals(2, mesh.coverage().unsupportedObjects());
        assertEquals(0, mesh.coverage().structuralFallbackObjects());
        assertEquals(2, mesh.unsupportedSprites().get("fixtures_unknown_01_7"));
        int chunkSize = zombie.iso.IsoChunkMap.CHUNK_SIZE_IN_SQUARES;
        assertEquals(2.0f * chunkSize, mesh.minX());
        assertEquals(3.0f * chunkSize, mesh.maxX());
        assertEquals(3.0f * chunkSize, mesh.minZ());
        assertEquals(4.0f * chunkSize, mesh.maxZ());
    }

    @Test
    void separatesUnsupportedCollisionBlockersFromAppearanceOnlyHoles() throws Exception {
        Path registryPath = temporary.resolve("collision-holes.json");
        Files.writeString(
                registryPath,
                "{\"schema_version\":1,\"source_sha256\":\"x\",\"tiles\":{}}");
        WorldState.TileObject invisibleBlocker = new WorldState.TileObject(
                0, "zombie.iso.IsoObject", "normal", "fixtures_blocker_01_2",
                false, false, false, false, false, false, false, false,
                true, false, true, WorldState.WorldItem.none());
        WorldState.TileObject appearanceOnly = new WorldState.TileObject(
                1, "zombie.iso.IsoObject", "normal", "fixtures_decor_01_4",
                false, false, false, false, false, false, false, false,
                false, false, false, WorldState.WorldItem.none());
        WorldState.Square square = new WorldState.Square(
                0, 0, 0, -1, 0, 255, 255, 255,
                false, true, false, false, false, false,
                List.of(invisibleBlocker, appearanceOnly));
        WorldState.Chunk chunk = new WorldState.Chunk(0, 0, 1, 1, List.of(square));

        WorldMeshBuilder.MeshData mesh =
                new WorldMeshBuilder(TileGeometryRegistry.load(registryPath)).build(chunk);

        assertEquals(2, mesh.coverage().unsupportedObjects());
        assertEquals(1, mesh.coverage().collisionCriticalUnsupportedObjects());
        assertEquals(Map.of("fixtures_blocker_01_2", 1), mesh.unsupportedCollisionSprites());
    }
}
