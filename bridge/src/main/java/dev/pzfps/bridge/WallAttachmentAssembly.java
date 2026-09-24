package dev.pzfps.bridge;

/** Anchors source-derived wall-object surfaces to their PZ-declared physical boundary. */
final class WallAttachmentAssembly {
    static final float CLEARANCE = .002f;

    private WallAttachmentAssembly() {}

    /**
     * Translate only newly emitted position components. Texture coordinates stay tied to the
     * installed source projection, so the object keeps its exact sprite identity and silhouette.
     */
    static boolean align(
            float[] vertices,
            int start,
            int end,
            int stride,
            int declaredEdge,
            int sealedEdges,
            float baseX,
            float baseZ) {
        if (declaredEdge == 0 || (sealedEdges & declaredEdge) == 0 || end <= start) return false;
        int axis = declaredEdge == StructuralPropClip.WEST
                        || declaredEdge == StructuralPropClip.EAST ? 0 : 2;
        float extreme = declaredEdge == StructuralPropClip.WEST
                        || declaredEdge == StructuralPropClip.NORTH
                ? Float.POSITIVE_INFINITY : Float.NEGATIVE_INFINITY;
        for (int index = start + axis; index < end; index += stride) {
            extreme = declaredEdge == StructuralPropClip.WEST
                            || declaredEdge == StructuralPropClip.NORTH
                    ? Math.min(extreme, vertices[index]) : Math.max(extreme, vertices[index]);
        }
        float boundary = switch (declaredEdge) {
            case StructuralPropClip.WEST -> baseX + CLEARANCE;
            case StructuralPropClip.NORTH -> baseZ + CLEARANCE;
            case StructuralPropClip.EAST -> baseX + 1.0f - CLEARANCE;
            case StructuralPropClip.SOUTH -> baseZ + 1.0f - CLEARANCE;
            default -> throw new IllegalArgumentException("unknown wall attachment edge " + declaredEdge);
        };
        float delta = boundary - extreme;
        if (!Float.isFinite(delta)) return false;
        for (int index = start + axis; index < end; index += stride) vertices[index] += delta;
        return true;
    }
}
