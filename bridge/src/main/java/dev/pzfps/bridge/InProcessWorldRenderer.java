package dev.pzfps.bridge;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import zombie.core.SpriteRenderer;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;

/**
 * Single-window replacement for IsoWorld.render(). PZ still owns frame setup, simulation,
 * world-space text, and UI; renderer-owned immutable meshes fill only the world slot.
 */
public final class InProcessWorldRenderer {
    private static final int MAX_PENDING_CHUNKS = 256;
    private static final int STRIDE_BYTES = WorldMeshBuilder.FLOATS_PER_VERTEX * Float.BYTES;
    private static final int TEXTURED_STRIDE_BYTES =
            WorldMeshBuilder.TEXTURED_FLOATS_PER_VERTEX * Float.BYTES;
    private static final float LEVEL_HEIGHT = 3.0f;
    private static final int RENDER_DISTANCE_CHUNKS =
            Math.max(1, Integer.getInteger("pzfps.renderDistanceChunks", 24));
    private static final float RENDER_DISTANCE =
            RENDER_DISTANCE_CHUNKS * zombie.iso.IsoChunkMap.CHUNK_SIZE_IN_SQUARES;
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final AtomicBoolean ASSETS_READY = new AtomicBoolean();
    private static final AtomicBoolean RENDER_FAILED = new AtomicBoolean();
    private static final AtomicBoolean REPLACEMENT_ANNOUNCED = new AtomicBoolean();
    private static final AtomicReference<WorldState.Player> PLAYER = new AtomicReference<>();
    private static final AtomicReference<WorldState.Entities> ENTITIES = new AtomicReference<>();
    private static final ChunkLighting.Store LIGHTING = new ChunkLighting.Store(4096);
    private static final ConcurrentHashMap<Long, WorldMeshBuilder.MeshData> MESHES =
            new ConcurrentHashMap<>();
    private static final ChunkQueue PENDING = new ChunkQueue(MAX_PENDING_CHUNKS);
    private static final AtomicLong BUILT_CHUNKS = new AtomicLong();
    private static final AtomicLong DROPPED_CHUNKS = new AtomicLong();
    private static final AtomicLong ENQUEUED_FRAMES = new AtomicLong();
    private static final AtomicLong COMPLETED_FRAMES = new AtomicLong();
    private static final boolean ENABLED = Boolean.getBoolean("pzfps.renderer.enabled");

    private static volatile GpuState gpuState;
    private static volatile CameraMatrices cameraMatrices;
    private static volatile FrustumIntersection cameraFrustum;
    private static volatile long lastReportNanos;
    private static volatile long lastReportCompleted;

    private InProcessWorldRenderer() {}

    public static void start(Path registryPath) {
        if (!ENABLED || !STARTED.compareAndSet(false, true)) return;
        Thread worker = new Thread(() -> meshWorker(registryPath), "PZFPS-mesh-builder");
        worker.setDaemon(true);
        worker.start();
    }

    public static void acceptPlayer(WorldState.Player player) {
        PLAYER.set(player);
    }

    public static void acceptEntities(WorldState.Entities entities) {
        ENTITIES.set(entities);
    }

    public static void submitChunk(WorldState.Chunk chunk) {
        if (!ENABLED) return;
        LIGHTING.put(chunk.key(), ChunkLighting.fromChunk(chunk));
        if (!PENDING.offer(chunk)) DROPPED_CHUNKS.incrementAndGet();
    }

    static void acceptLighting(long key, ChunkLighting lighting) {
        if (ENABLED) LIGHTING.put(key, lighting);
    }

    public static void removeChunk(long key) {
        PENDING.remove(key);
        MESHES.remove(key);
        LIGHTING.remove(key);
    }

    /** Called by advice on the game/render-state producer thread. */
    public static boolean replaceWorldDraw(Set<Integer> nativeEntityIds) {
        if (!ENABLED
                || !ASSETS_READY.get()
                || RENDER_FAILED.get()
                || PLAYER.get() == null
                || MESHES.isEmpty()) {
            return false;
        }
        RenderSnapshot snapshot = new RenderSnapshot(
                PLAYER.get(),
                ENTITIES.get(),
                List.copyOf(MESHES.values()),
                Set.copyOf(nativeEntityIds), LIGHTING.snapshot());
        SpriteRenderer.instance.drawGeneric(new WorldDrawer(snapshot));
        ENQUEUED_FRAMES.incrementAndGet();
        if (REPLACEMENT_ANNOUNCED.compareAndSet(false, true)) {
            System.out.println(
                    "[PZFPS] single-window first-person world renderer active; PZ text/UI passes retained");
        }
        return true;
    }

    public static boolean isReady() {
        return ENABLED && ASSETS_READY.get() && !RENDER_FAILED.get() && !MESHES.isEmpty();
    }

    static CameraMatrices currentCameraMatrices() {
        return cameraMatrices;
    }

    /**
     * Tests a native-model bound against the exact perspective frustum used by the most recently
     * queued replacement-world draw. Native model callbacks run after that draw on the same render
     * thread, so this is the final visibility decision rather than a producer-side isometric or
     * same-floor approximation.
     */
    static boolean currentViewIntersectsSphere(
            float worldX, float verticalY, float worldY, float radius) {
        FrustumIntersection frustum = cameraFrustum;
        return frustum != null && frustum.testSphere(worldX, verticalY, worldY, radius);
    }

    record CameraMatrices(Matrix4f projection, Matrix4f view) {
        CameraMatrices {
            projection = new Matrix4f(projection);
            view = new Matrix4f(view);
        }

        Matrix4f combined() {
            return new Matrix4f(projection).mul(view);
        }

        Matrix4fc projectionView() {
            return projection;
        }

        Matrix4fc worldView() {
            return view;
        }
    }

    /** Pure camera construction shared with tests; no OpenGL state is read here. */
    static CameraMatrices perspectiveCamera(
            WorldState.Player player, float eyeHeight, int viewportWidth, int viewportHeight) {
        int width = Math.max(1, viewportWidth);
        int height = Math.max(1, viewportHeight);
        PerspectiveViewRay.Ray ray = PerspectiveViewRay.fromPlayer(player, eyeHeight);
        float eyeX = ray.originX();
        float eyeY = ray.originY();
        float eyeZ = ray.originZ();
        float directionX = ray.directionX();
        float directionY = ray.directionY();
        float directionZ = ray.directionZ();
        if (Math.abs(directionX) + Math.abs(directionZ) < 0.001f) directionZ = 1.0f;
        Matrix4f projection = new Matrix4f().perspective(
                (float) Math.toRadians(82.0), (float) width / height, 0.035f, 400.0f);
        // Adding a unit direction to map coordinates ~10,000 quantizes small mouse
        // rotations before lookAt even runs. Keep direction independent of the origin.
        Matrix4f view = new Matrix4f().lookAlong(directionX, directionY, directionZ, 0, 1, 0)
                .translate(-eyeX, -eyeY, -eyeZ);
        return new CameraMatrices(projection, view);
    }

