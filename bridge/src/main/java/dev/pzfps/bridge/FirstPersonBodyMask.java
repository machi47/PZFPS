package dev.pzfps.bridge;

import java.nio.FloatBuffer;
import java.util.Map;
import zombie.core.rendering.ShaderPropertyBlock;
import zombie.core.skinnedmodel.model.ModelInstanceRenderData;
import zombie.core.skinnedmodel.model.SkinningData;

/** Hides only the first-person camera-occluding head in a retained PZ render snapshot. */
final class FirstPersonBodyMask {
    private static final String HEAD_BONE = "Bip01_Head";
    private static final float OFFSCREEN = -64.0f;

    private FirstPersonBodyMask() {}

    static boolean hideCameraOccludingHead(ModelInstanceRenderData data) {
        if (data == null || data.model == null || data.modelInstance == null
                || data.modelInstance.parent != null || data.matrixPalette == null) {
            return false;
        }
        SkinningData skinning = data.model.mesh == null ? null : data.model.mesh.skinningData;
        if (skinning == null && data.model.tag instanceof SkinningData tagged) {
            skinning = tagged;
        }
        if (skinning == null) return false;
        Integer headIndex = findBone(skinning.boneIndices, HEAD_BONE);
        return headIndex != null
                && collapsePaletteEntry(data.matrixPalette, headIndex, data.properties);
    }

    private static Integer findBone(Map<String, Integer> bones, String name) {
        if (bones == null) return null;
        Integer exact = bones.get(name);
        if (exact != null) return exact;
        for (Map.Entry<String, Integer> entry : bones.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        }
        return null;
    }

    /**
     * Skin transforms are a packed sequence of 4x4 matrices. A zero linear transform maps
     * every vertex weighted to the head bone to one off-screen point, degenerating its
     * triangles. This buffer belongs to ModelInstanceRenderData, not AnimationPlayer, so
     * PZ's evaluated gameplay pose remains untouched.
     */
    static boolean collapsePaletteEntry(FloatBuffer palette, int boneIndex) {
        if (palette == null || boneIndex < 0) return false;
        int base = boneIndex * 16;
        if (base > palette.limit() - 16) return false;
        for (int component = 0; component < 12; component++) {
            palette.put(base + component, 0.0f);
        }
        palette.put(base + 12, 0.0f);
        palette.put(base + 13, OFFSCREEN);
        palette.put(base + 14, 0.0f);
        palette.put(base + 15, 1.0f);
        return true;
    }

    /**
     * PZ's non-instanced shader reads the FloatBuffer directly, but its instanced path reads the
     * MatrixPalette copy in ShaderPropertyBlock. Keep both per-render-data snapshots identical;
     * changing only matrixPalette reports success while the actual batched draw still uses the
     * unmasked head pose.
     */
    static boolean collapsePaletteEntry(
            FloatBuffer palette, int boneIndex, ShaderPropertyBlock properties) {
        if (!collapsePaletteEntry(palette, boneIndex)) return false;
        if (properties != null) properties.SetMatrix4Array("MatrixPalette", palette);
        return true;
    }
}
