package dev.pzfps.bridge;

import java.util.List;

/** Converts immutable PZ snapshots into renderer-owned triangles without touching live objects. */
public final class WorldMeshBuilder {
    public static final int FLOATS_PER_VERTEX = 9;
    private static final float LEVEL_HEIGHT = 3.0f;
    private static final int CYLINDER_SEGMENTS = 10;
    private static final int MAX_VERTICES_PER_CHUNK = 500_000;

    public record MeshData(
            long key,
            long fingerprint,
            float[] vertices,
            int primitiveCount,
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ) {
        public int vertexCount() {
            return vertices.length / FLOATS_PER_VERTEX;
        }
    }

    private final TileGeometryRegistry registry;

    public WorldMeshBuilder(TileGeometryRegistry registry) {
        this.registry = registry;
    }

    public MeshData build(WorldState.Chunk chunk) {
        FloatBuilder output = new FloatBuilder(16_384);
        int primitiveCount = 0;
        int blockSize = zombie.iso.IsoChunkMap.CHUNK_SIZE_IN_SQUARES;
        float chunkX = chunk.worldX() * blockSize;
        float chunkZ = chunk.worldY() * blockSize;
        for (WorldState.Square square : chunk.squares()) {
            float baseX = chunkX + square.localX();
            float baseY = square.z() * LEVEL_HEIGHT;
            float baseZ = chunkZ + square.localY();
            float[] light = squareLight(square);
            if (square.solidFloor()) {
                addQuad(
                        output,
                        baseX,
                        baseY,
                        baseZ,
                        baseX,
                        baseY,
                        baseZ + 1,
                        baseX + 1,
                        baseY,
                        baseZ + 1,
                        baseX + 1,
                        baseY,
                        baseZ,
                        0,
                        1,
                        0,
                        light[0] * 0.72f,
                        light[1] * 0.78f,
                        light[2] * 0.67f);
            }
            for (WorldState.TileObject object : square.objects()) {
                float[] color = identityColor(object.sprite(), light);
                if (isStructuralPanel(object)) {
                    addFallback(output, baseX, baseY, baseZ, object, color);
                    primitiveCount++;
                }
                if (output.vertexCount() >= MAX_VERTICES_PER_CHUNK) break;
            }
            if (output.vertexCount() >= MAX_VERTICES_PER_CHUNK) break;
        }
        float[] vertices = output.toArray();
        float[] bounds = bounds(vertices);
        return new MeshData(
                chunk.key(),
                chunk.fingerprint(),
                vertices,
                primitiveCount,
                bounds[0],
                bounds[1],
                bounds[2],
                bounds[3],
                bounds[4],
                bounds[5]);
    }

    private static boolean isStructuralPanel(WorldState.TileObject object) {
        if (object.door() || object.window()) return true;
        String type = object.objectType().toLowerCase(java.util.Locale.ROOT);
        String sprite = object.sprite().toLowerCase(java.util.Locale.ROOT);
        return type.contains("wall")
                || sprite.startsWith("walls_")
                || sprite.startsWith("wall_")
                || sprite.startsWith("fencing_");
    }

