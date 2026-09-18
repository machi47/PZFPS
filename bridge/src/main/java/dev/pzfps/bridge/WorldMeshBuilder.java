package dev.pzfps.bridge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/** Converts immutable PZ snapshots into renderer-owned triangles without touching live objects. */
public final class WorldMeshBuilder {
    public static final int FLOATS_PER_VERTEX = 10;
    public static final int TEXTURED_FLOATS_PER_VERTEX = 13;
    private static final float LEVEL_HEIGHT = 3.0f;
    // PZ's tile-depth scene uses 2*sqrt(1.5) authored units per floor, not 3.
    static final float AUTHORED_HEIGHT_TO_WORLD = (float) Math.sqrt(1.5);
    private static final int CYLINDER_SEGMENTS = 10;
    private static final int MAX_VERTICES_PER_CHUNK = 500_000;

    /** Vertex X/Z are chunk-local from construction onward; culling bounds are world-space.
     * Rebasing after float world-space construction cannot recover lost submillimetre detail. */
    public record MeshData(
            long key,
            long fingerprint,
            float[] vertices,
            List<TexturedBatch> texturedBatches,
            List<MaterialBatch> materialBatches,
            int primitiveCount,
            Coverage coverage,
            Map<String, Integer> unsupportedSprites,
            Map<String, Integer> unsupportedCollisionSprites,
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ) {
        public MeshData {
            texturedBatches = List.copyOf(texturedBatches);
            materialBatches = List.copyOf(materialBatches);
            unsupportedSprites = Map.copyOf(unsupportedSprites);
            unsupportedCollisionSprites = Map.copyOf(unsupportedCollisionSprites);
        }

        public int vertexCount() {
            int count = vertices.length / FLOATS_PER_VERTEX;
            for (TexturedBatch batch : texturedBatches) count += batch.vertexCount();
            for (MaterialBatch batch : materialBatches) count += batch.vertexCount();
            return count;
        }
    }

