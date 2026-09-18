package dev.pzfps.bridge;

import java.util.ArrayList;
import java.util.List;

/** Removes sprite-depth support geometry that crosses a verified opaque wall.
 * This changes presentation only. No game object, collision or action is moved.
 * A wall constrains only its own one-square span and storey, not an infinite
 * plane: multi-tile furniture may continue past an adjacent open boundary. */
final class StructuralPropClip {
    static final int NORTH = 1, WEST = 2, EAST = 4, SOUTH = 8;
    static final float CLEARANCE = .001f;

    private StructuralPropClip() {}

    static boolean freestanding(WorldState.TileObject object) {
        if (object.door() || object.window() || object.edgeNorth() || object.edgeWest()) return false;
        String sprite = object.sprite();
        // Do not treat wall-mounted fixtures, fences, roofs or arbitrary mod art as
        // furniture just because their source happens to contain a box primitive.
        return sprite.startsWith("furniture_") || sprite.startsWith("appliances_")
                || BoxSideCompletion.closedCrate(sprite);
    }

    static float[] clip(float[] vertices, float x, float y, float z, int edges) {
        if (edges == 0 || vertices.length == 0) return vertices;
        int stride = WorldMeshBuilder.TEXTURED_FLOATS_PER_VERTEX;
        if (vertices.length % (3 * stride) != 0) throw new IllegalArgumentException("incomplete triangles");
        List<float[]> triangles = new ArrayList<>();
        for (int i = 0; i < vertices.length; i += stride)
            triangles.add(java.util.Arrays.copyOfRange(vertices, i, i + stride));
        for (int edge : new int[] {NORTH, WEST, EAST, SOUTH}) {
            if ((edges & edge) == 0) continue;
            boolean alongX = edge == NORTH || edge == SOUTH;
            int axis = alongX ? 2 : 0, tangent = alongX ? 0 : 2;
            float origin = alongX ? z : x, span = alongX ? x : z;
            boolean negative = edge == NORTH || edge == WEST;
            // Inside all five half-spaces is the forbidden volume beyond this
            // wall segment. Coordinates are chunk-local, retaining fine clearance.
            float[][] planes = {
                {tangent, 1, span}, {tangent, -1, -(span + 1)},
                {1, 1, y}, {1, -1, -(y + 3)},
                {axis, negative ? -1 : 1, negative ? -(origin + CLEARANCE) : origin + 1 - CLEARANCE}
            };
            List<float[]> result = new ArrayList<>();
            for (int i = 0; i < triangles.size(); i += 3) {
                List<float[]> remaining = List.of(triangles.get(i), triangles.get(i + 1), triangles.get(i + 2));
                boolean outside = false;
                for (float[] plane : planes) {
                    if (distance(remaining.get(0), plane) <= 0
                            && distance(remaining.get(1), plane) <= 0
                            && distance(remaining.get(2), plane) <= 0) {
                        outside = true;
                        break;
                    }
                }
                if (outside) {
                    result.addAll(remaining);
                    continue;
                }
                for (float[] plane : planes) {
                    emit(result, half(remaining, plane, false));
                    remaining = half(remaining, plane, true);
                    if (remaining.size() < 3) break;
                }
                // Remaining interior is behind the opaque wall and is discarded.
            }
            triangles = result;
        }
        float[] result = new float[triangles.size() * stride];
        for (int i = 0; i < triangles.size(); i++)
            System.arraycopy(triangles.get(i), 0, result, i * stride, stride);
        return result;
    }

    private static List<float[]> half(List<float[]> polygon, float[] plane, boolean inside) {
        if (polygon.isEmpty()) return List.of();
        List<float[]> result = new ArrayList<>();
        float[] a = polygon.getLast();
        float da = distance(a, plane);
        boolean keepA = inside ? da >= 0 : da < 0;
        for (float[] b : polygon) {
            float db = distance(b, plane);
            boolean keepB = inside ? db >= 0 : db < 0;
            if (keepA != keepB) {
                float t = da / (da - db);
                float[] intersection = new float[a.length];
                for (int j = 0; j < a.length; j++) intersection[j] = a[j] + t * (b[j] - a[j]);
                // Exact shared cut coordinate, not accumulated roundoff at seams.
                intersection[(int) plane[0]] = plane[2] / plane[1];
                result.add(intersection);
            }
            if (keepB) result.add(b);
            a = b;
            da = db;
            keepA = keepB;
        }
        return result;
    }

    private static float distance(float[] vertex, float[] plane) {
        return vertex[(int) plane[0]] * plane[1] - plane[2];
    }

    private static void emit(List<float[]> triangles, List<float[]> polygon) {
        for (int i = 1; i + 1 < polygon.size(); i++) {
            float[] a = polygon.getFirst(), b = polygon.get(i), c = polygon.get(i + 1);
            double ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
            double vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
            double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            if (nx * nx + ny * ny + nz * nz < 1e-18) continue;
            triangles.add(a); triangles.add(b); triangles.add(c);
        }
    }
}
