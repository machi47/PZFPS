package dev.pzfps.bridge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Immutable subset of PZ's tileGeometry.txt compiled by the project asset indexer. */
public final class TileGeometryRegistry {
    /** A dependency-free value class because the current injector defines classes in name order. */
    public record Primitive(
            String kind,
            float tx,
            float ty,
            float tz,
            float rx,
            float ry,
            float rz,
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ,
            float radiusBottom,
            float radiusTop,
            float height,
            String plane,
            List<float[]> points) {
        public Primitive {
            points = List.copyOf(points);
        }
    }

    private final Map<String, List<Primitive>> bySprite;
    private final String sourceSha256;

    private TileGeometryRegistry(Map<String, List<Primitive>> bySprite, String sourceSha256) {
        this.bySprite = Map.copyOf(bySprite);
        this.sourceSha256 = sourceSha256;
    }

    public static TileGeometryRegistry load(Path path) throws IOException {
        JSONObject root = new JSONObject(Files.readString(path));
        if (root.optInt("schema_version", 0) != 1) {
            throw new IOException("unsupported tile geometry schema in " + path);
        }
        JSONObject tiles = root.getJSONObject("tiles");
        HashMap<String, List<Primitive>> parsed = new HashMap<>(tiles.length() * 2);
        for (String sprite : tiles.keySet()) {
            JSONArray source = tiles.getJSONObject(sprite).optJSONArray("geometry");
            if (source == null || source.isEmpty()) continue;
            ArrayList<Primitive> primitives = new ArrayList<>(source.length());
            for (int index = 0; index < source.length(); index++) {
                JSONObject value = source.getJSONObject(index);
                float[] translate = vector(value.optJSONArray("translate"));
                float[] rotate = vector(value.optJSONArray("rotate_degrees"));
                switch (value.optString("kind", "")) {
                    case "box" -> {
                        float[] minimum = vector(value.getJSONArray("min"));
                        float[] maximum = vector(value.getJSONArray("max"));
                        primitives.add(new Primitive(
                                "box",
                                translate[0], translate[1], translate[2],
                                rotate[0], rotate[1], rotate[2],
                                minimum[0],
                                minimum[1],
                                minimum[2],
                                maximum[0],
                                maximum[1],
                                maximum[2],
                                0, 0, 0, "", List.of()));
                    }
                    case "cylinder" -> primitives.add(new Primitive(
                            "cylinder",
                            translate[0], translate[1], translate[2],
                            rotate[0], rotate[1], rotate[2],
                            0, 0, 0, 0, 0, 0,
                            value.getFloat("radius1"),
                            value.getFloat("radius2"),
                            value.getFloat("height"),
                            "", List.of()));
                    case "polygon" -> {
                        JSONArray points = value.getJSONArray("points");
                        ArrayList<float[]> parsedPoints = new ArrayList<>(points.length());
                        for (int pointIndex = 0; pointIndex < points.length(); pointIndex++) {
                            JSONArray point = points.getJSONArray(pointIndex);
                            parsedPoints.add(new float[] {point.getFloat(0), point.getFloat(1)});
                        }
                        primitives.add(new Primitive(
                                "polygon",
                                translate[0], translate[1], translate[2],
                                rotate[0], rotate[1], rotate[2],
                                0, 0, 0, 0, 0, 0, 0, 0, 0,
                                value.optString("plane", ""), parsedPoints));
                    }
                    default -> {
                        // The indexer may grow new primitive kinds. Unknown kinds stay explicit.
                    }
                }
            }
            if (!primitives.isEmpty()) parsed.put(sprite, List.copyOf(primitives));
        }
        return new TileGeometryRegistry(parsed, root.optString("source_sha256", ""));
    }

    public List<Primitive> geometry(String sprite) {
        return bySprite.getOrDefault(sprite, List.of());
    }

    public int tileCount() {
        return bySprite.size();
    }

    public String sourceSha256() {
        return sourceSha256;
    }

    private static float[] vector(JSONArray values) {
        if (values == null) return new float[] {0.0f, 0.0f, 0.0f};
        if (values.length() < 3) throw new IllegalArgumentException("expected a 3-vector");
        return new float[] {values.getFloat(0), values.getFloat(1), values.getFloat(2)};
    }
}