    /** Explicitly accounts for what became geometry and what remains an honest hole. */
    public record Coverage(
            int sourceTexturedFloors,
            int flatFallbackFloors,
            int stairFloorOpenings,
            int authoredGeometryObjects,
            int structuralFallbackObjects,
            int mirroredStructuralFaces,
            int completedInteriorCeilings,
            int nativeWorldItems,
            int unsupportedObjects,
            int collisionCriticalUnsupportedObjects,
            int truncatedChunks) {
        public static Coverage none() {
            return new Coverage(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        public Coverage plus(Coverage other) {
            return new Coverage(
                    sourceTexturedFloors + other.sourceTexturedFloors,
                    flatFallbackFloors + other.flatFallbackFloors,
                    stairFloorOpenings + other.stairFloorOpenings,
                    authoredGeometryObjects + other.authoredGeometryObjects,
                    structuralFallbackObjects + other.structuralFallbackObjects,
                    mirroredStructuralFaces + other.mirroredStructuralFaces,
                    completedInteriorCeilings + other.completedInteriorCeilings,
                    nativeWorldItems + other.nativeWorldItems,
                    unsupportedObjects + other.unsupportedObjects,
                    collisionCriticalUnsupportedObjects
                            + other.collisionCriticalUnsupportedObjects,
                    truncatedChunks + other.truncatedChunks);
        }
    }

    /** Source sprite samples projected onto only the support surfaces we currently know. */
    public record TexturedBatch(
            String sprite,
            float[] vertices,
            boolean solidFloor,
            boolean wallEdges,
            boolean wallAttachment) {
        public int vertexCount() {
            return vertices.length / TEXTURED_FLOATS_PER_VERTEX;
        }
    }

    /** Deterministic persistent material recipe evaluated from continuous world coordinates. */
    public record MaterialBatch(String material, float[] vertices) {
        public int vertexCount() {
            return vertices.length / FLOATS_PER_VERTEX;
        }
    }

    private final TileGeometryRegistry registry;
    // One worker owns this builder. Bounded by this immutable registry's polygons,
    // not live object count; repeat instances never rerun triangulation.
    private final Map<TileGeometryRegistry.Primitive, int[]> polygonTriangles = new java.util.IdentityHashMap<>();
    private int reportedPropConstraints;

    public WorldMeshBuilder(TileGeometryRegistry registry) {
        this.registry = registry;
    }

    public MeshData build(WorldState.Chunk chunk) {
        FloatBuilder output = new FloatBuilder(16_384, FLOATS_PER_VERTEX);
        Map<String, FloatBuilder> textured = new LinkedHashMap<>();
        Set<String> floorSprites = new HashSet<>();
        Set<String> wallSprites = new HashSet<>();
        Set<String> wallAttachmentSprites = new HashSet<>();
        Map<String, FloatBuilder> materials = new LinkedHashMap<>();
        int primitiveCount = 0;
        int sourceTexturedFloors = 0;
        int flatFallbackFloors = 0;
        int stairFloorOpenings = 0;
        int authoredGeometryObjects = 0;
        int structuralFallbackObjects = 0;
        int mirroredStructuralFaces = 0;
        int completedInteriorCeilings = 0;
        int nativeWorldItems = 0;
        int unsupportedObjects = 0;
        int collisionCriticalUnsupportedObjects = 0;
        Map<String, Integer> unsupportedSprites = new HashMap<>();
        Map<String, Integer> unsupportedCollisionSprites = new HashMap<>();
        boolean truncated = false;
        Map<Long, WorldState.Square> squaresByPosition = squareIndex(chunk.squares());
        List<StructuralPropClip.Boundary> wallBoundaries = StructuralPropClip.boundaries(chunk.squares());
        for (WorldState.Square square : chunk.squares()) {
            float baseX = square.localX();
            float baseY = square.z() * LEVEL_HEIGHT;
            float baseZ = square.localY();
            // Appearance and topology are persistent; live light is a separate GPU grid.
            float[] light = {1, 1, 1};
            int lightingIndex = ChunkLighting.index(square.localX(), square.localY(), square.z());
            output.lightingIndex = lightingIndex;
            // B42 reports an upper square as a solid floor even when a staircase on the level
            // below enters through it. HasStairsBelow is authoritative topology for that opening;
            // drawing the generic full-tile floor here creates the observed solid plane over the
            // stairwell. Stairs on this square do not imply a hole beneath themselves.
            if (hasFloorSurface(square) && !square.stairsBelow()) {
                String floorSprite = floorSprite(square);
                if (!floorSprite.isEmpty()) {
                    sourceTexturedFloors++;
                    floorSprites.add(floorSprite);
                    FloatBuilder batch = textured.computeIfAbsent(
                            floorSprite,
                            ignored -> new FloatBuilder(512, TEXTURED_FLOATS_PER_VERTEX));
                    batch.layer = 0;
                    batch.lightingIndex = lightingIndex;
                    addTexturedQuad(
                            batch,
                            new float[] {baseX, baseY, baseZ},
                            new float[] {baseX, baseY, baseZ + 1},
                            new float[] {baseX + 1, baseY, baseZ + 1},
                            new float[] {baseX + 1, baseY, baseZ},
                            new float[] {0, 1, 0},
                            new float[] {light[0], light[1], light[2]},
                            new float[][] {
                                sourcePixel(-0.5f, 0, -0.5f),
                                sourcePixel(-0.5f, 0, 0.5f),
                                sourcePixel(0.5f, 0, 0.5f),
                                sourcePixel(0.5f, 0, -0.5f)
                            });
                } else {
                    flatFallbackFloors++;
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
            } else if (hasFloorSurface(square)) {
                stairFloorOpenings++;
            }
            if (shouldCompleteInteriorCeiling(square, squaresByPosition)) {
                FloatBuilder ceiling = materials.computeIfAbsent(
                        "interior-plaster",
                        ignored -> new FloatBuilder(512, FLOATS_PER_VERTEX));
                ceiling.lightingIndex = lightingIndex;
                addInteriorCeiling(ceiling, baseX, baseY + LEVEL_HEIGHT, baseZ, light);
                completedInteriorCeilings++;
                primitiveCount++;
            }
            for (WorldState.TileObject object : square.objects()) {
                // The installed game exposes solidfloor as the semantic source of truth. Keep
                // the name fallback only for protocol fixtures/older snapshots; relying on the
                // conventional floors_ prefix misclassified mod and B42 floor identities as
                // full 3D objects and could draw them twice.
                if (object.floor() || object.sprite().startsWith("floors_")) continue;
                // PZ chooses a dedicated InventoryItem static/world model here. Treating its
                // generated IsoSprite name as a map-tile identity produced repeated unrelated
                // geometry (the observed "brown pots"). Preserve the authoritative item/model
                // metadata, but leave an honest hole until the model consumer is connected.
                if (object.worldItem().present()) {
                    nativeWorldItems++;
                    continue;
                }
                List<TileGeometryRegistry.Primitive> geometry = registry.geometry(object.sprite());
                var wallAttachment = WallAttachmentAssembly.placement(
                        object, geometry, square.sealedEdges());
                if (FenceAssembly.shortChainLink(object)) {
                    // The installed family mixes authored boxes (24/26) with empty
                    // geometry (25/27). Letting those take different paths shifted every
                    // other tile in perspective. One boundary-owned path shares endpoints.
                    structuralFallbackObjects++;
                    FloatBuilder batch = textured.computeIfAbsent(
                            object.sprite(),
                            ignored -> new FloatBuilder(512, TEXTURED_FLOATS_PER_VERTEX));
                    batch.layer = Math.min(16, Math.max(1, object.index() + 1));
                    batch.lightingIndex = lightingIndex;
                    addSourceEdgePanel(batch, baseX, baseY, baseZ,
                            FenceAssembly.north(object), object.index(), light);
                    primitiveCount++;
                } else if (wallAttachment.isPresent()) {
                    authoredGeometryObjects++;
                    wallAttachmentSprites.add(object.sprite());
                    FloatBuilder batch = textured.computeIfAbsent(
                            object.sprite(),
                            ignored -> new FloatBuilder(512, TEXTURED_FLOATS_PER_VERTEX));
                    batch.layer = 0;
                    batch.lightingIndex = lightingIndex;
                    addWallAttachment(batch, baseX, baseY, baseZ, wallAttachment.get(), light);
                    primitiveCount++;
                } else if (!geometry.isEmpty()) {
                    authoredGeometryObjects++;
                    FloatBuilder batch = textured.computeIfAbsent(
                            object.sprite(),
                            ignored -> new FloatBuilder(512, TEXTURED_FLOATS_PER_VERTEX));
                    boolean wallConstrained = StructuralPropClip.constrainedByWalls(object);
                    // Authored scene volume is not a wall decal. Pulling fixtures or props
                    // toward the camera by source-object order exposes them through walls.
                    batch.layer = wallConstrained ? 0 : Math.min(16, Math.max(1, object.index() + 1));
                    batch.lightingIndex = lightingIndex;
                    int objectStart = batch.size;
                    for (TileGeometryRegistry.Primitive primitive : geometry) {
                        addTexturedPrimitive(batch, baseX, baseY, baseZ, primitive, light);
                        if (primitive.kind().equals("box")) {
                            if (BoxSideCompletion.closedCrate(object.sprite())) {
                                addOppositeBoxSide(batch, baseX, baseY, baseZ, primitive, light, 0);
                                addOppositeBoxSide(batch, baseX, baseY, baseZ, primitive, light, 2);
                                addCrateBottom(batch, baseX, baseY, baseZ, primitive, light);
                            } else {
                                int sideAxis = BoxSideCompletion.sideAxis(object.sprite(), object.appearanceFacing(), primitive);
                                if (sideAxis >= 0) addOppositeBoxSide(batch, baseX, baseY, baseZ, primitive, light, sideAxis);
                            }
                        }
                        primitiveCount++;
                    }
                    if (wallConstrained && !wallBoundaries.isEmpty()) {
                        float[] original = java.util.Arrays.copyOfRange(batch.values, objectStart, batch.size);
                        float[] constrained = StructuralPropClip.clip(
                                original, baseX, baseY, baseZ, wallBoundaries);
                        if (reportedPropConstraints < 8 && !java.util.Arrays.equals(original, constrained)) {
                            reportedPropConstraints++;
                            System.out.printf("[PZFPS prop-boundary] sprite=%s chunk=%d,%d local=%d,%d,%d wallSegments=%d triangles=%d->%d%n",
                                    object.sprite(), chunk.worldX(), chunk.worldY(), square.localX(), square.localY(), square.z(),
                                    wallBoundaries.size(), original.length / (3 * TEXTURED_FLOATS_PER_VERTEX),
                                    constrained.length / (3 * TEXTURED_FLOATS_PER_VERTEX));
                        }
                        batch.size = objectStart;
                        for (float value : constrained) batch.add(value);
                    }
                } else if (isStructuralPanel(object)) {
                    structuralFallbackObjects++;
                    boolean mirrorSafe = isMirrorSafeStructuralPanel(object);
                    if (mirrorSafe && !object.sprite().startsWith("fencing_")) {
                        wallSprites.add(object.sprite());
                    }
                    FloatBuilder batch = textured.computeIfAbsent(
                            object.sprite(),
                            ignored -> new FloatBuilder(512, TEXTURED_FLOATS_PER_VERTEX));
                    batch.layer = Math.min(16, Math.max(1, object.index() + 1));
                    batch.lightingIndex = lightingIndex;
                    boolean emitted = false;
                    if (object.edgeNorth()) {
                        addSourceEdgePanel(
                                batch,
                                baseX,
                                baseY,
                                baseZ,
                                true,
                                object.index(),
                                light);
                        primitiveCount++;
                        if (mirrorSafe) mirroredStructuralFaces++;
                        emitted = true;
                    }
                    if (object.edgeWest()) {
                        addSourceEdgePanel(
                                batch,
                                baseX,
                                baseY,
                                baseZ,
                                false,
                                object.index(),
                                light);
                        primitiveCount++;
                        if (mirrorSafe) mirroredStructuralFaces++;
                        emitted = true;
                    }
                    // Some door/window subclasses expose orientation but not collision flags.
                    // Preserve a conservative edge fallback without returning to tile-centred slabs.
                    if (!emitted && (object.door() || object.window())) {
                        addSourceEdgePanel(
                                batch,
                                baseX,
                                baseY,
                                baseZ,
                                object.north(),
                                object.index(),
                                light);
                        primitiveCount++;
                    }
                } else {
                    unsupportedObjects++;
                    String identity = unsupportedIdentity(object);
                    unsupportedSprites.merge(identity, 1, Integer::sum);
                    if (object.solid() || object.solidTrans()) {
                        collisionCriticalUnsupportedObjects++;
                        unsupportedCollisionSprites.merge(identity, 1, Integer::sum);
                    }
                }
                if (totalVertexCount(output, textured, materials) >= MAX_VERTICES_PER_CHUNK) {
                    truncated = true;
                    break;
                }
            }
            if (totalVertexCount(output, textured, materials) >= MAX_VERTICES_PER_CHUNK) {
                truncated = true;
                break;
            }
        }
        float[] vertices = output.toArray();
        ArrayList<TexturedBatch> texturedBatches = new ArrayList<>(textured.size());
        for (Map.Entry<String, FloatBuilder> entry : textured.entrySet()) {
            if (entry.getValue().vertexCount() > 0) {
                texturedBatches.add(new TexturedBatch(entry.getKey(), entry.getValue().toArray(),
                        floorSprites.contains(entry.getKey()), wallSprites.contains(entry.getKey()),
                        wallAttachmentSprites.contains(entry.getKey())));
            }
        }
        ArrayList<MaterialBatch> materialBatches = new ArrayList<>(materials.size());
        for (Map.Entry<String, FloatBuilder> entry : materials.entrySet()) {
            if (entry.getValue().vertexCount() > 0) {
                materialBatches.add(new MaterialBatch(entry.getKey(), entry.getValue().toArray()));
            }
        }
        float[] bounds = chunkBounds(chunk, vertices, texturedBatches, materialBatches);
        return new MeshData(
                chunk.key(),
                chunk.fingerprint(),
                vertices,
                texturedBatches,
                materialBatches,
                primitiveCount,
                new Coverage(
                        sourceTexturedFloors,
                        flatFallbackFloors,
                        stairFloorOpenings,
                        authoredGeometryObjects,
                        structuralFallbackObjects,
                        mirroredStructuralFaces,
                        completedInteriorCeilings,
                        nativeWorldItems,
                        unsupportedObjects,
                        collisionCriticalUnsupportedObjects,
                        truncated ? 1 : 0),
                unsupportedSprites,
                unsupportedCollisionSprites,
                bounds[0],
                bounds[1],
                bounds[2],
                bounds[3],
                bounds[4],
                bounds[5]);
    }

    private static String floorSprite(WorldState.Square square) {
        for (WorldState.TileObject object : square.objects()) {
            if (object.worldItem().present()) continue;
            if (object.floor() && !object.sprite().isBlank()) return object.sprite();
        }
        // Compatibility with pre-floor-flag captures and intentionally minimal fixtures.
        for (WorldState.TileObject object : square.objects()) {
            if (object.worldItem().present()) continue;
            String sprite = object.sprite();
            if (sprite.startsWith("floors_")) return sprite;
        }
        return "";
    }

    private static boolean hasFloorSurface(WorldState.Square square) {
        // IsoGridSquare.isSolidFloor() merely reads a lazy collision cache. Occupied squares
        // need not have had TreatAsSolidFloor() called, even with a real solidfloor object.
        // Do not mutate that cache from the renderer or infer floors beneath empty squares.
        return square.solidFloor() || square.objects().stream()
                .anyMatch(object -> object.floor() && !object.worldItem().present());
    }

    private static String unsupportedIdentity(WorldState.TileObject object) {
        if (!object.sprite().isBlank()) return object.sprite();
        if (!object.objectType().isBlank()) return "<type:" + object.objectType() + ">";
        return "<class:" + object.javaType() + ">";
    }

    private static boolean isStructuralPanel(WorldState.TileObject object) {
        if (isRoofSprite(object.sprite())) return false;
        if (object.edgeNorth() || object.edgeWest() || object.door() || object.window()) return true;
        String type = object.objectType().toLowerCase(java.util.Locale.ROOT);
        String sprite = object.sprite().toLowerCase(java.util.Locale.ROOT);
        return type.contains("wall")
                || sprite.startsWith("walls_")
                || sprite.startsWith("wall_")
                || sprite.startsWith("fencing_");
    }

    /**
     * Legacy coverage grouping for non-stateful structural boundaries. All emitted textured
     * surfaces now draw two-sided, but only ordinary walls qualify for outer-edge alpha repair.
     * Doors/windows retain their exact alpha and still need state-aware assemblies.
     */
    private static boolean isMirrorSafeStructuralPanel(WorldState.TileObject object) {
        if (isRoofSprite(object.sprite())) return false;
        if (object.door() || object.window()) return false;
        String type = object.objectType().toLowerCase(java.util.Locale.ROOT);
        String sprite = object.sprite().toLowerCase(java.util.Locale.ROOT);
        return type.contains("wall")
                || sprite.startsWith("walls_")
                || sprite.startsWith("wall_")
                || sprite.startsWith("fencing_");
    }

    private static boolean isRoofSprite(String sprite) {
        String lower = sprite.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("roofs_")
                || lower.startsWith("roofing_")
                || lower.startsWith("walls_exterior_roofs_");
    }

    private static void addSourceEdgePanel(
            FloatBuilder output,
            float baseX,
            float baseY,
            float baseZ,
            boolean north,
            int objectLayer,
            float[] light) {
        float height = 3.0f;
        // PZ composes several sprite layers on the same tile edge. Preserve that
        // deterministic order without coplanar depth fighting in perspective.
        // Layer ordering is applied as a bounded depth-only offset in the shader, shared
        // with authored props. Shifting only walls physically made their order depend on
        // which side the camera viewed and left shelf/window layers at identical depths.
        float layerOffset = 0;
        float[][] local = north
                ? new float[][] {
                    {-0.5f, 0, -0.5f + layerOffset},
                    {0.5f, 0, -0.5f + layerOffset},
                    {0.5f, height, -0.5f + layerOffset},
                    {-0.5f, height, -0.5f + layerOffset}
                }
                : new float[][] {
                    {-0.5f + layerOffset, 0, 0.5f},
                    {-0.5f + layerOffset, 0, -0.5f},
                    {-0.5f + layerOffset, height, -0.5f},
                    {-0.5f + layerOffset, height, 0.5f}
                };
        float[][] world = new float[4][];
        float[][] source = new float[4][];
        for (int index = 0; index < 4; index++) {
            world[index] = new float[] {
                baseX + 0.5f + local[index][0],
                baseY + local[index][1],
                baseZ + 0.5f + local[index][2]
            };
            source[index] = sourcePixel(local[index][0], local[index][1], local[index][2]);
        }
        float[] faceNormal = normal(world[0], world[1], world[2]);
        addTexturedQuad(output, world[0], world[1], world[2], world[3],
                faceNormal, light, source);
        // The source-texture draw is two-sided. Do not duplicate coplanar reverse geometry:
        // that would submit both copies and darken translucent edges after disabling culling.
    }

    private static void addWallAttachment(
            FloatBuilder output,
            float baseX,
            float baseY,
            float baseZ,
            WallAttachmentAssembly.Placement placement,
            float[] light) {
        float boundaryX = baseX + .5f;
        float boundaryZ = baseZ + .5f;
        float nx = 0, nz = 0;
        switch (placement.edge()) {
            case StructuralPropClip.NORTH -> { boundaryZ = baseZ; nz = 1; }
            case StructuralPropClip.SOUTH -> { boundaryZ = baseZ + 1; nz = -1; }
            case StructuralPropClip.WEST -> { boundaryX = baseX; nx = 1; }
            case StructuralPropClip.EAST -> { boundaryX = baseX + 1; nx = -1; }
            default -> throw new IllegalArgumentException("unknown attachment edge " + placement.edge());
        }
        float tx = nz == 0 ? 0 : 1;
        float tz = nx == 0 ? 0 : 1;
        float bottom = baseY + placement.bottom();
        float top = bottom + placement.height();
        float halfWidth = placement.width() * .5f;
        float[][] front = attachmentFace(
                boundaryX + nx * placement.depth(),
                boundaryZ + nz * placement.depth(),
                tx, tz, halfWidth, bottom, top);
        float[][] back = attachmentFace(
                boundaryX + nx * .002f,
                boundaryZ + nz * .002f,
                tx, tz, halfWidth, bottom, top);
        float[][] uv = {{0,1},{1,1},{1,0},{0,0}};
        addTexturedQuad(output, front[0], front[1], front[2], front[3],
                new float[] {nx,0,nz}, light, uv);
        addTexturedQuad(output, back[1], back[0], back[3], back[2],
                new float[] {-nx,0,-nz}, light, new float[][] {uv[1],uv[0],uv[3],uv[2]});
        addTexturedQuad(output, back[0], front[0], front[3], back[3],
                new float[] {-tx,0,-tz}, light,
                new float[][] {{0,1},{0,1},{0,0},{0,0}});
        addTexturedQuad(output, front[1], back[1], back[2], front[2],
                new float[] {tx,0,tz}, light,
                new float[][] {{1,1},{1,1},{1,0},{1,0}});
        addTexturedQuad(output, front[3], front[2], back[2], back[3],
                new float[] {0,1,0}, light,
                new float[][] {{0,0},{1,0},{1,0},{0,0}});
        addTexturedQuad(output, back[0], back[1], front[1], front[0],
                new float[] {0,-1,0}, light,
                new float[][] {{0,1},{1,1},{1,1},{0,1}});
    }

    private static float[][] attachmentFace(
            float centerX,
            float centerZ,
            float tangentX,
            float tangentZ,
            float halfWidth,
            float bottom,
            float top) {
        return new float[][] {
            {centerX - tangentX * halfWidth, bottom, centerZ - tangentZ * halfWidth},
            {centerX + tangentX * halfWidth, bottom, centerZ + tangentZ * halfWidth},
            {centerX + tangentX * halfWidth, top, centerZ + tangentZ * halfWidth},
            {centerX - tangentX * halfWidth, top, centerZ - tangentZ * halfWidth}
        };
    }

    private static Map<Long, WorldState.Square> squareIndex(
            List<WorldState.Square> squares) {
        Map<Long, WorldState.Square> result = new HashMap<>(squares.size());
        for (WorldState.Square square : squares) {
            result.put(squarePositionKey(square.localX(), square.localY(), square.z()), square);
        }
        return result;
    }

    private static long squarePositionKey(int localX, int localY, int z) {
        return ((long) (z & 0xffff) << 32)
                | ((long) (localY & 0xffff) << 16)
                | (localX & 0xffffL);
    }

    /**
     * Completes only an interior horizontal boundary PZ already proves exists. An upper solid
     * floor is authoritative for stacked storeys; haveRoof covers a top-storey room. Stair
     * metadata wins over either signal so the completion cannot cap a traversable opening.
     */
    private static boolean shouldCompleteInteriorCeiling(
            WorldState.Square square, Map<Long, WorldState.Square> squaresByPosition) {
        if (square.roomId() < 0
                || square.exterior()
                || square.stairs()
                || square.stairTop()) {
            return false;
        }
        WorldState.Square upper = squaresByPosition.get(
                squarePositionKey(square.localX(), square.localY(), square.z() + 1));
        if (upper != null) return hasFloorSurface(upper) && !upper.stairsBelow();
        return square.roof();
    }

    private static void addInteriorCeiling(
            FloatBuilder output, float x, float y, float z, float[] light) {
        float[] color = new float[] {
            light[0] * 0.92f,
            light[1] * 0.90f,
            light[2] * 0.86f
        };
        // Clockwise from above is counter-clockwise from the room, producing a -Y normal.
        addQuad(
                output,
                new float[] {x, y, z},
                new float[] {x + 1.0f, y, z},
                new float[] {x + 1.0f, y, z + 1.0f},
                new float[] {x, y, z + 1.0f},
                new float[] {0.0f, -1.0f, 0.0f},
                color);
    }

    private static int totalVertexCount(
            FloatBuilder flat,
            Map<String, FloatBuilder> textured,
            Map<String, FloatBuilder> materials) {
        int count = flat.vertexCount();
        for (FloatBuilder batch : textured.values()) count += batch.vertexCount();
        for (FloatBuilder batch : materials.values()) count += batch.vertexCount();
        return count;
    }

    /** PZ 2x source projection after converting one 192-pixel level to three world metres. */
    private static float[] sourcePixel(float x, float y, float z) {
        return new float[] {
            64.0f + (x - z) * 64.0f,
            224.0f + (x + z) * 32.0f - y * 64.0f
        };
    }

    static float[] sourcePixel(float[] point) {
        // Registry points are in TileGeometryUtils scene units. Structural fallback panels
        // already use world-height units and call the scalar overload instead.
        return sourcePixel(point[0], point[1] * AUTHORED_HEIGHT_TO_WORLD, point[2]);
    }

    private static float[] chunkBounds(
            WorldState.Chunk chunk,
            float[] vertices,
            List<TexturedBatch> texturedBatches,
            List<MaterialBatch> materialBatches) {
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
        for (TexturedBatch batch : texturedBatches) {
            float[] textured = batch.vertices();
            for (int index = 0; index < textured.length; index += TEXTURED_FLOATS_PER_VERTEX) {
                float x = textured[index];
                float y = textured[index + 1];
                float z = textured[index + 2];
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                maxZ = Math.max(maxZ, z);
            }
        }
        for (MaterialBatch batch : materialBatches) {
            float[] material = batch.vertices();
            for (int index = 0; index < material.length; index += FLOATS_PER_VERTEX) {
                float x = material[index];
                float y = material[index + 1];
                float z = material[index + 2];
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                maxZ = Math.max(maxZ, z);
            }
        }
        int chunkSize = zombie.iso.IsoChunkMap.CHUNK_SIZE_IN_SQUARES;
        minX = outwardBound(Math.min(minX, 0), chunk.worldX(), chunkSize, false);
        minZ = outwardBound(Math.min(minZ, 0), chunk.worldY(), chunkSize, false);
        maxX = outwardBound(Math.max(maxX, chunkSize), chunk.worldX(), chunkSize, true);
        maxZ = outwardBound(Math.max(maxZ, chunkSize), chunk.worldY(), chunkSize, true);
        for (WorldState.Square square : chunk.squares()) {
            minY = Math.min(minY, square.z() * LEVEL_HEIGHT);
            maxY = Math.max(maxY, (square.z() + 1) * LEVEL_HEIGHT);
        }
        if (!Float.isFinite(minY)) {
            minY = 0;
            maxY = LEVEL_HEIGHT;
        }
        return new float[] {minX, minY, minZ, maxX, maxY, maxZ};
    }

    private static float outwardBound(float local, int chunk, int size, boolean maximum) {
        double exact = (double) chunk * size + local;
        float rounded = (float) exact;
        if (maximum && rounded < exact) return Math.nextUp(rounded);
        if (!maximum && rounded > exact) return Math.nextDown(rounded);
        return rounded;
    }

    private void addTexturedPrimitive(
            FloatBuilder output,
            float baseX,
            float baseY,
            float baseZ,
            TileGeometryRegistry.Primitive primitive,
            float[] light) {
        switch (primitive.kind()) {
            case "box" -> addTexturedBox(output, baseX, baseY, baseZ, primitive, light);
            case "cylinder" -> addTexturedCylinder(output, baseX, baseY, baseZ, primitive, light);
            case "polygon" -> addTexturedPolygon(output, baseX, baseY, baseZ, primitive, light);
            case "triangle" -> addTexturedSourceTriangle(
                    output, baseX, baseY, baseZ, primitive, light);
            default -> {
                // Unknown source primitives are deliberately omitted by the registry loader.
            }
        }
    }

    private static void addTexturedSourceTriangle(
            FloatBuilder output,
            float baseX,
            float baseY,
            float baseZ,
            TileGeometryRegistry.Primitive triangle,
            float[] light) {
        if (triangle.points().size() != 3) return;
        float[][] transformed = transformedLocal(
                triangle, triangle.points().toArray(float[][]::new));
        float[][] world = worldPoints(baseX, baseY, baseZ, transformed);
        float[] faceNormal = normal(world[0], world[1], world[2]);
        int second = 1;
        int third = 2;
        if (sourceFacing(faceNormal) < 0) {
            second = 2;
            third = 1;
            faceNormal = new float[] {-faceNormal[0], -faceNormal[1], -faceNormal[2]};
        }
        addTexturedTriangle(
                output,
                world[0], world[second], world[third], faceNormal, light,
                sourcePixel(transformed[0]),
                sourcePixel(transformed[second]),
                sourcePixel(transformed[third]));
    }

    /**
     * Projects the exact PZ sprite onto only the faces its original (+x,+y,+z)
     * isometric camera could observe. The registry geometry remains authoritative;
     * no unobserved face receives invented pixels.
     */
    private static void addTexturedBox(
            FloatBuilder output,
            float baseX,
            float baseY,
            float baseZ,
            TileGeometryRegistry.Primitive box,
            float[] light) {
        float[][] points = {
            {box.minX(), box.minY(), box.minZ()},
            {box.maxX(), box.minY(), box.minZ()},
            {box.maxX(), box.maxY(), box.minZ()},
            {box.minX(), box.maxY(), box.minZ()},
            {box.minX(), box.minY(), box.maxZ()},
            {box.maxX(), box.minY(), box.maxZ()},
            {box.maxX(), box.maxY(), box.maxZ()},
            {box.minX(), box.maxY(), box.maxZ()}
        };
        emitObservedFaces(
                output,
                baseX,
                baseY,
                baseZ,
                box,
                points,
                new int[][] {
                    {0, 3, 2, 1}, {4, 5, 6, 7}, {0, 4, 7, 3},
                    {1, 2, 6, 5}, {3, 7, 6, 2}, {0, 1, 5, 4}
                },
                light);
    }

    private static void addOppositeBoxSide(
            FloatBuilder output, float baseX, float baseY, float baseZ,
            TileGeometryRegistry.Primitive box, float[] light, int sideAxis) {
        for (boolean positive : new boolean[] {false, true}) {
            float x = positive ? box.maxX() : box.minX();
            float[][] local = positive
                    ? new float[][] {{x, box.minY(), box.minZ()}, {x, box.maxY(), box.minZ()},
                        {x, box.maxY(), box.maxZ()}, {x, box.minY(), box.maxZ()}}
                    : new float[][] {{x, box.minY(), box.minZ()}, {x, box.minY(), box.maxZ()},
                        {x, box.maxY(), box.maxZ()}, {x, box.maxY(), box.minZ()}};
            if (sideAxis == 2) {
                float z = positive ? box.maxZ() : box.minZ();
                local = positive
                        ? new float[][] {{box.minX(), box.minY(), z}, {box.maxX(), box.minY(), z},
                            {box.maxX(), box.maxY(), z}, {box.minX(), box.maxY(), z}}
                        : new float[][] {{box.minX(), box.minY(), z}, {box.minX(), box.maxY(), z},
                            {box.maxX(), box.maxY(), z}, {box.maxX(), box.minY(), z}};
            }
            float[][] transformed = transformedLocal(box, local);
            float[][] world = worldPoints(baseX, baseY, baseZ, transformed);
            float[] n = normal(world[0], world[1], world[2]);
            // Already-observed faces are emitted above. Never overlay a second copy.
            if (sourceFacing(n) >= -0.05f) continue;
            float[][] uv = new float[4][];
            for (int i = 0; i < 4; i++) {
                float donorX = sideAxis == 0 ? box.minX() + box.maxX() - local[i][0] : local[i][0];
                float donorZ = sideAxis == 2 ? box.minZ() + box.maxZ() - local[i][2] : local[i][2];
                float[] donor = transformLocal(box, donorX, local[i][1], donorZ);
                uv[i] = sourcePixel(donor);
            }
            addTexturedQuad(output, world[0], world[1], world[2], world[3], n, light, uv);
        }
    }

    private static void addCrateBottom(
            FloatBuilder output, float baseX, float baseY, float baseZ,
            TileGeometryRegistry.Primitive box, float[] light) {
        float[][] local = {{box.minX(), box.minY(), box.minZ()}, {box.maxX(), box.minY(), box.minZ()},
                {box.maxX(), box.minY(), box.maxZ()}, {box.minX(), box.minY(), box.maxZ()}};
        float[][] world = worldPoints(baseX, baseY, baseZ, transformedLocal(box, local));
        float[] normal = normal(world[0], world[1], world[2]);
        if (sourceFacing(normal) >= -0.05f) return;
        // Completion prior, not recovered ground truth: a plain wooden bottom.
        // Reuse a vertical panel rather than copying the metal-bound lid.
        float[][] uv = new float[4][];
        for (int i = 0; i < 4; i++) {
            float fraction = (local[i][2] - box.minZ()) / (box.maxZ() - box.minZ());
            uv[i] = sourcePixel(transformLocal(box, local[i][0],
                    box.minY() + fraction * (box.maxY() - box.minY()), box.maxZ()));
        }
        addTexturedQuad(output, world[0], world[1], world[2], world[3], normal, light, uv);
    }

    private static void addTexturedCylinder(
            FloatBuilder output,
            float baseX,
            float baseY,
            float baseZ,
            TileGeometryRegistry.Primitive cylinder,
            float[] light) {
        for (int segment = 0; segment < CYLINDER_SEGMENTS; segment++) {
            double a0 = Math.PI * 2.0 * segment / CYLINDER_SEGMENTS;
            double a1 = Math.PI * 2.0 * (segment + 1) / CYLINDER_SEGMENTS;
            float[][] local = {
                {(float) Math.cos(a0) * cylinder.radiusBottom(),
                        (float) Math.sin(a0) * cylinder.radiusBottom(), -cylinder.height() / 2},
                {(float) Math.cos(a1) * cylinder.radiusBottom(),
                        (float) Math.sin(a1) * cylinder.radiusBottom(), -cylinder.height() / 2},
                {(float) Math.cos(a1) * cylinder.radiusTop(),
                        (float) Math.sin(a1) * cylinder.radiusTop(), cylinder.height() / 2},
                {(float) Math.cos(a0) * cylinder.radiusTop(),
                        (float) Math.sin(a0) * cylinder.radiusTop(), cylinder.height() / 2},
                {0, 0, -cylinder.height() / 2},
                {0, 0, cylinder.height() / 2}
            };
            float[][] transformed = transformedLocal(cylinder, local);
            float[][] world = worldPoints(baseX, baseY, baseZ, transformed);
            addObservedTexturedQuad(output, world, transformed, new int[] {0, 1, 2, 3}, light);
            addObservedTexturedTriangle(output, world, transformed, new int[] {5, 3, 2}, light);
            // Either end can face the source after the authored rotation.
            addObservedTexturedTriangle(output, world, transformed, new int[] {4, 1, 0}, light);
        }
    }

    private void addTexturedPolygon(
            FloatBuilder output,
            float baseX,
            float baseY,
            float baseZ,
            TileGeometryRegistry.Primitive polygon,
            float[] light) {
        if (polygon.points().size() < 3) return;
        int[] triangles = polygonTriangles.computeIfAbsent(polygon,
                value -> PolygonTriangles.triangulate(value.points()));
        float[][] points = new float[polygon.points().size()][];
        for (int index = 0; index < points.length; index++) {
            float[] point = polygon.points().get(index);
            // Native Polygon.planeTo3D always starts on XY. The stored rotation already
            // contains plane orientation; mapping by plane again rotates it twice.
            points[index] = new float[] {point[0], point[1], 0};
        }
        float[][] transformed = transformedLocal(polygon, points);
        float[][] world = worldPoints(baseX, baseY, baseZ, transformed);
        float[] faceNormal = normal(world[triangles[0]], world[triangles[1]], world[triangles[2]]);
        boolean reverse = sourceFacing(faceNormal) < 0.0f;
        if (Math.abs(sourceFacing(faceNormal)) <= 0.05f) return;
        if (reverse) faceNormal = new float[] {-faceNormal[0], -faceNormal[1], -faceNormal[2]};
        for (int index = 0; index < triangles.length; index += 3) {
            int first = triangles[index];
            int second = triangles[index + (reverse ? 2 : 1)];
            int third = triangles[index + (reverse ? 1 : 2)];
            addTexturedTriangle(
                    output,
                    world[first], world[second], world[third], faceNormal, light,
                    sourcePixel(transformed[first]),
                    sourcePixel(transformed[second]),
                    sourcePixel(transformed[third]));
        }
    }

    private static void emitObservedFaces(
            FloatBuilder output,
            float baseX,
            float baseY,
            float baseZ,
            TileGeometryRegistry.Primitive primitive,
            float[][] points,
            int[][] faces,
            float[] light) {
        float[][] transformed = transformedLocal(primitive, points);
        float[][] world = worldPoints(baseX, baseY, baseZ, transformed);
        for (int[] face : faces) {
            addObservedTexturedQuad(output, world, transformed, face, light);
        }
    }

    private static void addObservedTexturedQuad(
            FloatBuilder output,
            float[][] world,
            float[][] transformed,
            int[] face,
            float[] light) {
        float[] faceNormal = normal(world[face[0]], world[face[1]], world[face[2]]);
        if (sourceFacing(faceNormal) <= 0.05f) return;
        addTexturedQuad(
                output,
                world[face[0]], world[face[1]], world[face[2]], world[face[3]],
                faceNormal,
                light,
                new float[][] {
                    sourcePixel(transformed[face[0]]),
                    sourcePixel(transformed[face[1]]),
                    sourcePixel(transformed[face[2]]),
                    sourcePixel(transformed[face[3]])
                });
    }

    private static void addObservedTexturedTriangle(
            FloatBuilder output,
            float[][] world,
            float[][] transformed,
            int[] face,
            float[] light) {
        float[] faceNormal = normal(world[face[0]], world[face[1]], world[face[2]]);
        if (sourceFacing(faceNormal) <= 0.05f) return;
        addTexturedTriangle(
                output,
                world[face[0]], world[face[1]], world[face[2]], faceNormal, light,
                sourcePixel(transformed[face[0]]),
                sourcePixel(transformed[face[1]]),
                sourcePixel(transformed[face[2]]));
    }

    private static float[][] transformedLocal(
            TileGeometryRegistry.Primitive primitive, float[][] points) {
        float[][] result = new float[points.length][];
        for (int index = 0; index < points.length; index++) {
            result[index] = transformLocal(
                    primitive, points[index][0], points[index][1], points[index][2]);
        }
        return result;
    }

    private static float[][] worldPoints(
            float baseX, float baseY, float baseZ, float[][] local) {
        float[][] result = new float[local.length][];
        for (int index = 0; index < local.length; index++) {
            result[index] = new float[] {
                baseX + 0.5f + local[index][0],
                baseY + local[index][1] * AUTHORED_HEIGHT_TO_WORLD,
                baseZ + 0.5f + local[index][2]
            };
        }
        return result;
    }

    private static float sourceFacing(float[] normal) {
        return normal[0] + normal[1] + normal[2];
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
            case "triangle" -> addSourceTriangle(
                    output, baseX, baseY, baseZ, primitive, color);
            default -> {
                // Unknown source primitives are deliberately omitted by the registry loader.
            }
        }
    }

    private static void addSourceTriangle(
            FloatBuilder output,
            float baseX,
            float baseY,
            float baseZ,
            TileGeometryRegistry.Primitive triangle,
            float[] color) {
        if (triangle.points().size() != 3) return;
        float[][] transformed = transformedLocal(
                triangle, triangle.points().toArray(float[][]::new));
        float[][] world = worldPoints(baseX, baseY, baseZ, transformed);
        addTriangle(output, world[0], world[1], world[2],
                normal(world[0], world[1], world[2]), color);
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
                    (float) Math.cos(a0) * cylinder.radiusBottom(),
                    (float) Math.sin(a0) * cylinder.radiusBottom(), -cylinder.height() / 2);
            float[] b1 = transform(baseX, baseY, baseZ, cylinder,
                    (float) Math.cos(a1) * cylinder.radiusBottom(),
                    (float) Math.sin(a1) * cylinder.radiusBottom(), -cylinder.height() / 2);
            float[] t0 = transform(baseX, baseY, baseZ, cylinder,
                    (float) Math.cos(a0) * cylinder.radiusTop(),
                    (float) Math.sin(a0) * cylinder.radiusTop(), cylinder.height() / 2);
            float[] t1 = transform(baseX, baseY, baseZ, cylinder,
                    (float) Math.cos(a1) * cylinder.radiusTop(),
                    (float) Math.sin(a1) * cylinder.radiusTop(), cylinder.height() / 2);
            addQuad(output, b0, b1, t1, t0, normal(b0, b1, t1), color);
            float[] bottom = transform(baseX, baseY, baseZ, cylinder, 0, 0, -cylinder.height() / 2);
            float[] top = transform(baseX, baseY, baseZ, cylinder, 0, 0, cylinder.height() / 2);
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
        int[] triangles = PolygonTriangles.triangulate(polygon.points());
        float[][] points = new float[polygon.points().size()][];
        for (int index = 0; index < points.length; index++) {
            float[] point = polygon.points().get(index);
            points[index] = transform(baseX, baseY, baseZ, polygon, point[0], point[1], 0);
        }
        float[] faceNormal = normal(points[triangles[0]], points[triangles[1]], points[triangles[2]]);
        for (int index = 0; index < triangles.length; index += 3) {
            addTriangle(output, points[triangles[index]], points[triangles[index+1]],
                    points[triangles[index+2]], faceNormal, color);
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
        float[] local = transformLocal(value, x, y, z);
        return new float[] {
            baseX + 0.5f + local[0],
            baseY + local[1] * AUTHORED_HEIGHT_TO_WORLD,
            baseZ + 0.5f + local[2]
        };
    }

    static float[] transformLocal(
            TileGeometryRegistry.Primitive value, float x, float y, float z) {
        double rx = Math.toRadians(value.rx());
        double ry = Math.toRadians(value.ry());
        double rz = Math.toRadians(value.rz());
        // Match PZ's JOML translation().rotateXYZ(): T * Rx * Ry * Rz.
        // Column-vector application order is Z, then Y, then X, not X/Y/Z.
        float x1 = (float) (x * Math.cos(rz) - y * Math.sin(rz));
        float y1 = (float) (x * Math.sin(rz) + y * Math.cos(rz));
        float x2 = (float) (x1 * Math.cos(ry) + z * Math.sin(ry));
        float z2 = (float) (-x1 * Math.sin(ry) + z * Math.cos(ry));
        float y3 = (float) (y1 * Math.cos(rx) - z2 * Math.sin(rx));
        float z3 = (float) (y1 * Math.sin(rx) + z2 * Math.cos(rx));
        return new float[] {
            value.tx() + x2,
            value.ty() + y3,
            value.tz() + z3
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

    private static void addTexturedQuad(
            FloatBuilder output,
            float[] a,
            float[] b,
            float[] c,
            float[] d,
            float[] normal,
            float[] color,
            float[][] sourcePixels) {
        addTexturedTriangle(
                output, a, b, c, normal, color,
                sourcePixels[0], sourcePixels[1], sourcePixels[2]);
        addTexturedTriangle(
                output, a, c, d, normal, color,
                sourcePixels[0], sourcePixels[2], sourcePixels[3]);
    }

    private static void addTexturedTriangle(
            FloatBuilder output,
            float[] a,
            float[] b,
            float[] c,
            float[] normal,
            float[] color,
            float[] sourceA,
            float[] sourceB,
            float[] sourceC) {
        addTexturedVertex(output, a, normal, color, sourceA);
        addTexturedVertex(output, b, normal, color, sourceB);
        addTexturedVertex(output, c, normal, color, sourceC);
    }

    private static void addTexturedVertex(
            FloatBuilder output, float[] p, float[] n, float[] c, float[] sourcePixel) {
        output.add(p[0]); output.add(p[1]); output.add(p[2]);
        output.add(n[0]); output.add(n[1]); output.add(n[2]);
        output.add(c[0]); output.add(c[1]); output.add(c[2]);
        output.add(sourcePixel[0]); output.add(sourcePixel[1]);
        output.add(output.layer);
        output.add(output.lightingIndex);
    }

    private static void addVertex(FloatBuilder output, float[] p, float[] n, float[] c) {
        output.add(p[0]); output.add(p[1]); output.add(p[2]);
        output.add(n[0]); output.add(n[1]); output.add(n[2]);
        output.add(c[0]); output.add(c[1]); output.add(c[2]);
        output.add(output.lightingIndex);
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
        private final int stride;
        private float layer;
        private int lightingIndex;

        FloatBuilder(int capacity, int stride) {
            values = new float[capacity];
            this.stride = stride;
        }

        void add(float value) {
            if (size == values.length) values = java.util.Arrays.copyOf(values, values.length * 2);
            values[size++] = value;
        }

        int vertexCount() {
            return size / stride;
        }

        float[] toArray() {
            return java.util.Arrays.copyOf(values, size);
        }
    }
}
