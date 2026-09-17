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
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import zombie.core.SpriteRenderer;
import zombie.core.textures.TextureDraw;

/**
 * Single-window replacement for IsoWorld.render(). PZ still owns frame setup, simulation,
 * world-space text, and UI; renderer-owned immutable meshes fill only the world slot.
 */
public final class InProcessWorldRenderer {
    private static final int MAX_PENDING_CHUNKS = 64;
    private static final int STRIDE_BYTES = WorldMeshBuilder.FLOATS_PER_VERTEX * Float.BYTES;
    private static final float LEVEL_HEIGHT = 3.0f;
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final AtomicBoolean ASSETS_READY = new AtomicBoolean();
    private static final AtomicBoolean RENDER_FAILED = new AtomicBoolean();
    private static final AtomicBoolean REPLACEMENT_ANNOUNCED = new AtomicBoolean();
    private static final AtomicReference<WorldState.Player> PLAYER = new AtomicReference<>();
    private static final AtomicReference<WorldState.Entities> ENTITIES = new AtomicReference<>();
    private static final ConcurrentHashMap<Long, WorldMeshBuilder.MeshData> MESHES =
            new ConcurrentHashMap<>();
    private static final ChunkQueue PENDING = new ChunkQueue(MAX_PENDING_CHUNKS);
    private static final AtomicLong BUILT_CHUNKS = new AtomicLong();
    private static final AtomicLong DROPPED_CHUNKS = new AtomicLong();
    private static final AtomicLong ENQUEUED_FRAMES = new AtomicLong();
    private static final AtomicLong COMPLETED_FRAMES = new AtomicLong();
    private static final boolean ENABLED = Boolean.getBoolean("pzfps.renderer.enabled");

