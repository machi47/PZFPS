package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RoofDepthSurfaceTest {
    @TempDir Path temporary;

    @Test
    void supplementalDepthTriangleFillsOnlyMissingAuthoredGeometry() throws Exception {
        Path authored = temporary.resolve("authored.json");
        Path derived = temporary.resolve("derived.json");
        Files.writeString(authored, """
                {"schema_version":1,"source_sha256":"authored","tiles":{}}
                """);
        Files.writeString(derived, """
                {"schema_version":1,"source_sha256":"depth","tiles":{"roofs_02_3":{
                  "geometry":[{"kind":"triangle","points":[[-.5,0,-.5],[.5,0,-.5],[.5,1,.5]]}]
                }}}
                """);
        TileGeometryRegistry registry = TileGeometryRegistry.load(authored, derived);
        assertEquals(1, registry.geometry("roofs_02_3").size());
        assertEquals("triangle", registry.geometry("roofs_02_3").getFirst().kind());

        WorldState.TileObject roof = new WorldState.TileObject(
                0, "IsoObject", "normal", "roofs_02_3",
                false, false, false, false, false, false, false);
        WorldState.Square square = new WorldState.Square(
                0, 0, 0, 0, 0, 255, 255, 255,
                false, false, false, false, false, false, List.of(roof));
        WorldMeshBuilder.MeshData mesh = new WorldMeshBuilder(registry)
                .build(new WorldState.Chunk(0, 0, 1, 1, List.of(square)));
        assertEquals(1, mesh.coverage().authoredGeometryObjects());
        assertEquals(3, mesh.texturedBatches().getFirst().vertexCount());
    }

    @Test
    void roofWallFamilyWithoutEvidenceIsNotTurnedIntoVerticalWall() throws Exception {
        Path authored = temporary.resolve("empty.json");
        Files.writeString(authored, """
                {"schema_version":1,"source_sha256":"authored","tiles":{}}
                """);
        WorldState.TileObject roof = new WorldState.TileObject(
                0, "IsoObject", "wall", "walls_exterior_roofs_03_0",
                false, false, false, true, false, false, false);
        WorldState.Square square = new WorldState.Square(
                0, 0, 0, 0, 0, 255, 255, 255,
                false, false, false, false, false, false, List.of(roof));
        WorldMeshBuilder.MeshData mesh = new WorldMeshBuilder(TileGeometryRegistry.load(authored))
                .build(new WorldState.Chunk(0, 0, 1, 1, List.of(square)));
        assertEquals(1, mesh.coverage().unsupportedObjects());
        assertFalse(mesh.unsupportedSprites().isEmpty());
        assertEquals(0, mesh.coverage().structuralFallbackObjects());
    }

    @Test
    void contextualRoofSurfaceRequiresDeclaredNeighbourFromSnapshot() throws Exception {
        Path authored = temporary.resolve("authored-context.json");
        Path derived = temporary.resolve("derived-context.json");
        Files.writeString(authored, """
                {"schema_version":1,"source_sha256":"authored","tiles":{}}
                """);
        Files.writeString(derived, """
                {"schema_version":1,"source_sha256":"depth","tiles":{
                  "roofs_colour_0":{"geometry":[],"properties":{"depth_target":"roofs_shape_0"}}
                },"contextual_tiles":{
                  "roofs_overlay_0":{
                    "geometry":[{"kind":"triangle","points":[[-.5,0,-.5],[.5,0,-.5],[.5,1,.5]]}],
                    "properties":{
                      "depth_target":"roofs_shape_1",
                      "context_joins":[{
                        "relation":"south","offset":[0,1,0],"targets":["roofs_shape_0"]
                      }]
                    }
                  }
                }}
                """);
        TileGeometryRegistry registry = TileGeometryRegistry.load(authored, derived);
        assertEquals(1, registry.contextualTileCount());

        WorldState.TileObject overlay = new WorldState.TileObject(
                0, "IsoObject", "WestRoofT", "roofs_overlay_0",
                false, false, false, false, false, false, false);
        WorldState.Square source = new WorldState.Square(
                0, 0, 1, -1, 0, 255, 255, 255,
                false, true, false, false, false, false, List.of(overlay));
        WorldMeshBuilder.MeshData isolated = new WorldMeshBuilder(registry)
                .build(new WorldState.Chunk(0, 0, 1, 1, List.of(source)));
        assertEquals(0, isolated.coverage().contextualRoofObjects());
        assertEquals(1, isolated.coverage().unsupportedObjects());

        WorldState.TileObject neighbour = new WorldState.TileObject(
                0, "IsoObject", "WestRoofT", "roofs_colour_0",
                false, false, false, false, false, false, false);
        WorldState.Square south = new WorldState.Square(
                0, 1, 1, -1, 0, 255, 255, 255,
                false, true, false, false, false, false, List.of(neighbour));
        WorldMeshBuilder.MeshData joined = new WorldMeshBuilder(registry)
                .build(new WorldState.Chunk(0, 0, 1, 1, List.of(source, south)));
        assertEquals(1, joined.coverage().contextualRoofObjects());
        assertEquals(1, joined.coverage().authoredGeometryObjects());
        assertEquals(3, joined.texturedBatches().stream()
                .filter(batch -> batch.sprite().equals("roofs_overlay_0"))
                .findFirst().orElseThrow().vertexCount());

        WorldState.Square boundarySource = new WorldState.Square(
                0, 7, 1, -1, 0, 255, 255, 255,
                false, true, false, false, false, false, List.of(overlay));
        WorldState.Chunk northChunk = new WorldState.Chunk(
                4, 7, 2, 2, List.of(boundarySource));
        WorldState.Square boundaryTarget = new WorldState.Square(
                0, 0, 1, -1, 0, 255, 255, 255,
                false, true, false, false, false, false, List.of(neighbour));
        WorldState.Chunk southChunk = new WorldState.Chunk(
                4, 8, 3, 3, List.of(boundaryTarget));
        WorldMeshBuilder.MeshData crossChunk = new WorldMeshBuilder(registry)
                .build(northChunk, List.of(northChunk, southChunk));
        assertEquals(1, crossChunk.coverage().contextualRoofObjects());
        WorldMeshBuilder.MeshData blockedCrossChunk = new WorldMeshBuilder(registry)
                .build(northChunk, List.of(northChunk));
        assertEquals(0, blockedCrossChunk.coverage().contextualRoofObjects());
        assertNotEquals(blockedCrossChunk.fingerprint(), crossChunk.fingerprint(),
                "a changed context decision must invalidate the GPU mesh cache");

        HashMap<Long, WorldState.Chunk> snapshots = new HashMap<>();
        WorldState.Chunk eastChunk = new WorldState.Chunk(5, 7, 4, 4, List.of());
        WorldState.Chunk diagonalChunk = new WorldState.Chunk(5, 8, 5, 5, List.of());
        snapshots.put(northChunk.key(), northChunk);
        snapshots.put(eastChunk.key(), eastChunk);
        snapshots.put(southChunk.key(), southChunk);
        snapshots.put(diagonalChunk.key(), diagonalChunk);
        assertEquals(List.of(northChunk, eastChunk, southChunk),
                InProcessWorldRenderer.roofContext(northChunk, snapshots));
        assertFalse(InProcessWorldRenderer.roofContextEdgeChanged(
                null, northChunk, false));
        assertTrue(InProcessWorldRenderer.roofContextEdgeChanged(null, southChunk, false));
    }

    /** Opt-in parser/load audit for the local proprietary-derived registry. */
    @Test
    void installedCompiledRoofRegistryWhenExplicitlyProvided() throws Exception {
        String path = System.getenv("PZFPS_ROOF_SURFACE_AUDIT");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                path != null, "Installed-data audit requires PZFPS_ROOF_SURFACE_AUDIT");
        Path registryPath = Path.of(path);
        JSONObject document = new JSONObject(Files.readString(registryPath));
        TileGeometryRegistry registry = TileGeometryRegistry.load(registryPath);
        assertEquals(document.getInt("tile_count"), registry.tileCount());
        assertFalse(registry.geometry("roofs_02_3").isEmpty());
        assertFalse(registry.geometry("roofs_accents_01_4").isEmpty());
        assertFalse(registry.geometry("roofs_30_02_28").isEmpty());
        assertFalse(registry.geometry("walls_exterior_roofs_10_5").isEmpty());
        assertFalse(registry.geometry("walls_exterior_roofs_30_21_16").isEmpty());
    }
}
