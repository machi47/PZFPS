package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.FloatBuffer;
import org.lwjgl.util.vector.Matrix4f;
import org.junit.jupiter.api.Test;
import zombie.core.rendering.ShaderPropertyBlock;

final class FirstPersonBodyMaskTest {
    @Test
    void collapsesOnlyTheHeadBoneInTheRetainedRenderPalette() {
        FloatBuffer palette = FloatBuffer.allocate(32);
        for (int index = 0; index < palette.capacity(); index++) {
            palette.put(index, index + 1.0f);
        }
        int originalPosition = palette.position();
        float[] otherBone = new float[16];
        for (int index = 0; index < otherBone.length; index++) {
            otherBone[index] = palette.get(index);
        }

        assertTrue(FirstPersonBodyMask.collapsePaletteEntry(palette, 1));

        for (int index = 0; index < otherBone.length; index++) {
            assertEquals(otherBone[index], palette.get(index));
        }
        for (int index = 16; index < 28; index++) assertEquals(0.0f, palette.get(index));
        assertEquals(0.0f, palette.get(28));
        assertEquals(-64.0f, palette.get(29));
        assertEquals(0.0f, palette.get(30));
        assertEquals(1.0f, palette.get(31));
        assertEquals(originalPosition, palette.position());
    }

    @Test
    void leavesPaletteAloneWhenHeadIsOutsideTheCapturedSkeleton() {
        FloatBuffer palette = FloatBuffer.allocate(16);
        assertFalse(FirstPersonBodyMask.collapsePaletteEntry(palette, 1));
    }

    @Test
    void synchronizesThePaletteUsedByPzsInstancedCharacterDraw() {
        FloatBuffer palette = FloatBuffer.allocate(32);
        for (int index = 0; index < palette.capacity(); index++) {
            palette.put(index, index + 1.0f);
        }
        ShaderPropertyBlock properties = new ShaderPropertyBlock();
        properties.SetMatrix4Array("MatrixPalette", palette);

        assertTrue(FirstPersonBodyMask.collapsePaletteEntry(palette, 1, properties));

        Matrix4f[] shaderPalette = properties.GetParameter("MatrixPalette").GetMatrix4Array();
        FloatBuffer copiedHead = FloatBuffer.allocate(16);
        shaderPalette[1].store(copiedHead);
        assertEquals(0.0f, copiedHead.get(0));
        assertEquals(0.0f, copiedHead.get(11));
        assertEquals(0.0f, copiedHead.get(12));
        assertEquals(-64.0f, copiedHead.get(13));
        assertEquals(0.0f, copiedHead.get(14));
        assertEquals(1.0f, copiedHead.get(15));

        FloatBuffer copiedBody = FloatBuffer.allocate(16);
        shaderPalette[0].store(copiedBody);
        for (int index = 0; index < 16; index++) {
            assertEquals(index + 1.0f, copiedBody.get(index));
        }
    }
}