    private static float[] bounds(float[] vertices) {
        if (vertices.length == 0) return new float[] {0, 0, 0, 0, 0, 0};
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;
        for (int index = 0; index < vertices.length; index += FLOATS_PER_VERTEX) {
            float x = vertices[index];
            float y = vertices[index + 1];
            float z = vertices[index + 2];
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
        return new float[] {minX, minY, minZ, maxX, maxY, maxZ};
    }

    private static void addPrimitive(
            FloatBuilder output,
            float baseX,
            float baseY,
            float baseZ,
            TileGeometryRegistry.Primitive primitive,
            float[] color) {
        switch (primitive.kind()) {
            case "box" -> addBox(
                    output,
                    baseX,
                    baseY,
                    baseZ,
                    primitive,
                    primitive.minX(),
                    primitive.minY(),
                    primitive.minZ(),
                    primitive.maxX(),
                    primitive.maxY(),
                    primitive.maxZ(),
                    color);
            case "cylinder" -> addCylinder(output, baseX, baseY, baseZ, primitive, color);
            case "polygon" -> addPolygon(output, baseX, baseY, baseZ, primitive, color);
            default -> {
                // Unknown source primitives are deliberately omitted by the registry loader.
            }
        }
    }

    private static void addFallback(
            FloatBuilder output,
            float baseX,
            float baseY,
            float baseZ,
            WorldState.TileObject object,
            float[] color) {
        String objectType = object.objectType().toLowerCase(java.util.Locale.ROOT);
        boolean panel = object.door()
                || object.window()
                || objectType.contains("wall")
                || object.sprite().startsWith("walls_")
                || object.sprite().startsWith("wall_")
                || object.sprite().startsWith("fencing_");
        if (panel) {
            float thickness = 0.06f;
            float height = object.door() ? 2.15f : object.window() ? 1.45f : 2.7f;
            boolean north = object.open() && (object.door() || object.window())
                    ? !object.north()
                    : object.north();
            if (north) {
                addBox(output, baseX, baseY, baseZ, identityPrimitive(),
                        -0.5f, 0, -thickness, 0.5f, height, thickness, color);
            } else {
                addBox(output, baseX, baseY, baseZ, identityPrimitive(),
                        -thickness, 0, -0.5f, thickness, height, 0.5f, color);
            }
        }
    }

    private static void addBox(
            FloatBuilder output,
            float baseX,
            float baseY,
            float baseZ,
            TileGeometryRegistry.Primitive transform,
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ,
            float[] color) {
        float[][] local = {
            {minX, minY, minZ}, {maxX, minY, minZ}, {maxX, maxY, minZ}, {minX, maxY, minZ},
            {minX, minY, maxZ}, {maxX, minY, maxZ}, {maxX, maxY, maxZ}, {minX, maxY, maxZ}
        };
        float[][] p = new float[8][];
        for (int index = 0; index < local.length; index++) {
            p[index] = transform(baseX, baseY, baseZ, transform, local[index][0], local[index][1], local[index][2]);
        }
        addQuad(output, p[0], p[3], p[2], p[1], normal(p[0], p[3], p[2]), color);
        addQuad(output, p[4], p[5], p[6], p[7], normal(p[4], p[5], p[6]), color);
        addQuad(output, p[0], p[4], p[7], p[3], normal(p[0], p[4], p[7]), color);
        addQuad(output, p[1], p[2], p[6], p[5], normal(p[1], p[2], p[6]), color);
        addQuad(output, p[3], p[7], p[6], p[2], normal(p[3], p[7], p[6]), color);
        addQuad(output, p[0], p[1], p[5], p[4], normal(p[0], p[1], p[5]), color);
    }

    private static void addCylinder(
            FloatBuilder output,
            float baseX,
            float baseY,
            float baseZ,
            TileGeometryRegistry.Primitive cylinder,
            float[] color) {
        for (int segment = 0; segment < CYLINDER_SEGMENTS; segment++) {
            double a0 = Math.PI * 2.0 * segment / CYLINDER_SEGMENTS;
            double a1 = Math.PI * 2.0 * (segment + 1) / CYLINDER_SEGMENTS;
            float[] b0 = transform(baseX, baseY, baseZ, cylinder,
                    (float) Math.cos(a0) * cylinder.radiusBottom(), 0,
                    (float) Math.sin(a0) * cylinder.radiusBottom());
            float[] b1 = transform(baseX, baseY, baseZ, cylinder,
                    (float) Math.cos(a1) * cylinder.radiusBottom(), 0,
                    (float) Math.sin(a1) * cylinder.radiusBottom());
            float[] t0 = transform(baseX, baseY, baseZ, cylinder,
                    (float) Math.cos(a0) * cylinder.radiusTop(), cylinder.height(),
                    (float) Math.sin(a0) * cylinder.radiusTop());
            float[] t1 = transform(baseX, baseY, baseZ, cylinder,
                    (float) Math.cos(a1) * cylinder.radiusTop(), cylinder.height(),
                    (float) Math.sin(a1) * cylinder.radiusTop());
            addQuad(output, b0, b1, t1, t0, normal(b0, b1, t1), color);
            float[] bottom = transform(baseX, baseY, baseZ, cylinder, 0, 0, 0);
            float[] top = transform(baseX, baseY, baseZ, cylinder, 0, cylinder.height(), 0);
            addTriangle(output, bottom, b1, b0, normal(bottom, b1, b0), color);
            addTriangle(output, top, t0, t1, normal(top, t0, t1), color);
        }
    }

    private static void addPolygon(
            FloatBuilder output,
            float baseX,
            float baseY,
            float baseZ,
            TileGeometryRegistry.Primitive polygon,
            float[] color) {
        if (polygon.points().size() < 3) return;
        float[][] points = new float[polygon.points().size()][];
        for (int index = 0; index < points.length; index++) {
            float[] point = polygon.points().get(index);
            float x;
            float y;
            float z;
            switch (polygon.plane()) {
                case "XY" -> { x = point[0]; y = point[1]; z = 0; }
                case "XZ" -> { x = point[0]; y = 0; z = point[1]; }
                case "YZ" -> { x = 0; y = point[0]; z = point[1]; }
                default -> { return; }
            }
            points[index] = transform(baseX, baseY, baseZ, polygon, x, y, z);
        }
        float[] faceNormal = normal(points[0], points[1], points[2]);
        for (int index = 1; index < points.length - 1; index++) {
            addTriangle(output, points[0], points[index], points[index + 1], faceNormal, color);
        }
    }

    private static float[] transform(
            float baseX,
            float baseY,
            float baseZ,
            TileGeometryRegistry.Primitive value,
            float x,
            float y,
            float z) {
        double rx = Math.toRadians(value.rx());
        double ry = Math.toRadians(value.ry());
        double rz = Math.toRadians(value.rz());
        float y1 = (float) (y * Math.cos(rx) - z * Math.sin(rx));
        float z1 = (float) (y * Math.sin(rx) + z * Math.cos(rx));
        float x2 = (float) (x * Math.cos(ry) + z1 * Math.sin(ry));
        float z2 = (float) (-x * Math.sin(ry) + z1 * Math.cos(ry));
        float x3 = (float) (x2 * Math.cos(rz) - y1 * Math.sin(rz));
        float y3 = (float) (x2 * Math.sin(rz) + y1 * Math.cos(rz));
        return new float[] {
            baseX + 0.5f + value.tx() + x3,
            baseY + value.ty() + y3,
            baseZ + 0.5f + value.tz() + z2
        };
    }

    private static void addQuad(
            FloatBuilder output,
            float ax, float ay, float az,
            float bx, float by, float bz,
            float cx, float cy, float cz,
            float dx, float dy, float dz,
            float nx, float ny, float nz,
            float r, float g, float b) {
        float[] color = {r, g, b};
        float[] normal = {nx, ny, nz};
        addQuad(output, new float[] {ax, ay, az}, new float[] {bx, by, bz},
                new float[] {cx, cy, cz}, new float[] {dx, dy, dz}, normal, color);
    }

    private static void addQuad(
            FloatBuilder output, float[] a, float[] b, float[] c, float[] d, float[] normal, float[] color) {
        addTriangle(output, a, b, c, normal, color);
        addTriangle(output, a, c, d, normal, color);
    }

    private static void addTriangle(
            FloatBuilder output, float[] a, float[] b, float[] c, float[] normal, float[] color) {
        addVertex(output, a, normal, color);
        addVertex(output, b, normal, color);
        addVertex(output, c, normal, color);
    }

    private static void addVertex(FloatBuilder output, float[] p, float[] n, float[] c) {
        output.add(p[0]); output.add(p[1]); output.add(p[2]);
        output.add(n[0]); output.add(n[1]); output.add(n[2]);
        output.add(c[0]); output.add(c[1]); output.add(c[2]);
    }

    private static float[] normal(float[] a, float[] b, float[] c) {
        float ux = b[0] - a[0];
        float uy = b[1] - a[1];
        float uz = b[2] - a[2];
        float vx = c[0] - a[0];
        float vy = c[1] - a[1];
        float vz = c[2] - a[2];
        float x = uy * vz - uz * vy;
        float y = uz * vx - ux * vz;
        float z = ux * vy - uy * vx;
        float length = (float) Math.sqrt(x * x + y * y + z * z);
        if (length < 0.000001f) return new float[] {0, 1, 0};
        return new float[] {x / length, y / length, z / length};
    }

    private static float[] squareLight(WorldState.Square square) {
        return new float[] {
            Math.max(0.42f, Math.min(1, square.lightR() / 255.0f)),
            Math.max(0.42f, Math.min(1, square.lightG() / 255.0f)),
            Math.max(0.42f, Math.min(1, square.lightB() / 255.0f))
        };
    }

    private static float[] identityColor(String value, float[] light) {
        float hue = (value.hashCode() & 0x7fff_ffff) % 1000 / 1000.0f;
        float[] rgb = hsv(hue, 0.28f, 0.78f);
        return new float[] {rgb[0] * light[0], rgb[1] * light[1], rgb[2] * light[2]};
    }

    private static float[] hsv(float hue, float saturation, float value) {
        float scaled = hue * 6.0f;
        int sector = (int) Math.floor(scaled);
        float fraction = scaled - sector;
        float p = value * (1 - saturation);
        float q = value * (1 - saturation * fraction);
        float t = value * (1 - saturation * (1 - fraction));
        return switch (sector % 6) {
            case 0 -> new float[] {value, t, p};
            case 1 -> new float[] {q, value, p};
            case 2 -> new float[] {p, value, t};
            case 3 -> new float[] {p, q, value};
            case 4 -> new float[] {t, p, value};
            default -> new float[] {value, p, q};
        };
    }

    private static TileGeometryRegistry.Primitive identityPrimitive() {
        return new TileGeometryRegistry.Primitive(
                "identity", 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, "", List.of());
    }

    private static final class FloatBuilder {
        private float[] values;
        private int size;

        FloatBuilder(int capacity) {
            values = new float[capacity];
        }

        void add(float value) {
            if (size == values.length) values = java.util.Arrays.copyOf(values, values.length * 2);
            values[size++] = value;
        }

        int vertexCount() {
            return size / FLOATS_PER_VERTEX;
        }

        float[] toArray() {
            return java.util.Arrays.copyOf(values, size);
        }
    }
}
