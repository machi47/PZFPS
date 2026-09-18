package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BoxSideCompletionTest {
    @TempDir Path temporary;

    @Test
    void onlyVerifiedCabinetFamilyGetsSideCompletion() {
        assertTrue(BoxSideCompletion.mirrorLocalX("location_business_machinery_01_32"));
        assertTrue(BoxSideCompletion.mirrorLocalX("location_business_machinery_01_35"));
        assertFalse(BoxSideCompletion.mirrorLocalX("location_business_machinery_01_36"));
        assertFalse(BoxSideCompletion.mirrorLocalX("fixtures_counters_01_0"));
    }

    @Test
    void onlyInspectedBedroomDresserPairGetsStorageSideCompletion() {
        assertTrue(BoxSideCompletion.mirrorLocalX("furniture_storage_02_36"));
        assertTrue(BoxSideCompletion.mirrorLocalX("furniture_storage_02_37"));
        assertFalse(BoxSideCompletion.mirrorLocalX("furniture_storage_01_12"));
        assertFalse(BoxSideCompletion.mirrorLocalX("furniture_bedding_01_52"));
    }

    @Test
    void nativeFacingSelectsSidesWithoutGuessingUnrelatedAssetNames() {
        var box = new TileGeometryRegistry.Primitive("box", 0, 0, 0, 0, 0, 0,
                -.5f, 0, -.25f, .5f, 1, .25f, 0, 0, 0, "", List.of());
        assertEquals(0, BoxSideCompletion.sideAxis("unseen_asset", "N", box));
        assertEquals(0, BoxSideCompletion.sideAxis("unseen_asset", "S", box));
        assertEquals(2, BoxSideCompletion.sideAxis("unseen_asset", "W", box));
        assertEquals(2, BoxSideCompletion.sideAxis("unseen_asset", "E", box));
        assertEquals(-1, BoxSideCompletion.sideAxis("unseen_asset", "", box));
        var rotated = new TileGeometryRegistry.Primitive("box", 0, 0, 0, 0, -90, 0,
                -.5f, 0, -.25f, .5f, 1, .25f, 0, 0, 0, "", List.of());
        assertEquals(2, BoxSideCompletion.sideAxis("unseen_asset", "N", rotated));
        assertEquals(0, BoxSideCompletion.sideAxis("unseen_asset", "W", rotated));
    }

    @Test
    void closedCratesHaveSixUniqueFacesWithNoGeometryExpansion() throws Exception {
        assertTrue(BoxSideCompletion.closedCrate("carpentry_01_16"));
        assertTrue(BoxSideCompletion.closedCrate("carpentry_01_19"));
        assertFalse(BoxSideCompletion.closedCrate("carpentry_01_20"));
        assertFalse(BoxSideCompletion.closedCrate("location_business_machinery_01_32"));
        for (String sprite : List.of("carpentry_01_16", "carpentry_01_19")) {
            Path path = temporary.resolve(sprite + ".json");
            Files.writeString(path, """
                    {"schema_version":1,"source_sha256":"fixture","tiles":{"%s":{
                      "geometry":[{"kind":"box","min":[-0.5,0,-0.5],"max":[0.5,0.8,0.5],
                       "translate":[0,0,0],"rotate_degrees":[0,0,0]}]}}}
                    """.formatted(sprite));
            var object = new WorldState.TileObject(0, "IsoObject", "normal", sprite,
                    false, false, false, false, false, false, false);
            var square = new WorldState.Square(0, 0, 0, -1, 0, 255, 255, 255,
                    false, true, false, false, false, false, List.of(object));
            var batch = new WorldMeshBuilder(TileGeometryRegistry.load(path))
                    .build(new WorldState.Chunk(0, 0, 1, 1, List.of(square))).texturedBatches().getFirst();
            assertEquals(36, batch.vertexCount(), "Exactly six faces, not coplanar reverse duplicates");
            int stride = WorldMeshBuilder.TEXTURED_FLOATS_PER_VERTEX;
            var normalCounts = new java.util.HashMap<String, Integer>();
            float[] v = batch.vertices();
            for (int i = 0; i < v.length; i += stride) {
                String normal = Math.round(v[i+3]) + "," + Math.round(v[i+4]) + "," + Math.round(v[i+5]);
                normalCounts.merge(normal, 1, Integer::sum);
                assertTrue(v[i] >= 0 && v[i] <= 1);
                assertTrue(v[i+2] >= 0 && v[i+2] <= 1);
                assertTrue(v[i+1] >= 0 && v[i+1] <= .8f * WorldMeshBuilder.AUTHORED_HEIGHT_TO_WORLD);
            }
            assertEquals(6, normalCounts.size());
            for (int count : normalCounts.values()) assertEquals(6, count);
            float[] bounds = BoxSideCompletion.projectedBounds(v);
            assertEquals(128, bounds[2], .001);
            assertTrue(bounds[3] > 120 && bounds[3] < 130);
            // The completed bottom deliberately samples a vertical wood panel,
            // not the top/lid pixels: at least one donor reaches the lowest source edge.
            boolean bottomSamplesSideBase = false;
            for (int i = 0; i < v.length; i += stride)
                if (v[i+4] < -.99f && Math.abs(v[i+10] - (bounds[1] + bounds[3])) < .001)
                    bottomSamplesSideBase = true;
            assertTrue(bottomSamplesSideBase);
        }
    }

    @Test
    void addsOnlyOppositeSideAndReusesDonorSidePixelsAcrossRotations() throws Exception {
        for (int variant = 32; variant <= 35; variant++) {
            String sprite = "location_business_machinery_01_" + variant;
            float angle = variant % 2 == 0 ? 0 : -89.9802f;
            Path path = temporary.resolve("box" + variant + ".json");
            Files.writeString(path, """
                    {"schema_version":1,"source_sha256":"fixture","tiles":{"%s":{
                      "geometry":[{"kind":"box","min":[-0.448,0,-0.3014],
                       "max":[0.448,0.7558,0.3014],"translate":[0,0,0],
                       "rotate_degrees":[0,%s,0]}]}}}
                    """.formatted(sprite, angle));
            var object = new WorldState.TileObject(0, "IsoObject", "normal", sprite,
                    false, false, false, false, false, false, false);
            var square = new WorldState.Square(0, 0, 0, -1, 0, 255, 255, 255,
                    false, true, false, false, false, false, List.of(object));
            var mesh = new WorldMeshBuilder(TileGeometryRegistry.load(path))
                    .build(new WorldState.Chunk(0, 0, 1, 1, List.of(square)));
            var batch = mesh.texturedBatches().getFirst();
            assertEquals(24, batch.vertexCount(), "three observed faces plus one side only");
            float[] v = batch.vertices();
            int stride = WorldMeshBuilder.TEXTURED_FLOATS_PER_VERTEX;
            for (int i = 18 * stride; i < v.length; i += stride) {
                boolean found = false;
                for (int j = 0; j < 18 * stride; j += stride) {
                    if (Math.abs(v[i+9] - v[j+9]) < .001 && Math.abs(v[i+10] - v[j+10]) < .001
                            && v[i+3]*v[j+3] + v[i+4]*v[j+4] + v[i+5]*v[j+5] < -.99) {
                        found = true;
                    }
                }
                assertTrue(found, "new side must sample the corresponding opposite side, not the front");
            }
        }
    }
}