    private static volatile GpuState gpuState;

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
        if (!PENDING.offer(chunk)) DROPPED_CHUNKS.incrementAndGet();
    }

    public static void removeChunk(long key) {
        PENDING.remove(key);
        MESHES.remove(key);
    }

    /** Called by advice on the game/render-state producer thread. */
    public static boolean replaceWorldDraw() {
        if (!ENABLED
                || !ASSETS_READY.get()
                || RENDER_FAILED.get()
                || PLAYER.get() == null
                || MESHES.isEmpty()) {
            return false;
        }
        RenderSnapshot snapshot = new RenderSnapshot(
                PLAYER.get(), ENTITIES.get(), List.copyOf(MESHES.values()));
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
                long stateAge = Math.max(
                        0L, System.currentTimeMillis() - snapshot.player.captureEpochMillis());
                System.out.printf(
                        "[PZFPS renderer] completedFrames=%d enqueuedFrames=%d meshes=%d built=%d dropped=%d stateAgeMs=%d%n",
                        completed,
                        ENQUEUED_FRAMES.get(),
                        snapshot.meshes.size(),
                        BUILT_CHUNKS.get(),
                        DROPPED_CHUNKS.get(),
                        stateAge);
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
            List<WorldMeshBuilder.MeshData> meshes) {}

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
        private int entityVao;
        private int entityVbo;

        GpuState() {
            program = createProgram();
            mvpUniform = GL20.glGetUniformLocation(program, "uMvp");
            if (mvpUniform < 0) throw new IllegalStateException("uMvp uniform is absent");
        }

        void render(RenderSnapshot snapshot) {
            int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            int previousArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            boolean previousDepth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            boolean previousBlend = GL11.glIsEnabled(GL11.GL_BLEND);
            boolean previousCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
            boolean previousDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            try {
                GL11.glClearColor(0.025f, 0.03f, 0.04f, 1.0f);
                GL11.glClearDepth(1.0);
                GL11.glDepthMask(true);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
                GL11.glEnable(GL11.GL_DEPTH_TEST);
                GL11.glDepthFunc(GL11.GL_LEQUAL);
                GL11.glDisable(GL11.GL_BLEND);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL20.glUseProgram(program);

                FloatBuffer matrix = viewProjection(snapshot.player);
                GL20.glUniformMatrix4fv(mvpUniform, false, matrix);
                synchronizeMeshes(snapshot.meshes);
                for (WorldMeshBuilder.MeshData source : snapshot.meshes) {
                    GpuMesh mesh = meshes.get(source.key());
                    if (mesh == null || mesh.vertexCount == 0) continue;
                    GL30.glBindVertexArray(mesh.vao);
                    GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, mesh.vertexCount);
                }
                drawEntities(snapshot.entities);
            } finally {
                GL30.glBindVertexArray(previousVao);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousArrayBuffer);
                GL20.glUseProgram(previousProgram);
                setEnabled(GL11.GL_DEPTH_TEST, previousDepth);
                setEnabled(GL11.GL_BLEND, previousBlend);
                setEnabled(GL11.GL_CULL_FACE, previousCull);
                GL11.glDepthMask(previousDepthMask);
            }
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
            int vao = GL30.glGenVertexArrays();
            int vbo = GL15.glGenBuffers();
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            FloatBuffer vertices = BufferUtils.createFloatBuffer(source.vertices().length);
            vertices.put(source.vertices()).flip();
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STATIC_DRAW);
            configureAttributes();
            return new GpuMesh(source.fingerprint(), vao, vbo, source.vertexCount());
        }

        private void drawEntities(WorldState.Entities entities) {
            if (entities == null || entities.values().isEmpty()) return;
            float[] vertices = entityVertices(entities.values());
            if (vertices.length == 0) return;
            if (entityVao == 0) {
                entityVao = GL30.glGenVertexArrays();
                entityVbo = GL15.glGenBuffers();
                GL30.glBindVertexArray(entityVao);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, entityVbo);
                configureAttributes();
            } else {
                GL30.glBindVertexArray(entityVao);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, entityVbo);
            }
            FloatBuffer buffer = BufferUtils.createFloatBuffer(vertices.length);
            buffer.put(vertices).flip();
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buffer, GL15.GL_STREAM_DRAW);
            GL11.glDrawArrays(
                    GL11.GL_TRIANGLES, 0, vertices.length / WorldMeshBuilder.FLOATS_PER_VERTEX);
        }

        private static void configureAttributes() {
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, STRIDE_BYTES, 0L);
            GL20.glEnableVertexAttribArray(1);
            GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, STRIDE_BYTES, 3L * Float.BYTES);
            GL20.glEnableVertexAttribArray(2);
            GL20.glVertexAttribPointer(2, 3, GL11.GL_FLOAT, false, STRIDE_BYTES, 6L * Float.BYTES);
        }

        private static FloatBuffer viewProjection(WorldState.Player player) {
            IntBuffer viewport = BufferUtils.createIntBuffer(4);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            int width = Math.max(1, viewport.get(2));
            int height = Math.max(1, viewport.get(3));
            float eyeX = player.x();
            float eyeY = player.z() * LEVEL_HEIGHT + 1.62f;
            float eyeZ = player.y();
            float pitch = Math.max(-1.45f, Math.min(1.45f, player.verticalAim()));
            float horizontal = (float) Math.cos(pitch);
            float directionX = player.forwardX() * horizontal;
            float directionY = (float) Math.sin(pitch);
            float directionZ = player.forwardY() * horizontal;
            if (Math.abs(directionX) + Math.abs(directionZ) < 0.001f) directionZ = 1.0f;
            Matrix4f matrix = new Matrix4f()
                    .perspective((float) Math.toRadians(82.0), (float) width / height, 0.035f, 400.0f)
                    .lookAt(
                            eyeX,
                            eyeY,
                            eyeZ,
                            eyeX + directionX,
                            eyeY + directionY,
                            eyeZ + directionZ,
                            0,
                            1,
                            0);
            FloatBuffer result = BufferUtils.createFloatBuffer(16);
            matrix.get(result);
            return result;
        }

        private static int createProgram() {
            String vertex = """
                    #version 330 core
                    layout(location=0) in vec3 inPosition;
                    layout(location=1) in vec3 inNormal;
                    layout(location=2) in vec3 inColor;
                    uniform mat4 uMvp;
                    out vec3 vertexColor;
                    void main() {
                        vec3 sun = normalize(vec3(-0.45, 0.82, -0.35));
                        float light = 0.42 + 0.58 * max(dot(normalize(inNormal), sun), 0.0);
                        vertexColor = inColor * light;
                        gl_Position = uMvp * vec4(inPosition, 1.0);
                    }
                    """;
            String fragment = """
                    #version 330 core
                    in vec3 vertexColor;
                    out vec4 outColor;
                    void main() {
                        outColor = vec4(vertexColor, 1.0);
                    }
                    """;
            int vertexShader = compile(GL20.GL_VERTEX_SHADER, vertex);
            int fragmentShader = compile(GL20.GL_FRAGMENT_SHADER, fragment);
            int result = GL20.glCreateProgram();
            GL20.glAttachShader(result, vertexShader);
            GL20.glAttachShader(result, fragmentShader);
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

    private record GpuMesh(long fingerprint, int vao, int vbo, int vertexCount) {
        void destroy() {
            GL15.glDeleteBuffers(vbo);
            GL30.glDeleteVertexArrays(vao);
        }
    }

    private static float[] entityVertices(List<WorldState.Entity> entities) {
        EntityFloatBuilder output = new EntityFloatBuilder(Math.max(324, entities.size() * 324));
        for (WorldState.Entity entity : entities) {
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
