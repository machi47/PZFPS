package dev.pzfps.bridge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** A PZ-declared neighbouring roof identity that makes contextual geometry safe. */
    public record RoofJoin(int dx, int dy, int dz, String relation, List<String> targets) {
        public RoofJoin {
            targets = List.copyOf(targets);
        }
    }

    private final Map<String, List<Primitive>> bySprite;
    private final Map<String, List<Primitive>> contextualBySprite;
    private final Map<String, List<RoofJoin>> roofJoinsBySprite;
    private final Map<String, String> roofTargetBySprite;
    private final Set<String> authoredReplacementSprites;
    private final String sourceSha256;

    private TileGeometryRegistry(
            Map<String, List<Primitive>> bySprite,
            Map<String, List<Primitive>> contextualBySprite,
            Map<String, List<RoofJoin>> roofJoinsBySprite,
            Map<String, String> roofTargetBySprite,
            Set<String> authoredReplacementSprites,
            String sourceSha256) {
        this.bySprite = Map.copyOf(bySprite);
        this.contextualBySprite = Map.copyOf(contextualBySprite);
        this.roofJoinsBySprite = Map.copyOf(roofJoinsBySprite);
        this.roofTargetBySprite = Map.copyOf(roofTargetBySprite);
        this.authoredReplacementSprites = Set.copyOf(authoredReplacementSprites);
        this.sourceSha256 = sourceSha256;
    }

    public static TileGeometryRegistry load(Path path) throws IOException {
        JSONObject root = new JSONObject(Files.readString(path));
        if (root.optInt("schema_version", 0) != 1) {
            throw new IOException("unsupported tile geometry schema in " + path);
        }
        JSONObject tiles = root.getJSONObject("tiles");
        HashMap<String, List<Primitive>> parsed = parseTileGeometry(tiles);
        HashMap<String, String> roofTargets = parseRoofTargets(tiles);
        Set<String> replacements = parseAuthoredReplacements(tiles);
        JSONObject contextualTiles = root.optJSONObject("contextual_tiles");
        HashMap<String, List<Primitive>> contextual = contextualTiles == null
                ? new HashMap<>() : parseTileGeometry(contextualTiles);
        HashMap<String, List<RoofJoin>> roofJoins = contextualTiles == null
                ? new HashMap<>() : parseRoofJoins(contextualTiles);
        if (contextualTiles != null) roofTargets.putAll(parseRoofTargets(contextualTiles));
        return new TileGeometryRegistry(
                parsed,
                contextual,
                roofJoins,
                roofTargets,
                replacements,
                root.optString("source_sha256", ""));
    }

    private static HashMap<String, List<Primitive>> parseTileGeometry(JSONObject tiles)
            throws IOException {
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
                        try {
                            PolygonTriangles.triangulate(parsedPoints);
                        } catch (IllegalArgumentException invalid) {
                            System.err.printf("[PZFPS geometry] rejected polygon sprite=%s primitive=%d reason=%s%n",
                                    sprite, index, invalid.getMessage());
                            continue;
                        }
                        primitives.add(new Primitive(
                                "polygon",
                                translate[0], translate[1], translate[2],
                                rotate[0], rotate[1], rotate[2],
                                0, 0, 0, 0, 0, 0, 0, 0, 0,
                                value.optString("plane", ""), parsedPoints));
                    }
                    case "triangle", "quad" -> {
                        JSONArray points = value.getJSONArray("points");
                        int expectedPoints = value.getString("kind").equals("quad") ? 4 : 3;
                        if (points.length() != expectedPoints) {
                            throw new IOException(value.getString("kind") + " has wrong point count for " + sprite);
                        }
                        ArrayList<float[]> parsedPoints = new ArrayList<>(expectedPoints);
                        for (int pointIndex = 0; pointIndex < expectedPoints; pointIndex++) {
                            JSONArray point = points.getJSONArray(pointIndex);
                            if (point.length() != 3) {
                                throw new IOException("triangle point must be a 3-vector for " + sprite);
                            }
                            parsedPoints.add(new float[] {
                                point.getFloat(0), point.getFloat(1), point.getFloat(2)
                            });
                        }
                        primitives.add(new Primitive(
                                value.getString("kind"),
                                0, 0, 0, 0, 0, 0,
                                0, 0, 0, 0, 0, 0, 0, 0, 0,
                                "", parsedPoints));
                    }
                    default -> {
                        // The indexer may grow new primitive kinds. Unknown kinds stay explicit.
                    }
                }
            }
            if (!primitives.isEmpty()) parsed.put(sprite, List.copyOf(primitives));
        }
        return parsed;
    }

    private static HashMap<String, String> parseRoofTargets(JSONObject tiles) {
        HashMap<String, String> result = new HashMap<>();
        for (String sprite : tiles.keySet()) {
            JSONObject properties = tiles.getJSONObject(sprite).optJSONObject("properties");
            if (properties == null) continue;
            String target = properties.optString("depth_target", "");
            if (!target.isBlank()) result.put(sprite, target);
        }
        return result;
    }

    private static Set<String> parseAuthoredReplacements(JSONObject tiles) {
        java.util.HashSet<String> result = new java.util.HashSet<>();
        for (String sprite : tiles.keySet()) {
            JSONObject properties = tiles.getJSONObject(sprite).optJSONObject("properties");
            if (properties != null && properties.optBoolean("replace_authored_geometry", false)) {
                result.add(sprite);
            }
        }
        return Set.copyOf(result);
    }

    private static HashMap<String, List<RoofJoin>> parseRoofJoins(JSONObject tiles)
            throws IOException {
        HashMap<String, List<RoofJoin>> result = new HashMap<>();
        for (String sprite : tiles.keySet()) {
            JSONObject properties = tiles.getJSONObject(sprite).optJSONObject("properties");
            if (properties == null) continue;
            JSONArray source = properties.optJSONArray("context_joins");
            if (source == null || source.isEmpty()) continue;
            ArrayList<RoofJoin> joins = new ArrayList<>(source.length());
            for (int index = 0; index < source.length(); index++) {
                JSONObject value = source.getJSONObject(index);
                JSONArray offset = value.getJSONArray("offset");
                if (offset.length() != 3) {
                    throw new IOException("roof context offset must be a 3-vector for " + sprite);
                }
                JSONArray targets = value.getJSONArray("targets");
                ArrayList<String> parsedTargets = new ArrayList<>(targets.length());
                for (int target = 0; target < targets.length(); target++) {
                    parsedTargets.add(targets.getString(target));
                }
                joins.add(new RoofJoin(
                        offset.getInt(0), offset.getInt(1), offset.getInt(2),
                        value.optString("relation", ""), parsedTargets));
            }
            result.put(sprite, List.copyOf(joins));
        }
        return result;
    }

    /** Merge evidence-derived geometry only where the installed authored registry has no mesh. */
    public static TileGeometryRegistry load(Path primary, Path supplemental) throws IOException {
        return load(primary, List.of(supplemental));
    }

    /** Merge evidence-derived registries in order. Ordinary derived geometry fills holes;
     * an explicit source-evidence flag may replace a tileGeometry support volume. */
    public static TileGeometryRegistry load(Path primary, List<Path> supplemental)
            throws IOException {
        TileGeometryRegistry authored = load(primary);
        HashMap<String, List<Primitive>> merged = new HashMap<>(authored.bySprite);
        HashMap<String, List<Primitive>> contextual = new HashMap<>(authored.contextualBySprite);
        HashMap<String, List<RoofJoin>> roofJoins = new HashMap<>(authored.roofJoinsBySprite);
        HashMap<String, String> roofTargets = new HashMap<>(authored.roofTargetBySprite);
        java.util.HashSet<String> replacements = new java.util.HashSet<>();
        StringBuilder hashes = new StringBuilder(authored.sourceSha256);
        for (Path path : supplemental) {
            TileGeometryRegistry derived = load(path);
            for (Map.Entry<String, List<Primitive>> entry : derived.bySprite.entrySet()) {
                if (derived.authoredReplacementSprites.contains(entry.getKey())) {
                    merged.put(entry.getKey(), entry.getValue());
                    replacements.add(entry.getKey());
                } else {
                    merged.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
            for (Map.Entry<String, List<Primitive>> entry : derived.contextualBySprite.entrySet())
                contextual.putIfAbsent(entry.getKey(), entry.getValue());
            for (Map.Entry<String, List<RoofJoin>> entry : derived.roofJoinsBySprite.entrySet())
                roofJoins.putIfAbsent(entry.getKey(), entry.getValue());
            for (Map.Entry<String, String> entry : derived.roofTargetBySprite.entrySet())
                roofTargets.putIfAbsent(entry.getKey(), entry.getValue());
            hashes.append("+supplemental:").append(derived.sourceSha256);
        }
        return new TileGeometryRegistry(
                merged,
                contextual,
                roofJoins,
                roofTargets,
                replacements,
                hashes.toString());
    }

    public List<Primitive> geometry(String sprite) {
        return bySprite.getOrDefault(sprite, List.of());
    }

    public List<Primitive> contextualGeometry(String sprite) {
        return contextualBySprite.getOrDefault(sprite, List.of());
    }

    public List<RoofJoin> roofJoins(String sprite) {
        return roofJoinsBySprite.getOrDefault(sprite, List.of());
    }

    public String roofTarget(String sprite) {
        return roofTargetBySprite.getOrDefault(sprite, sprite);
    }

    public int contextualTileCount() {
        return contextualBySprite.size();
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