    static Matrix4f relativeMatrix(CameraMatrices camera, WorldState.Player player,
            float eyeHeight, float originX, float originZ) {
        PerspectiveViewRay.Ray ray = PerspectiveViewRay.fromPlayer(player, eyeHeight);
        Matrix4f view = new Matrix4f(camera.view()).m30(0).m31(0).m32(0)
                .translate(originX - ray.originX(), -ray.originY(), originZ - ray.originZ());
        return new Matrix4f(camera.projection()).mul(view);
    }

    static float[] relativeVertices(float[] source, int stride, float originX, float originZ) {
        float[] result = source.clone();
        for (int i = 0; i < result.length; i += stride) {
            result[i] -= originX;
            result[i + 2] -= originZ;
        }
        return result;
    }

    private static void meshWorker(Path registryPath) {
        try {
            TileGeometryRegistry registry = TileGeometryRegistry.load(registryPath);
            WorldMeshBuilder builder = new WorldMeshBuilder(registry);
            ASSETS_READY.set(true);
            System.out.printf(
                    "[PZFPS] in-process geometry ready tiles=%d sha256=%s queue=%d%n",
                    registry.tileCount(), registry.sourceSha256(), MAX_PENDING_CHUNKS);
            while (!Thread.currentThread().isInterrupted()) {
                WorldState.Chunk chunk = PENDING.take();
                MESHES.put(chunk.key(), builder.build(chunk));
                BUILT_CHUNKS.incrementAndGet();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException error) {
            RENDER_FAILED.set(true);
            System.err.printf("[PZFPS] in-process renderer asset failure: %s%n", error);
        }
    }

    private static void render(RenderSnapshot snapshot) {
        try {
            GpuState state = gpuState;
            if (state == null) {
                state = new GpuState();
                gpuState = state;
            }
            state.render(snapshot);
            long completed = COMPLETED_FRAMES.incrementAndGet();
            if (completed % 300 == 0) {
                long reportNanos = System.nanoTime();
                long previousNanos = lastReportNanos;
                long previousCompleted = lastReportCompleted;
                lastReportNanos = reportNanos;
                lastReportCompleted = completed;
                double completedHz = previousNanos == 0L
                        ? Double.NaN
                        : (completed - previousCompleted)
                                * 1_000_000_000.0
                                / Math.max(1L, reportNanos - previousNanos);
                long stateAge = Math.max(
                        0L, System.currentTimeMillis() - snapshot.player.captureEpochMillis());
                CullingCounts culling = state.lastCulling;
                WorldMeshBuilder.Coverage coverage = state.lastCoverage;
                System.out.printf(
                        "[PZFPS renderer] completedFrames=%d enqueuedFrames=%d completedCallbackHz=%s meshes=%d visible=%d empty=%d distanceCulled=%d frustumCulled=%d built=%d dropped=%d stateAgeMs=%d sourceFloors=%d flatFloors=%d stairOpenings=%d indexedObjects=%d structuralFallbacks=%d mirroredStructuralFaces=%d completedInteriorCeilings=%d nativeItems=%d unsupportedObjects=%d collisionHoles=%d truncatedChunks=%d%n",
                        completed,
                        ENQUEUED_FRAMES.get(),
                        Double.isFinite(completedHz)
                                ? String.format(java.util.Locale.ROOT, "%.2f", completedHz)
                                : "unavailable",
                        snapshot.meshes.size(),
                        culling.visible(),
                        culling.empty(),
                        culling.distance(),
                        culling.frustum(),
                        BUILT_CHUNKS.get(),
                        DROPPED_CHUNKS.get(),
                        stateAge,
                        coverage.sourceTexturedFloors(),
                        coverage.flatFallbackFloors(),
                        coverage.stairFloorOpenings(),
                        coverage.authoredGeometryObjects(),
                        coverage.structuralFallbackObjects(),
                        coverage.mirroredStructuralFaces(),
                        coverage.completedInteriorCeilings(),
                        coverage.nativeWorldItems(),
                        coverage.unsupportedObjects(),
                        coverage.collisionCriticalUnsupportedObjects(),
                        coverage.truncatedChunks());
                System.out.printf("[PZFPS lighting] textures=%d uploads=%d visibleMaxAgeMs=%d bytesPerUpload=%d%n",
                        state.lightingTextures.size(), state.lightingUploads,
                        state.visibleLightAgeMillis, ChunkLighting.BYTES);
                List<SpriteCount> unsupported =
                        topUnsupportedSprites(state.lastVisibleMeshes, 8);
                if (!unsupported.isEmpty()) {
                    System.out.printf(
                            "[PZFPS coverage] topUnsupported=%s%n",
                            unsupported.stream()
                                    .map(value -> value.sprite() + ":" + value.count())
                                    .collect(java.util.stream.Collectors.joining(",")));
                }
                List<SpriteCount> collisionHoles =
                        topUnsupportedCollisionSprites(state.lastVisibleMeshes, 8);
                if (!collisionHoles.isEmpty()) {
                    System.out.printf(
                            "[PZFPS coverage] topCollisionHoles=%s%n",
                            collisionHoles.stream()
                                    .map(value -> value.sprite() + ":" + value.count())
                                    .collect(java.util.stream.Collectors.joining(",")));
                }
            }
        } catch (Throwable error) {
            if (RENDER_FAILED.compareAndSet(false, true)) {
                System.err.printf(
                        "[PZFPS] replacement render failed; restoring vanilla world next frame: %s%n",
                        error);
                error.printStackTrace(System.err);
            }
        }
    }

    private record RenderSnapshot(
            WorldState.Player player,
            WorldState.Entities entities,
            List<WorldMeshBuilder.MeshData> meshes,
            Set<Integer> nativeEntityIds,
            Map<Long, ChunkLighting> lighting) {}

    private record CullingCounts(int visible, int empty, int distance, int frustum) {
        private static CullingCounts none() {
            return new CullingCounts(0, 0, 0, 0);
        }
    }

    record SpriteCount(String sprite, int count) {}

    static List<SpriteCount> topUnsupportedSprites(
            List<WorldMeshBuilder.MeshData> visibleMeshes, int limit) {
        return topSpriteCounts(visibleMeshes, limit, false);
    }

    static List<SpriteCount> topUnsupportedCollisionSprites(
            List<WorldMeshBuilder.MeshData> visibleMeshes, int limit) {
        return topSpriteCounts(visibleMeshes, limit, true);
    }

    private static List<SpriteCount> topSpriteCounts(
            List<WorldMeshBuilder.MeshData> visibleMeshes,
            int limit,
            boolean collisionOnly) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        Map<String, Integer> counts = new HashMap<>();
        for (WorldMeshBuilder.MeshData mesh : visibleMeshes) {
            Map<String, Integer> source = collisionOnly
                    ? mesh.unsupportedCollisionSprites()
                    : mesh.unsupportedSprites();
            for (Map.Entry<String, Integer> entry : source.entrySet()) {
                counts.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .map(entry -> new SpriteCount(entry.getKey(), entry.getValue()))
                .sorted(java.util.Comparator.comparingInt(SpriteCount::count)
                        .reversed()
                        .thenComparing(SpriteCount::sprite))
                .limit(limit)
                .toList();
    }

    private static final class WorldDrawer extends TextureDraw.GenericDrawer {
        private final RenderSnapshot snapshot;

        WorldDrawer(RenderSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public void render() {
            InProcessWorldRenderer.render(snapshot);
        }
    }

    /** Render-thread-owned OpenGL resources. */
    private static final class GpuState {
        private final Map<Long, GpuMesh> meshes = new HashMap<>();
        private final int program;
        private final int mvpUniform;
        private final int texturedUniform;
        private final int materialUniform;
        private final int textureUniform;
        private final int uvBoundsUniform;
        private final int cropUniform;
        private final int surfaceKindUniform;
        private final int projectedBoundsUniform;
        private final int originUniform;
        private final int lightingUniform;
        private final int lightingEnabledUniform;
        private final int depthUnitUniform;
        private int reportedDepthBits = -1;
        private final Map<Long, GpuLighting> lightingTextures = new HashMap<>();
        private final java.nio.ByteBuffer lightingUpload = BufferUtils.createByteBuffer(ChunkLighting.BYTES);
        private long lightingUploads;
        private long visibleLightAgeMillis;
        private final Map<String, Texture> sourceTextures = new HashMap<>();
        private final Set<String> reportedMissingTextures = ConcurrentHashMap.newKeySet();
        private boolean entityBufferCreated;
        private int entityVbo;
        private volatile CullingCounts lastCulling = CullingCounts.none();
        private volatile WorldMeshBuilder.Coverage lastCoverage =
                WorldMeshBuilder.Coverage.none();
        private volatile List<WorldMeshBuilder.MeshData> lastVisibleMeshes = List.of();
        private float renderedEyeHeight = Float.NaN;
        private long lastEyeHeightNanos;

        GpuState() {
            program = createProgram();
            mvpUniform = GL20.glGetUniformLocation(program, "uMvp");
            texturedUniform = GL20.glGetUniformLocation(program, "uTextured");
            materialUniform = GL20.glGetUniformLocation(program, "uMaterial");
            textureUniform = GL20.glGetUniformLocation(program, "uTexture");
            uvBoundsUniform = GL20.glGetUniformLocation(program, "uUvBounds");
            cropUniform = GL20.glGetUniformLocation(program, "uCrop");
            surfaceKindUniform = GL20.glGetUniformLocation(program, "uSurfaceKind");
            projectedBoundsUniform = GL20.glGetUniformLocation(program, "uProjectedBounds");
            originUniform = GL20.glGetUniformLocation(program, "uOrigin");
            lightingUniform = GL20.glGetUniformLocation(program, "uLighting");
            lightingEnabledUniform = GL20.glGetUniformLocation(program, "uLightingEnabled");
            depthUnitUniform = GL20.glGetUniformLocation(program, "uDepthUnit");
            if (mvpUniform < 0
                    || texturedUniform < 0
                    || materialUniform < 0
                    || textureUniform < 0
                    || uvBoundsUniform < 0
                    || cropUniform < 0 || surfaceKindUniform < 0 || projectedBoundsUniform < 0 || originUniform < 0
                    || lightingUniform < 0 || lightingEnabledUniform < 0 || depthUnitUniform < 0) {
                throw new IllegalStateException("one or more source-texture shader uniforms are absent");
            }
        }

        void render(RenderSnapshot snapshot) {
            int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            int previousArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            boolean previousDepth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            boolean previousBlend = GL11.glIsEnabled(GL11.GL_BLEND);
            int previousBlendSource = GL11.glGetInteger(GL11.GL_BLEND_SRC);
            int previousBlendDestination = GL11.glGetInteger(GL11.GL_BLEND_DST);
            boolean previousCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
            int previousCullFace = GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);
            int previousFrontFace = GL11.glGetInteger(GL11.GL_FRONT_FACE);
            boolean previousDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            int previousDepthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
            boolean previousPolygonOffset = GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL);
            boolean previousAlphaTest = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
            java.nio.DoubleBuffer previousDepthRange = BufferUtils.createDoubleBuffer(2);
            GL11.glGetDoublev(GL11.GL_DEPTH_RANGE, previousDepthRange);
            int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            int previousLightingTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            boolean previousTexture2d = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
            int previousUnpackBuffer = GL11.glGetInteger(org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
            int previousUnpackAlignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
            int previousUnpackRowLength = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
            int previousUnpackSkipRows = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS);
            int previousUnpackSkipPixels = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS);
            GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
            try {
                GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER, 0);
                GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
                GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
                GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
                GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
                GL11.glClearColor(0.025f, 0.03f, 0.04f, 1.0f);
                GL11.glClearDepth(1.0);
                GL11.glDepthMask(true);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
                GL11.glEnable(GL11.GL_DEPTH_TEST);
                GL11.glDepthFunc(GL11.GL_LEQUAL);
                GL11.glDepthRange(0, 1);
                GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
                GL11.glDisable(GL11.GL_ALPHA_TEST);
                GL11.glDisable(GL11.GL_BLEND);
                GL11.glEnable(GL11.GL_CULL_FACE);
                GL11.glCullFace(GL11.GL_BACK);
                GL11.glFrontFace(GL11.GL_CCW);
                GL20.glUseProgram(program);
                GL20.glUniform1i(textureUniform, 0);
                GL20.glUniform1i(lightingUniform, 1);
                int depthBits = GL11.glGetInteger(GL11.GL_DEPTH_BITS);
                if (depthBits != reportedDepthBits) {
                    System.out.printf("[PZFPS depth] bits=%d ordering=fragment layers=16 unitsPerLayer=2%n", depthBits);
                    reportedDepthBits = depthBits;
                }
                GL20.glUniform1f(depthUnitUniform, depthBits > 0
                        ? (float) (1.0 / (Math.pow(2, Math.min(depthBits, 24)) - 1.0)) : 0);

                CameraMatrices camera = cameraMatrices(
                        snapshot.player, smoothEyeHeight(snapshot.player));
                cameraMatrices = camera;
                Matrix4f matrix = camera.combined();
                FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);
                matrix.get(matrixBuffer);
                GL20.glUniformMatrix4fv(mvpUniform, false, matrixBuffer);
                FrustumIntersection frustum = new FrustumIntersection(matrix);
                cameraFrustum = frustum;
                synchronizeMeshes(snapshot.meshes);
                Iterator<Map.Entry<Long, GpuLighting>> lightIterator = lightingTextures.entrySet().iterator();
                while (lightIterator.hasNext()) {
                    var entry = lightIterator.next();
                    if (snapshot.lighting.containsKey(entry.getKey())) continue;
                    GL11.glDeleteTextures(entry.getValue().texture);
                    lightIterator.remove();
                }
                VisibleMeshes culled = visibleMeshes(snapshot, frustum);
                lastCulling = culled.counts();
                List<WorldMeshBuilder.MeshData> visible = culled.meshes();
                WorldMeshBuilder.Coverage visibleCoverage = WorldMeshBuilder.Coverage.none();
                for (WorldMeshBuilder.MeshData source : visible) {
                    visibleCoverage = visibleCoverage.plus(source.coverage());
                }
                lastCoverage = visibleCoverage;
                lastVisibleMeshes = List.copyOf(visible);
                visible.sort(java.util.Comparator.comparingDouble(
                        source -> distanceSquared(source, snapshot.player)));
                visibleLightAgeMillis = -1;
                for (WorldMeshBuilder.MeshData source : visible) {
                    GpuMesh mesh = meshes.get(source.key());
                    if (mesh == null) continue;
                    ChunkLighting light = snapshot.lighting.get(source.key());
                    bindLighting(source.key(), light);
                    if (light != null) visibleLightAgeMillis = Math.max(visibleLightAgeMillis,
                            Math.max(0, System.nanoTime() - light.capturedNanos) / 1_000_000L);
                    matrixBuffer.clear();
                    relativeMatrix(camera, snapshot.player, renderedEyeHeight, mesh.originX, mesh.originZ)
                            .get(matrixBuffer);
                    GL20.glUniformMatrix4fv(mvpUniform, false, matrixBuffer);
                    GL20.glUniform3f(originUniform, mesh.originX, 0, mesh.originZ);
                    if (mesh.vertexCount > 0) {
                        GL20.glUniform1i(texturedUniform, 0);
                        GL20.glUniform1i(materialUniform, 0);
                        GL11.glDisable(GL11.GL_BLEND);
                        GL11.glDisable(GL11.GL_TEXTURE_2D);
                        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, mesh.vbo);
                        configureAttributes(STRIDE_BYTES, false);
                        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, mesh.vertexCount);
                    }
                    for (GpuMaterialBatch batch : mesh.materialBatches) {
                        drawMaterialBatch(batch);
                    }
                    // Reversible two-sided source-surface fallback, not a duplicate shell or
                    // generated backside. Stateful alpha openings remain in the source texture.
                    GL11.glDisable(GL11.GL_CULL_FACE);
                    for (GpuTexturedBatch batch : mesh.texturedBatches) {
                        drawTexturedBatch(batch);
                    }
                    GL11.glEnable(GL11.GL_CULL_FACE);
                }
                GL20.glUniform1i(texturedUniform, 0);
                GL20.glUniform1i(materialUniform, 0);
                matrixBuffer.clear();
                matrix.get(matrixBuffer);
                GL20.glUniformMatrix4fv(mvpUniform, false, matrixBuffer);
                GL20.glUniform3f(originUniform, 0, 0, 0);
                GL20.glUniform1i(lightingEnabledUniform, 0);
                GL11.glDisable(GL11.GL_BLEND);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                drawEntities(snapshot.entities, snapshot.nativeEntityIds);
            } finally {
                GL11.glPopClientAttrib();
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousArrayBuffer);
                GL20.glUseProgram(previousProgram);
                setEnabled(GL11.GL_DEPTH_TEST, previousDepth);
                GL11.glBlendFunc(previousBlendSource, previousBlendDestination);
                setEnabled(GL11.GL_BLEND, previousBlend);
                GL11.glCullFace(previousCullFace);
                GL11.glFrontFace(previousFrontFace);
                setEnabled(GL11.GL_CULL_FACE, previousCull);
                GL11.glDepthMask(previousDepthMask);
                GL11.glDepthFunc(previousDepthFunction);
                GL11.glDepthRange(previousDepthRange.get(0), previousDepthRange.get(1));
                setEnabled(GL11.GL_POLYGON_OFFSET_FILL, previousPolygonOffset);
                setEnabled(GL11.GL_ALPHA_TEST, previousAlphaTest);
                GL13.glActiveTexture(GL13.GL_TEXTURE1);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousLightingTexture);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
                setEnabled(GL11.GL_TEXTURE_2D, previousTexture2d);
                GL13.glActiveTexture(previousActiveTexture);
                GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER, previousUnpackBuffer);
                GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, previousUnpackAlignment);
                GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, previousUnpackRowLength);
                GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, previousUnpackSkipRows);
                GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, previousUnpackSkipPixels);
            }
        }

        private record GpuLighting(int texture, ChunkLighting source) {}

        private void bindLighting(long key, ChunkLighting source) {
            GL20.glUniform1i(lightingEnabledUniform, source == null ? 0 : 1);
            if (source == null) return;
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GpuLighting current = lightingTextures.get(key);
            int texture = current == null ? GL11.glGenTextures() : current.texture;
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            if (current == null || !source.sameContent(current.source)) {
                lightingUpload.clear();
                source.writeTo(lightingUpload);
                lightingUpload.flip();
                if (current == null) {
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
                    GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8,
                            ChunkLighting.WIDTH, ChunkLighting.HEIGHT, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, lightingUpload);
                } else {
                    GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0,
                            ChunkLighting.WIDTH, ChunkLighting.HEIGHT, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, lightingUpload);
                }
                lightingUploads++;
            }
            if (current == null || current.source != source)
                lightingTextures.put(key, new GpuLighting(texture, source));
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
        }

        private void drawTexturedBatch(GpuTexturedBatch batch) {
            Texture texture = sourceTextures.get(batch.sprite);
            if (texture == null || texture.getID() == 0) {
                texture = Texture.getSharedTexture(batch.sprite);
                if (texture != null && texture.getID() != 0) {
                    sourceTextures.put(batch.sprite, texture);
                    System.out.printf(
                            "[PZFPS texture] resolved sprite=%s crop=%dx%d original=%dx%d%n",
                            batch.sprite,
                            texture.getWidth(),
                            texture.getHeight(),
                            texture.getWidthOrig(),
                            texture.getHeightOrig());
                }
            }
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, batch.vbo);
            configureAttributes(TEXTURED_STRIDE_BYTES, true);
            GL20.glUniform1i(materialUniform, 0);
            GL20.glUniform1i(surfaceKindUniform, batch.solidFloor ? 1 : batch.wallEdges ? 2
                    : BoxSideCompletion.closedCrate(batch.sprite) ? 3 : 0);
            GL20.glUniform4f(projectedBoundsUniform, batch.projectedBounds[0], batch.projectedBounds[1],
                    batch.projectedBounds[2], batch.projectedBounds[3]);
            if (texture == null || texture.getID() == 0) {
                GL20.glUniform1i(texturedUniform, 0);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                if (reportedMissingTextures.add(batch.sprite)) {
                    System.err.printf("[PZFPS texture] unavailable sprite=%s%n", batch.sprite);
                }
            } else {
                GL11.glEnable(GL11.GL_TEXTURE_2D);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture.getID());
                GL20.glUniform1i(texturedUniform, 1);
                GL20.glUniform4f(
                        uvBoundsUniform,
                        texture.getXStart(),
                        texture.getYStart(),
                        texture.getXEnd(),
                        texture.getYEnd());
                GL20.glUniform4f(
                        cropUniform,
                        texture.getOffsetX(),
                        texture.getOffsetY(),
                        Math.max(1, texture.getWidth()),
                        Math.max(1, texture.getHeight()));
            }
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, batch.vertexCount);
        }

        private void drawMaterialBatch(GpuMaterialBatch batch) {
            GL20.glUniform1i(texturedUniform, 0);
            GL20.glUniform1i(materialUniform, materialId(batch.material));
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, batch.vbo);
            configureAttributes(STRIDE_BYTES, false);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, batch.vertexCount);
        }

        private static int materialId(String material) {
            return "interior-plaster".equals(material) ? 1 : 0;
        }

        private VisibleMeshes visibleMeshes(
                RenderSnapshot snapshot, FrustumIntersection frustum) {
            ArrayList<WorldMeshBuilder.MeshData> visible = new ArrayList<>();
            int empty = 0;
            int distanceCulled = 0;
            int frustumCulled = 0;
            float maximumDistanceSquared = RENDER_DISTANCE * RENDER_DISTANCE;
            for (WorldMeshBuilder.MeshData source : snapshot.meshes) {
                if (distanceSquared(source, snapshot.player) > maximumDistanceSquared) {
                    distanceCulled++;
                    continue;
                }
                if (!frustum.testAab(
                        source.minX(), source.minY(), source.minZ(),
                        source.maxX(), source.maxY(), source.maxZ())) {
                    frustumCulled++;
                    continue;
                }
                if (source.vertexCount() == 0) empty++;
                visible.add(source);
            }
            return new VisibleMeshes(
                    visible,
                    new CullingCounts(
                            visible.size() - empty, empty, distanceCulled, frustumCulled));
        }

        private record VisibleMeshes(
                List<WorldMeshBuilder.MeshData> meshes, CullingCounts counts) {}

        private static double distanceSquared(
                WorldMeshBuilder.MeshData source, WorldState.Player player) {
            double centerX = (source.minX() + source.maxX()) * 0.5;
            double centerZ = (source.minZ() + source.maxZ()) * 0.5;
            double dx = centerX - player.x();
            double dz = centerZ - player.y();
            return dx * dx + dz * dz;
        }

        private void synchronizeMeshes(List<WorldMeshBuilder.MeshData> sourceMeshes) {
            Set<Long> live = ConcurrentHashMap.newKeySet(sourceMeshes.size());
            for (WorldMeshBuilder.MeshData source : sourceMeshes) {
                live.add(source.key());
                GpuMesh current = meshes.get(source.key());
                if (current != null && current.fingerprint == source.fingerprint()) continue;
                if (current != null) current.destroy();
                meshes.put(source.key(), upload(source));
            }
            Iterator<Map.Entry<Long, GpuMesh>> iterator = meshes.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Long, GpuMesh> entry = iterator.next();
                if (live.contains(entry.getKey())) continue;
                entry.getValue().destroy();
                iterator.remove();
            }
        }

        private static GpuMesh upload(WorldMeshBuilder.MeshData source) {
            float originX = (int) (source.key() >> 32) * zombie.iso.IsoChunkMap.CHUNK_SIZE_IN_SQUARES;
            float originZ = (int) source.key() * zombie.iso.IsoChunkMap.CHUNK_SIZE_IN_SQUARES;
            int vbo = 0;
            int vertexCount = source.vertices().length / WorldMeshBuilder.FLOATS_PER_VERTEX;
            if (vertexCount > 0) {
                vbo = GL15.glGenBuffers();
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
                FloatBuffer vertices = BufferUtils.createFloatBuffer(source.vertices().length);
                vertices.put(relativeVertices(source.vertices(), WorldMeshBuilder.FLOATS_PER_VERTEX,
                        originX, originZ)).flip();
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STATIC_DRAW);
            }
            ArrayList<GpuTexturedBatch> textured = new ArrayList<>();
            for (WorldMeshBuilder.TexturedBatch sourceBatch : source.texturedBatches()) {
                int texturedVbo = GL15.glGenBuffers();
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, texturedVbo);
                FloatBuffer vertices = BufferUtils.createFloatBuffer(sourceBatch.vertices().length);
                vertices.put(relativeVertices(sourceBatch.vertices(), WorldMeshBuilder.TEXTURED_FLOATS_PER_VERTEX,
                        originX, originZ)).flip();
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STATIC_DRAW);
                textured.add(new GpuTexturedBatch(
                        sourceBatch.sprite(), texturedVbo, sourceBatch.vertexCount(),
                        sourceBatch.solidFloor(), sourceBatch.wallEdges(),
                        BoxSideCompletion.closedCrate(sourceBatch.sprite()) && sourceBatch.vertices().length > 0
                                ? BoxSideCompletion.projectedBounds(sourceBatch.vertices()) : new float[] {0, 0, 1, 1}));
            }
            ArrayList<GpuMaterialBatch> materials = new ArrayList<>();
            for (WorldMeshBuilder.MaterialBatch sourceBatch : source.materialBatches()) {
                int materialVbo = GL15.glGenBuffers();
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, materialVbo);
                FloatBuffer vertices = BufferUtils.createFloatBuffer(sourceBatch.vertices().length);
                vertices.put(relativeVertices(sourceBatch.vertices(), WorldMeshBuilder.FLOATS_PER_VERTEX,
                        originX, originZ)).flip();
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STATIC_DRAW);
                materials.add(new GpuMaterialBatch(
                        sourceBatch.material(), materialVbo, sourceBatch.vertexCount()));
            }
            return new GpuMesh(
                    source.fingerprint(),
                    originX, originZ,
                    vbo,
                    vertexCount,
                    List.copyOf(textured),
                    List.copyOf(materials));
        }

        private void drawEntities(WorldState.Entities entities, Set<Integer> nativeActorIds) {
            if (entities == null || entities.values().isEmpty()) return;
            float[] vertices = entityVertices(entities.values(), nativeActorIds);
            if (vertices.length == 0) return;
            if (!entityBufferCreated) {
                entityBufferCreated = true;
                entityVbo = GL15.glGenBuffers();
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, entityVbo);
                configureAttributes(STRIDE_BYTES, false);
            } else {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, entityVbo);
                configureAttributes(STRIDE_BYTES, false);
            }
            FloatBuffer buffer = BufferUtils.createFloatBuffer(vertices.length);
            buffer.put(vertices).flip();
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buffer, GL15.GL_STREAM_DRAW);
            GL11.glDrawArrays(
                    GL11.GL_TRIANGLES, 0, vertices.length / WorldMeshBuilder.FLOATS_PER_VERTEX);
        }

        private static void configureAttributes(int strideBytes, boolean textured) {
            GL20.glEnableVertexAttribArray(5);
            GL20.glVertexAttribPointer(5, 1, GL11.GL_FLOAT, false, strideBytes,
                    (textured ? 12L : 9L) * Float.BYTES);
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, strideBytes, 0L);
            GL20.glEnableVertexAttribArray(1);
            GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, strideBytes, 3L * Float.BYTES);
            GL20.glEnableVertexAttribArray(2);
            GL20.glVertexAttribPointer(2, 3, GL11.GL_FLOAT, false, strideBytes, 6L * Float.BYTES);
            if (textured) {
                GL20.glEnableVertexAttribArray(3);
                GL20.glVertexAttribPointer(
                        3, 2, GL11.GL_FLOAT, false, strideBytes, 9L * Float.BYTES);
                GL20.glEnableVertexAttribArray(4);
                GL20.glVertexAttribPointer(4, 1, GL11.GL_FLOAT, false, strideBytes, 11L * Float.BYTES);
            } else {
                GL20.glDisableVertexAttribArray(3);
                GL20.glVertexAttrib2f(3, 0.0f, 0.0f);
                GL20.glDisableVertexAttribArray(4);
                GL20.glVertexAttrib1f(4, 0.0f);
            }
        }

        private float smoothEyeHeight(WorldState.Player player) {
            float target = Math.max(0.35f, Math.min(2.0f, player.eyeHeight()));
            long now = System.nanoTime();
            if (!Float.isFinite(renderedEyeHeight) || lastEyeHeightNanos == 0L) {
                renderedEyeHeight = target;
            } else {
                float seconds = Math.min(0.1f, (now - lastEyeHeightNanos) / 1_000_000_000.0f);
                float blend = 1.0f - (float) Math.exp(-14.0f * seconds);
                renderedEyeHeight += (target - renderedEyeHeight) * blend;
            }
            lastEyeHeightNanos = now;
            return renderedEyeHeight;
        }

        private static CameraMatrices cameraMatrices(WorldState.Player player, float eyeHeight) {
            IntBuffer viewport = BufferUtils.createIntBuffer(4);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            return perspectiveCamera(player, eyeHeight, viewport.get(2), viewport.get(3));
        }

        private static int createProgram() {
            String vertex = """
                    #version 120
                    attribute vec3 inPosition;
                    attribute vec3 inNormal;
                    attribute vec3 inColor;
                    attribute vec2 inSourcePixel;
                    attribute float inLayer;
                    attribute float inLightingIndex;
                    uniform mat4 uMvp;
                    uniform vec3 uOrigin;
                    uniform int uTextured;
                    uniform int uMaterial;
                    varying vec3 vertexColor;
                    varying vec2 sourcePixel;
                    varying vec3 worldPosition;
                    varying vec3 surfaceNormal;
                    varying vec2 lightingUv;
                    varying float surfaceLayer;
                    void main() {
                        vec3 sun = normalize(vec3(-0.45, 0.82, -0.35));
                        float light = uMaterial == 1
                                ? 1.0
                                : 0.42 + 0.58 * max(dot(normalize(inNormal), sun), 0.0);
                        vertexColor = uTextured == 1 ? inColor : inColor * light;
                        sourcePixel = inSourcePixel;
                        worldPosition = inPosition + uOrigin;
                        surfaceNormal = inNormal;
                        lightingUv = vec2(mod(inLightingIndex, 8.0) + 0.5,
                                floor(inLightingIndex / 8.0) + 0.5) / vec2(8.0, 512.0);
                        gl_Position = uMvp * vec4(inPosition, 1.0);
                        surfaceLayer = uTextured == 1 ? clamp(inLayer, 0.0, 16.0) : 0.0;
                    }
                    """;
            String fragment = """
                    #version 120
                    uniform int uTextured;
                    uniform int uMaterial;
                    uniform sampler2D uTexture;
                    uniform vec4 uUvBounds;
                    uniform vec4 uCrop;
                    uniform int uSurfaceKind;
                    uniform vec4 uProjectedBounds;
                    uniform sampler2D uLighting;
                    uniform int uLightingEnabled;
                    uniform float uDepthUnit;
                    varying vec3 vertexColor;
                    varying vec2 sourcePixel;
                    varying vec3 worldPosition;
                    varying vec3 surfaceNormal;
                    varying vec2 lightingUv;
                    varying float surfaceLayer;
                    vec4 sampleSprite(vec2 pixel) {
                        vec2 lo = uCrop.xy + vec2(0.5);
                        vec2 hi = uCrop.xy + uCrop.zw - vec2(0.5);
                        vec2 cropUv = (clamp(pixel, lo, hi) - uCrop.xy) / uCrop.zw;
                        return texture2D(uTexture, mix(uUvBounds.xy, uUvBounds.zw, cropUv));
                    }
                    void main() {
                        // Apply ordering at the fragment's actual depth. Applying the
                        // nonlinear correction at vertices warps the depth plane and
                        // makes differently tessellated coplanar surfaces intersect.
                        float distance = 1.0 / gl_FragCoord.w;
                        float bias = surfaceLayer * 0.0005;
                        float metricOffset = (0.035 * 400.0 / (400.0 - 0.035))
                                * bias / max(0.001225, distance * (distance - bias));
                        // Sub-millimetre ordering eventually falls below depth-buffer
                        // resolution. Reserve two units per source layer where possible,
                        // but never expand the total eye-depth displacement beyond 8mm.
                        // At long range precision can still defeat ordering; do not pull
                        // props through walls to conceal that limitation.
                        float maximumOffset = (0.035 * 400.0 / (400.0 - 0.035))
                                * 0.008 / max(0.001225, distance * (distance - 0.008));
                        gl_FragDepth = max(0.0, gl_FragCoord.z
                                - min(maximumOffset, max(metricOffset, surfaceLayer * 2.0 * uDepthUnit)));
                        // Preserve the preceding diagnostic exposure floor for this transport
                        // change. No seen-state/darkMulti multiplier; physical calibration pending.
                        vec3 liveLight = vec3(1.0);
                        if (uLightingEnabled == 1) {
                            vec4 sampleLight = texture2D(uLighting, lightingUv);
                            liveLight = sampleLight.a > 0.5 ? max(vec3(0.42), sampleLight.rgb) : vec3(0.42);
                        }
                        if (uTextured == 1) {
                            vec2 samplePixel = sourcePixel;
                            if (uSurfaceKind == 3) {
                                // Verified whole wooden crates: fit the authored projection to
                                // the actual cropped artwork, not a nominal 128px tile rectangle.
                                vec2 fraction = (sourcePixel - uProjectedBounds.xy) / uProjectedBounds.zw;
                                samplePixel = uCrop.xy + vec2(0.5) + fraction * (uCrop.zw - vec2(1.0));
                            }
                            vec2 cropUv = (samplePixel - uCrop.xy) / uCrop.zw;
                            bool outside = any(lessThan(cropUv, vec2(0.0))) || any(greaterThan(cropUv, vec2(1.0)));
                            vec4 source = outside ? vec4(0.0) : sampleSprite(samplePixel);
                            if (uSurfaceKind == 3 && source.a < 0.999) {
                                // The crate is a closed solid. Raster trim/bevel gaps must not
                                // perforate it. This small edge extension is family-specific;
                                // never apply it to open shelves, chairs, windows or vegetation.
                                for (int dy = -4; dy <= 4; dy++) {
                                    for (int dx = -4; dx <= 4; dx++) {
                                        vec4 candidate = sampleSprite(samplePixel + vec2(float(dx), float(dy)));
                                        if (candidate.a > source.a) source = candidate;
                                    }
                                }
                                if (source.a < 0.5) source = sampleSprite(uCrop.xy + uCrop.zw * 0.5);
                                source.a = 1.0;
                            }
                            // Known solid floor diamonds have raster-trimmed/antialiased edges
                            // (e.g. 126x64 stored pixels for a 128x64 footprint). Extend only a
                            // two-pixel boundary band from its own opaque neighbours; never
                            // bridge stairwells, alter geometry, or fill window/prop alpha holes.
                            vec2 iso = (sourcePixel - vec2(64.0, 224.0)) / vec2(64.0, 32.0);
                            vec2 floorLocal = vec2(iso.x + iso.y, iso.y - iso.x) * 0.5;
                            bool floorEdge = uSurfaceKind == 1 && max(abs(floorLocal.x), abs(floorLocal.y)) > 0.465;
                            if (floorEdge && source.a < 0.999) {
                                for (int dy = -2; dy <= 2; dy++) {
                                    for (int dx = -2; dx <= 2; dx++) {
                                        vec4 candidate = sampleSprite(sourcePixel + vec2(float(dx), float(dy)));
                                        if (candidate.a > source.a) source = candidate;
                                    }
                                }
                            }
                            // A wall's rasterized side face is narrower than its 64px projected
                            // tile interval. Repair only outer joins, following the tangent's
                            // isometric slope. Internal windows/signs/cutouts remain untouched.
                            float wallU = mod(sourcePixel.x, 64.0);
                            bool wallEdge = uSurfaceKind == 2 && min(wallU, 64.0 - wallU) < 6.0;
                            if (wallEdge && source.a < 0.999) {
                                vec2 tangent = vec2(1.0, abs(surfaceNormal.z) > 0.5 ? 0.5 : -0.5);
                                for (int step = 1; step <= 6; step++) {
                                    vec2 pa = sourcePixel - tangent * float(step);
                                    vec2 pb = sourcePixel + tangent * float(step);
                                    bool insideA = all(greaterThanEqual(pa, uCrop.xy)) && all(lessThan(pa, uCrop.xy + uCrop.zw));
                                    bool insideB = all(greaterThanEqual(pb, uCrop.xy)) && all(lessThan(pb, uCrop.xy + uCrop.zw));
                                    vec4 a = insideA ? sampleSprite(pa) : vec4(0.0);
                                    vec4 b = insideB ? sampleSprite(pb) : vec4(0.0);
                                    if (a.a > source.a) source = a;
                                    if (b.a > source.a) source = b;
                                }
                            }
                            if (source.a < 0.02) discard;
                            gl_FragColor = vec4(source.rgb * vertexColor * liveLight, source.a);
                            return;
                        }
                        if (uMaterial == 1) {
                            float fine = sin(worldPosition.x * 2.17 + worldPosition.z * 1.63)
                                    * sin(worldPosition.x * 0.73 - worldPosition.z * 1.11);
                            float broad = sin(worldPosition.x * 0.37 - worldPosition.z * 0.53);
                            float variation = 0.985 + 0.010 * fine + 0.005 * broad;
                            gl_FragColor = vec4(vertexColor * variation * liveLight, 1.0);
                            return;
                        }
                        gl_FragColor = vec4(vertexColor * liveLight, 1.0);
                    }
                    """;
            int vertexShader = compile(GL20.GL_VERTEX_SHADER, vertex);
            int fragmentShader = compile(GL20.GL_FRAGMENT_SHADER, fragment);
            int result = GL20.glCreateProgram();
            GL20.glAttachShader(result, vertexShader);
            GL20.glAttachShader(result, fragmentShader);
            GL20.glBindAttribLocation(result, 0, "inPosition");
            GL20.glBindAttribLocation(result, 1, "inNormal");
            GL20.glBindAttribLocation(result, 2, "inColor");
            GL20.glBindAttribLocation(result, 3, "inSourcePixel");
            GL20.glBindAttribLocation(result, 4, "inLayer");
            GL20.glBindAttribLocation(result, 5, "inLightingIndex");
            GL20.glLinkProgram(result);
            if (GL20.glGetProgrami(result, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                throw new IllegalStateException("shader link failed: " + GL20.glGetProgramInfoLog(result));
            }
            GL20.glDetachShader(result, vertexShader);
            GL20.glDetachShader(result, fragmentShader);
            GL20.glDeleteShader(vertexShader);
            GL20.glDeleteShader(fragmentShader);
            return result;
        }

        private static int compile(int type, String source) {
            int shader = GL20.glCreateShader(type);
            GL20.glShaderSource(shader, source);
            GL20.glCompileShader(shader);
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                throw new IllegalStateException("shader compile failed: " + GL20.glGetShaderInfoLog(shader));
            }
            return shader;
        }

        private static void setEnabled(int capability, boolean enabled) {
            if (enabled) GL11.glEnable(capability);
            else GL11.glDisable(capability);
        }
    }

    private record GpuTexturedBatch(String sprite, int vbo, int vertexCount, boolean solidFloor, boolean wallEdges,
                                    float[] projectedBounds) {
        void destroy() {
            GL15.glDeleteBuffers(vbo);
        }
    }

    private record GpuMaterialBatch(String material, int vbo, int vertexCount) {
        void destroy() {
            GL15.glDeleteBuffers(vbo);
        }
    }

    private record GpuMesh(
            long fingerprint,
            float originX, float originZ,
            int vbo,
            int vertexCount,
            List<GpuTexturedBatch> texturedBatches,
            List<GpuMaterialBatch> materialBatches) {
        void destroy() {
            if (vbo != 0) GL15.glDeleteBuffers(vbo);
            for (GpuTexturedBatch batch : texturedBatches) batch.destroy();
            for (GpuMaterialBatch batch : materialBatches) batch.destroy();
        }
    }

    static float[] entityVertices(
            List<WorldState.Entity> entities, Set<Integer> nativeActorIds) {
        EntityFloatBuilder output = new EntityFloatBuilder(Math.max(324, entities.size() * 324));
        for (WorldState.Entity entity : entities) {
            if (nativeActorIds.contains(entity.id())) continue;
            // IsoCell's moving-object list also contains blood/giblets and other transient
            // physics effects. A generic 1.72m blue actor box misrepresents those particles.
            // Retain safety silhouettes only for actual actors/vehicles without native models.
            if (!"zombie".equals(entity.kind()) && !"character".equals(entity.kind())
                    && !"vehicle".equals(entity.kind())) continue;
            float x = entity.x();
            float y = entity.z() * LEVEL_HEIGHT;
            float z = entity.y();
            float radius = "vehicle".equals(entity.kind()) ? 0.9f : 0.28f;
            float height = "vehicle".equals(entity.kind()) ? 1.45f : entity.crawling() ? 0.55f : 1.72f;
            float[] color = "zombie".equals(entity.kind())
                    ? new float[] {0.62f, 0.12f, 0.10f}
                    : "vehicle".equals(entity.kind())
                            ? new float[] {0.28f, 0.30f, 0.34f}
                            : new float[] {0.12f, 0.38f, 0.72f};
            addEntityBox(output, x - radius, y, z - radius, x + radius, y + height, z + radius, color);
        }
        return output.toArray();
    }

    private static void addEntityBox(
            EntityFloatBuilder output,
            float minX, float minY, float minZ,
            float maxX, float maxY, float maxZ,
            float[] color) {
        float[][] p = {
            {minX, minY, minZ}, {maxX, minY, minZ}, {maxX, maxY, minZ}, {minX, maxY, minZ},
            {minX, minY, maxZ}, {maxX, minY, maxZ}, {maxX, maxY, maxZ}, {minX, maxY, maxZ}
        };
        addEntityQuad(output, p[0], p[3], p[2], p[1], new float[] {0, 0, -1}, color);
        addEntityQuad(output, p[4], p[5], p[6], p[7], new float[] {0, 0, 1}, color);
        addEntityQuad(output, p[0], p[4], p[7], p[3], new float[] {-1, 0, 0}, color);
        addEntityQuad(output, p[1], p[2], p[6], p[5], new float[] {1, 0, 0}, color);
        addEntityQuad(output, p[3], p[7], p[6], p[2], new float[] {0, 1, 0}, color);
        addEntityQuad(output, p[0], p[1], p[5], p[4], new float[] {0, -1, 0}, color);
    }

    private static void addEntityQuad(
            EntityFloatBuilder output,
            float[] a, float[] b, float[] c, float[] d,
            float[] normal, float[] color) {
        addEntityVertex(output, a, normal, color);
        addEntityVertex(output, b, normal, color);
        addEntityVertex(output, c, normal, color);
        addEntityVertex(output, a, normal, color);
        addEntityVertex(output, c, normal, color);
        addEntityVertex(output, d, normal, color);
    }

    private static void addEntityVertex(
            EntityFloatBuilder output, float[] p, float[] normal, float[] color) {
        output.add(p[0]); output.add(p[1]); output.add(p[2]);
        output.add(normal[0]); output.add(normal[1]); output.add(normal[2]);
        output.add(color[0]); output.add(color[1]); output.add(color[2]);
        output.add(-1); // diagnostic entity boxes do not sample the static chunk light grid
    }

    private static final class EntityFloatBuilder {
        private float[] values;
        private int size;

        EntityFloatBuilder(int capacity) {
            values = new float[capacity];
        }

        void add(float value) {
            if (size == values.length) values = java.util.Arrays.copyOf(values, values.length * 2);
            values[size++] = value;
        }

        float[] toArray() {
            return java.util.Arrays.copyOf(values, size);
        }
    }

    /** Bounded, coalescing handoff from the verified game thread to one mesh worker. */
    private static final class ChunkQueue {
        private final int capacity;
        private final LinkedHashMap<Long, WorldState.Chunk> values = new LinkedHashMap<>();

        ChunkQueue(int capacity) {
            this.capacity = capacity;
        }

        synchronized boolean offer(WorldState.Chunk chunk) {
            values.remove(chunk.key());
            boolean retainedAll = true;
            if (values.size() >= capacity) {
                Iterator<Long> iterator = values.keySet().iterator();
                if (iterator.hasNext()) {
                    iterator.next();
                    iterator.remove();
                    retainedAll = false;
                }
            }
            values.put(chunk.key(), chunk);
            notifyAll();
            return retainedAll;
        }

        synchronized WorldState.Chunk take() throws InterruptedException {
            while (values.isEmpty()) wait();
            Iterator<Map.Entry<Long, WorldState.Chunk>> iterator = values.entrySet().iterator();
            WorldState.Chunk result = iterator.next().getValue();
            iterator.remove();
            return result;
        }

        synchronized void remove(long key) {
            values.remove(key);
        }
    }
}
