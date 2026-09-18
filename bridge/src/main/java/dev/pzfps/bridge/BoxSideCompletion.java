package dev.pzfps.bridge;

/** Evidence-backed family routing, not a guess that every box has interchangeable faces. */
final class BoxSideCompletion {
    private BoxSideCompletion() {}

    static boolean closedCrate(String sprite) {
        // Installed B42 Tiles2x17: whole cuboids with repeated wooden panels,
        // not stacked fragment sprites 20/21 or the table/chair families.
        return "carpentry_01_16".equals(sprite) || "carpentry_01_19".equals(sprite);
    }

    static float[] projectedBounds(float[] vertices) {
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < vertices.length; i += WorldMeshBuilder.TEXTURED_FLOATS_PER_VERTEX) {
            minX = Math.min(minX, vertices[i + 9]);
            minY = Math.min(minY, vertices[i + 10]);
            maxX = Math.max(maxX, vertices[i + 9]);
            maxY = Math.max(maxY, vertices[i + 10]);
        }
        if (!Float.isFinite(minX) || maxX <= minX || maxY <= minY)
            throw new IllegalArgumentException("Empty/degenerate source projection");
        return new float[] {minX, minY, maxX - minX, maxY - minY};
    }

    static int sideAxis(String sprite, String facing, TileGeometryRegistry.Primitive box) {
        // A game's Facing property identifies the front without guessing from pixel colour
        // or tile numbers. Only copy faces perpendicular to that front: side -> side.
        float fx = facing.equals("E") ? 1 : facing.equals("W") ? -1 : 0;
        float fz = facing.equals("S") ? 1 : facing.equals("N") ? -1 : 0;
        if (fx != 0 || fz != 0) {
            float[] center = WorldMeshBuilder.transformLocal(box, 0, 0, 0);
            for (int axis : new int[] {0, 2}) {
                float[] p = WorldMeshBuilder.transformLocal(box, axis == 0 ? 1 : 0, 0, axis == 2 ? 1 : 0);
                float x = p[0] - center[0], y = p[1] - center[1], z = p[2] - center[2];
                if (Math.abs(y) < .01f && Math.abs(x * fx + z * fz) < .01f) return axis;
            }
            return -1;
        }
        return mirrorLocalX(sprite) ? 0 : -1;
    }

    static boolean mirrorLocalX(String sprite) {
        // B42 tool-cabinet family: inspected four source views on Tiles2x100 and the
        // matching authored box. Local X is its left/right axis even for the rotated
        // variants. Drawers belong to local +/-Z and must never be copied to the back.
        // This completes only the unseen opposite side; it does not invent a back/bottom.
        return switch (sprite) {
            case "location_business_machinery_01_32", "location_business_machinery_01_33",
                    "location_business_machinery_01_34", "location_business_machinery_01_35",
                    // Installed bedroom dresser pair. Their authored multi-box model
                    // supplies one local-X side; copying only that side closes the
                    // opposite end without putting drawer/front artwork on the back.
                    "furniture_storage_02_36", "furniture_storage_02_37" -> true;
            default -> false;
        };
    }
}
