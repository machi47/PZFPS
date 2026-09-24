package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

final class WallAttachmentAssemblyTest {
    private static final int STRIDE = WorldMeshBuilder.TEXTURED_FLOATS_PER_VERTEX;

    private static float[] vertices(float[][] positions) {
        float[] result = new float[positions.length * STRIDE];
        for (int i = 0; i < positions.length; i++)
            System.arraycopy(positions[i], 0, result, i * STRIDE, 3);
        return result;
    }

    @Test
    void sourceSurfaceAnchorsToEachDeclaredWallAndPreservesShape() {
        for (int edge : new int[] {StructuralPropClip.NORTH, StructuralPropClip.WEST,
                StructuralPropClip.EAST, StructuralPropClip.SOUTH}) {
            float[] values = vertices(new float[][] {{4.2f,1,7.3f}, {4.5f,2,7.6f}, {4.4f,1.5f,7.5f}});
            float beforeDx = values[STRIDE] - values[0];
            float beforeDz = values[STRIDE + 2] - values[2];
            assertTrue(WallAttachmentAssembly.align(
                    values, 0, values.length, STRIDE, edge, edge, 4, 7));
            float extreme = edge == StructuralPropClip.WEST || edge == StructuralPropClip.NORTH
                    ? Float.POSITIVE_INFINITY : Float.NEGATIVE_INFINITY;
            int axis = edge == StructuralPropClip.WEST || edge == StructuralPropClip.EAST ? 0 : 2;
            for (int index = axis; index < values.length; index += STRIDE) {
                extreme = edge == StructuralPropClip.WEST || edge == StructuralPropClip.NORTH
                        ? Math.min(extreme, values[index]) : Math.max(extreme, values[index]);
            }
            float expected = switch (edge) {
                case StructuralPropClip.WEST -> 4 + WallAttachmentAssembly.CLEARANCE;
                case StructuralPropClip.NORTH -> 7 + WallAttachmentAssembly.CLEARANCE;
                case StructuralPropClip.EAST -> 5 - WallAttachmentAssembly.CLEARANCE;
                default -> 8 - WallAttachmentAssembly.CLEARANCE;
            };
            assertEquals(expected, extreme, .00001f);
            assertEquals(beforeDx, values[STRIDE] - values[0], .00001f);
            assertEquals(beforeDz, values[STRIDE + 2] - values[2], .00001f);
        }
    }

    @Test
    void requiresExactDeclaredEdgeToBeAnAuthoritativeSealedWall() {
        float[] values = vertices(new float[][] {{.2f,1,.3f}, {.4f,2,.6f}, {.3f,1.5f,.5f}});
        float[] original = values.clone();
        assertFalse(WallAttachmentAssembly.align(values, 0, values.length, STRIDE,
                0, StructuralPropClip.WEST, 0, 0));
        assertArrayEquals(original, values);
        assertFalse(WallAttachmentAssembly.align(values, 0, values.length, STRIDE,
                StructuralPropClip.WEST, StructuralPropClip.NORTH, 0, 0));
        assertArrayEquals(original, values);
    }
}
