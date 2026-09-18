package dev.pzfps.bridge;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Removes sprite-depth support geometry that crosses a verified opaque wall.
 * This changes presentation only. No game object, collision or action is moved.
 * A wall constrains only its own one-square span and storey, not an infinite
 * plane: multi-tile furniture may continue past an adjacent open boundary. */
final class StructuralPropClip {
    static final int NORTH = 1, WEST = 2, EAST = 4, SOUTH = 8;
    static final float CLEARANCE = .001f;

    /** Canonical finite wall segment in chunk-local coordinates. Axis 0 is an
     * X-normal wall; axis 2 is a Z-normal wall. */
    record Boundary(int axis, int coordinate, int spanStart, int level) {}

    private StructuralPropClip() {}

    static boolean constrainedByWalls(WorldState.TileObject object) {
        if (object.door() || object.window() || object.edgeNorth() || object.edgeWest()) return false;
        String sprite = object.sprite().toLowerCase(Locale.ROOT);
        String type = object.objectType().toLowerCase(Locale.ROOT);
        // Boundary assemblies own their placement and must not be cut by themselves.
        // Everything else with authored volume is a physical scene object, including
        // fixtures and wall attachments: source draw order is not permission to cross
        // an opaque wall or appear on its reverse side.
        return !type.contains("wall")
                && !sprite.startsWith("walls_")
                && !sprite.startsWith("wall_")
                && !sprite.startsWith("roofs_")
                && !sprite.startsWith("roofing_")
                && !sprite.startsWith("fencing_")
                && !sprite.startsWith("fixtures_doors_fences_");
    }

    static List<Boundary> boundaries(List<WorldState.Square> squares) {
        Set<Boundary> result = new LinkedHashSet<>();
        for (WorldState.Square square : squares) {
            int edges = square.sealedEdges();
            if ((edges & NORTH) != 0)
                result.add(new Boundary(2, square.localY(), square.localX(), square.z()));
            if ((edges & SOUTH) != 0)
                result.add(new Boundary(2, square.localY() + 1, square.localX(), square.z()));
            if ((edges & WEST) != 0)
                result.add(new Boundary(0, square.localX(), square.localY(), square.z()));
            if ((edges & EAST) != 0)
                result.add(new Boundary(0, square.localX() + 1, square.localY(), square.z()));
        }
        return List.copyOf(result);
    }

    static float[] clip(
            float[] vertices,
            float ownerX,
            float ownerY,
            float ownerZ,
            List<Boundary> boundaries) {
        float[] result = vertices;
        for (Boundary boundary : boundaries) {
            float wallY = boundary.level() * 3.0f;
            float owner = boundary.axis() == 0 ? ownerX + .5f : ownerZ + .5f;
            boolean keepLess = owner < boundary.coordinate();
            // A finite wall segment only owns one tile span and one storey. This
            // inexpensive rejection also keeps distant chunk walls out of the clipper.
            if (!intersects(result, boundary.axis(), boundary.coordinate(), boundary.spanStart(), wallY, keepLess))
                continue;
            result = clipBoundary(
                    result,
                    boundary.axis(),
                    boundary.coordinate(),
                    boundary.spanStart(),
                    wallY,
                    keepLess);
            if (result.length == 0) break;
        }
        return result;
    }

    static float[] clip(float[] vertices, float x, float y, float z, int edges) {
        if (edges == 0 || vertices.length == 0) return vertices;
        List<Boundary> boundaries = new ArrayList<>(4);
        int level = Math.round(y / 3.0f);
        if ((edges & NORTH) != 0) boundaries.add(new Boundary(2, Math.round(z), Math.round(x), level));
        if ((edges & SOUTH) != 0) boundaries.add(new Boundary(2, Math.round(z + 1), Math.round(x), level));
        if ((edges & WEST) != 0) boundaries.add(new Boundary(0, Math.round(x), Math.round(z), level));
        if ((edges & EAST) != 0) boundaries.add(new Boundary(0, Math.round(x + 1), Math.round(z), level));
        return clip(vertices, x, y, z, boundaries);
    }

    private static boolean intersects(
            float[] vertices, int axis, float coordinate, float spanStart, float wallY,
            boolean keepLess) {
        if (vertices.length == 0) return false;
        int tangent = axis == 0 ? 2 : 0;
        float minAxis = Float.POSITIVE_INFINITY, maxAxis = Float.NEGATIVE_INFINITY;
        float minTangent = Float.POSITIVE_INFINITY, maxTangent = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        int stride = WorldMeshBuilder.TEXTURED_FLOATS_PER_VERTEX;
        for (int i = 0; i < vertices.length; i += stride) {
            minAxis = Math.min(minAxis, vertices[i + axis]);
            maxAxis = Math.max(maxAxis, vertices[i + axis]);
            minTangent = Math.min(minTangent, vertices[i + tangent]);
            maxTangent = Math.max(maxTangent, vertices[i + tangent]);
            minY = Math.min(minY, vertices[i + 1]);
            maxY = Math.max(maxY, vertices[i + 1]);
        }
        boolean reachesForbiddenSide = keepLess
                ? maxAxis >= coordinate - CLEARANCE
                : minAxis <= coordinate + CLEARANCE;
        return reachesForbiddenSide
                && minTangent < spanStart + 1 && maxTangent > spanStart
                && minY < wallY + 3 && maxY > wallY;
    }

    private static float[] clipBoundary(
            float[] vertices,
            int axis,
            float coordinate,
            float span,
            float wallY,
            boolean keepLess) {
        int stride = WorldMeshBuilder.TEXTURED_FLOATS_PER_VERTEX;
        if (vertices.length % (3 * stride) != 0) throw new IllegalArgumentException("incomplete triangles");
        List<float[]> triangles = new ArrayList<>();
        for (int i = 0; i < vertices.length; i += stride)
            triangles.add(java.util.Arrays.copyOfRange(vertices, i, i + stride));
        int tangent = axis == 0 ? 2 : 0;
        // Inside all five half-spaces is the forbidden volume beyond this
        // wall segment. Coordinates are chunk-local, retaining fine clearance.
        float[][] planes = {
            {tangent, 1, span}, {tangent, -1, -(span + 1)},
            {1, 1, wallY}, {1, -1, -(wallY + 3)},
            {axis, keepLess ? 1 : -1,
                keepLess ? coordinate - CLEARANCE : -(coordinate + CLEARANCE)}
        };
        List<float[]> clipped = new ArrayList<>();
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
                clipped.addAll(remaining);
                continue;
            }
            for (float[] plane : planes) {
                emit(clipped, half(remaining, plane, false));
                remaining = half(remaining, plane, true);
                if (remaining.size() < 3) break;
            }
            // Remaining interior is behind the opaque wall and is discarded.
        }
        triangles = clipped;
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
