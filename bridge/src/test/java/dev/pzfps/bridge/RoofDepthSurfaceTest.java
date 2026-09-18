package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

    /** Opt-in parser/load audit for the local proprietary-derived registry. */
    @Test
    void installedCompiledRoofRegistryWhenExplicitlyProvided() throws Exception {
        String path = System.getenv("PZFPS_ROOF_SURFACE_AUDIT");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                path != null, "Installed-data audit requires PZFPS_ROOF_SURFACE_AUDIT");
        TileGeometryRegistry registry = TileGeometryRegistry.load(Path.of(path));
        assertEquals(3998, registry.tileCount());
        assertFalse(registry.geometry("roofs_02_3").isEmpty());
        assertFalse(registry.geometry("roofs_accents_01_4").isEmpty());
        assertFalse(registry.geometry("walls_exterior_roofs_10_5").isEmpty());
    }
}
