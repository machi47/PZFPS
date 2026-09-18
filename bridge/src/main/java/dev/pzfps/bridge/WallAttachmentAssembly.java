package dev.pzfps.bridge;

import java.util.List;
import java.util.Optional;

/** Converts PZ's isometric wall-switch support volumes into shallow wall-owned housings. */
final class WallAttachmentAssembly {
    record Placement(int edge, float width, float height, float bottom, float depth) {}

    private WallAttachmentAssembly() {}

    static boolean eligible(WorldState.TileObject object) {
        if (!object.javaType().endsWith("IsoLightSwitch")
                || !object.sprite().startsWith("lighting_indoor_01_")) return false;
        // IsoLightSwitch is PZ's controller class, not a visual family: table lamps,
        // floor lamps and exterior sconces also use it. Installed Tiles2x96 confirms
        // only the first four sprites are the two wall-switch faces/orientations.
        int suffix = spriteSuffix(object.sprite());
        return suffix >= 0 && suffix <= 3;
    }

    static Optional<Placement> placement(
            WorldState.TileObject object,
            List<TileGeometryRegistry.Primitive> geometry,
            int sealedEdges) {
        if (!eligible(object) || sealedEdges == 0) return Optional.empty();
        int edge = nearestAvailableEdge(geometry, sealedEdges);
        if (edge == 0) return Optional.empty();
        // The source registry volumes for these objects are interaction/support regions,
        // sometimes an entire 2.45-unit wall. They are not the fixture's visible volume.
        // A restrained closed housing preserves the real sprite while making placement
        // physical and wall-relative. Exact per-model meshes remain future asset work.
        // The installed crop is 13x27 pixels for both visible orientations. Preserve
        // that portrait aspect ratio instead of stretching the plate into a wide slab.
        return Optional.of(new Placement(edge, .105f, .22f, 1.08f, .018f));
    }

    private static int nearestAvailableEdge(
            List<TileGeometryRegistry.Primitive> geometry, int edges) {
        if (Integer.bitCount(edges) == 1) return edges;
        float best = Float.POSITIVE_INFINITY;
        int selected = 0;
        for (int edge : new int[] {
                StructuralPropClip.NORTH,
                StructuralPropClip.WEST,
                StructuralPropClip.EAST,
                StructuralPropClip.SOUTH}) {
            if ((edges & edge) == 0) continue;
            float distance = supportDistance(geometry, edge);
            if (distance < best) {
                best = distance;
                selected = edge;
            }
        }
        return selected;
    }

    private static float supportDistance(
            List<TileGeometryRegistry.Primitive> geometry, int edge) {
        if (geometry.isEmpty()) return 0;
        float best = Float.POSITIVE_INFINITY;
        for (TileGeometryRegistry.Primitive primitive : geometry) {
            if (!primitive.kind().equals("box")) continue;
            float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
            float minZ = Float.POSITIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
            for (float x : new float[] {primitive.minX(), primitive.maxX()}) {
                for (float y : new float[] {primitive.minY(), primitive.maxY()}) {
                    for (float z : new float[] {primitive.minZ(), primitive.maxZ()}) {
                        float[] p = WorldMeshBuilder.transformLocal(primitive, x, y, z);
                        minX = Math.min(minX, p[0] + .5f);
                        maxX = Math.max(maxX, p[0] + .5f);
                        minZ = Math.min(minZ, p[2] + .5f);
                        maxZ = Math.max(maxZ, p[2] + .5f);
                    }
                }
            }
            float extent = edge == StructuralPropClip.WEST || edge == StructuralPropClip.EAST
                    ? maxX - minX : maxZ - minZ;
            float center = edge == StructuralPropClip.WEST || edge == StructuralPropClip.EAST
                    ? (minX + maxX) * .5f : (minZ + maxZ) * .5f;
            float wall = edge == StructuralPropClip.WEST || edge == StructuralPropClip.NORTH ? 0 : 1;
            best = Math.min(best, extent + Math.abs(center - wall));
        }
        return best;
    }

    private static int spriteSuffix(String sprite) {
        int underscore = sprite.lastIndexOf('_');
        if (underscore < 0 || underscore + 1 == sprite.length()) return -1;
        try {
            return Integer.parseInt(sprite.substring(underscore + 1));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
}
