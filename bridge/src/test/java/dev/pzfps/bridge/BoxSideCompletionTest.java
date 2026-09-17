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
