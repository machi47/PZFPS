package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class StructuralPropClipTest {
    private static final int STRIDE = WorldMeshBuilder.TEXTURED_FLOATS_PER_VERTEX;

    private static float[] triangle(float[] a, float[] b, float[] c) {
        float[] result = new float[3 * STRIDE];
        float[][] points = {a, b, c};
        for (int i = 0; i < 3; i++) {
            System.arraycopy(points[i], 0, result, i * STRIDE, 3);
            result[i * STRIDE + 4] = 1;
            result[i * STRIDE + 6] = .7f;
            result[i * STRIDE + 9] = 64 + 64 * (points[i][0] - points[i][2]);
            result[i * STRIDE + 10] = 224 + 32 * (points[i][0] + points[i][2]) - 64 * points[i][1];
            result[i * STRIDE + 12] = 31;
        }
        return result;
    }

    private static float[] clip(float[] source, int edge) {
        return StructuralPropClip.clip(source, 0, 0, 0, edge);
    }

    @Test
    void allFourSolidBoundariesRemoveOverhangWithoutMovingRetainedSurfaceOrUv() {
        for (int edge : new int[] {1, 2, 4, 8}) {
            boolean alongX = edge == 1 || edge == 8;
            int axis = alongX ? 2 : 0, tangent = alongX ? 0 : 2;
            boolean negative = edge == 1 || edge == 2;
            float[] a = {.2f, 1, .2f}, b = {.8f, 1, .8f}, c = {.5f, 1, .5f};
            a[axis] = negative ? -.03f : 1.03f;
            b[axis] = a[axis];
            c[axis] = .5f;
            a[tangent] = .2f; b[tangent] = .8f; c[tangent] = .5f;
            float[] result = clip(triangle(a, b, c), edge);
            assertTrue(result.length > 0);
            for (int i = 0; i < result.length; i += STRIDE) {
                assertTrue(negative ? result[i + axis] >= .001f : result[i + axis] <= .999f);
                assertEquals(64 + 64 * (result[i] - result[i + 2]), result[i + 9], .0001);
                assertEquals(224 + 32 * (result[i] + result[i + 2]) - 64 * result[i + 1], result[i + 10], .0001);
                assertEquals(31, result[i + 12]);
                assertEquals(.7f, result[i + 6]);
                assertEquals(0, result[i + 11]);
            }
        }
    }

    @Test
    void openingsAndUnknownBoundariesDoNotClipAnything() {
        float[] source = triangle(new float[] {-.1f,1,-.1f}, new float[] {1.1f,1,-.1f}, new float[] {.5f,1,1.1f});
        assertSame(source, clip(source, 0));
    }

    @Test
    void segmentEndsAndStoreyLimitsDoNotCutNeighboringOpenSpace() {
        for (float[] offsets : new float[][] {{1.1f,0}, {-1.1f,0}, {0,3.1f}, {0,-3.1f}}) {
            float[] source = triangle(new float[] {.1f+offsets[0],1+offsets[1],-.1f},
                    new float[] {.8f+offsets[0],1+offsets[1],-.1f},
                    new float[] {.4f+offsets[0],2+offsets[1],-.1f});
            assertArrayEquals(source, clip(source, StructuralPropClip.NORTH));
        }
    }

    @Test
    void crossingSegmentEndSplitsOnlyTheOccludedPortion() {
        float[] result = clip(triangle(new float[] {.5f,1,-.1f}, new float[] {1.5f,1,-.1f},
                new float[] {1.5f,2,-.1f}), StructuralPropClip.NORTH);
        assertTrue(result.length > 0);
        for (int i = 0; i < result.length; i += STRIDE) assertTrue(result[i] >= 1);
    }

    @Test
    void completelyBehindWallAndCoplanarTrianglesAreRemoved() {
        float[] source = triangle(new float[] {.1f,1,0}, new float[] {.8f,1,0}, new float[] {.5f,2,0});
        assertEquals(0, clip(source, StructuralPropClip.NORTH).length);
    }

    @Test
    void clipIsIndependentOfCameraAndStableOnSecondApplication() {
        float[] source = triangle(new float[] {-.1f,1,-.1f}, new float[] {.8f,1,-.1f}, new float[] {.5f,2,.8f});
        float[] once = clip(source, 15), twice = clip(once, 15);
        assertArrayEquals(once, twice);
    }

    @Test
    void builderConstrainsPropsButLeavesAttachmentLayersAndUnblockedMultiTileParts(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("geometry.json");
        String geometry = "{\"geometry\":[{\"kind\":\"box\",\"min\":[-0.51,0,-0.51],\"max\":[0.6,1,0.6]}]}";
        Files.writeString(file, "{\"schema_version\":1,\"tiles\":{\"furniture_fixture_0\":" + geometry
                + ",\"lighting_fixture_0\":" + geometry + "}}");
        var prop = object("furniture_fixture_0");
        var attachment = object("lighting_fixture_0");
        var square = new WorldState.Square(0,0,0,1,0,255,255,255,
                false,false,false,false,false,false,List.of(prop, attachment), StructuralPropClip.NORTH);
        var mesh = new WorldMeshBuilder(TileGeometryRegistry.load(file))
                .build(new WorldState.Chunk(0,0,1,1,List.of(square)));
        for (var batch : mesh.texturedBatches()) {
            float[] values = batch.vertices();
            assertTrue(values.length > 0);
            for (int i = 0; i < values.length; i += STRIDE) {
                assertEquals(batch.sprite().startsWith("furniture") ? 0 : 4, values[i+11]);
                if (batch.sprite().startsWith("furniture") && values[i] > 0 && values[i] < 1)
                    assertTrue(values[i+2] >= .001f);
            }
        }
    }

    private static WorldState.TileObject object(String sprite) {
        return new WorldState.TileObject(3,"IsoObject","MAX",sprite,false,false,false,false,false,false,false);
    }
}
