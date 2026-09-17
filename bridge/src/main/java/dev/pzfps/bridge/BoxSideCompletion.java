package dev.pzfps.bridge;

/** Evidence-backed family routing, not a guess that every box has interchangeable faces. */
final class BoxSideCompletion {
    private BoxSideCompletion() {}

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
                    "location_business_machinery_01_34", "location_business_machinery_01_35" -> true;
            default -> false;
        };
    }
}
