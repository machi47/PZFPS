package dev.pzfps.bridge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.joml.Vector3f;
import zombie.characters.IsoPlayer;
import zombie.core.PerformanceSettings;
import zombie.core.SpriteRenderer;
import zombie.core.skinnedmodel.model.ItemModelRenderer;
import zombie.core.textures.TextureDraw;
import zombie.inventory.InventoryItem;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.objects.IsoWorldInventoryObject;
import zombie.util.list.PZArrayList;

/** Queues PZ's native static-model renderer for culled, identity-checked world items. */
final class NativeWorldItemPass {
    private static final float MAXIMUM_DISTANCE = 48.0f;
    private static final float MODEL_BOUND_RADIUS = 3.0f;
    private static final int MAXIMUM_ITEMS_PER_FRAME = 512;
    private static final Map<Long, List<Reference>> CHUNKS = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<Drawer> DRAWER_POOL = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean FAILED = new AtomicBoolean();
    private static final AtomicLong QUEUED = new AtomicLong();
    private static final AtomicLong COMPLETED_CALLBACKS = new AtomicLong();
    private static final AtomicLong FRUSTUM_CULLED = new AtomicLong();
    private static final AtomicLong UNRESOLVED = new AtomicLong();
    private static final AtomicLong NO_MODEL = new AtomicLong();
    private static long frames;

    record Reference(
            int squareX,
            int squareY,
            int z,
            int objectIndex,
            int itemId,
            float worldX,
            float worldY,
            float worldZ) {}

    private NativeWorldItemPass() {}

    static void acceptChunk(WorldState.Chunk chunk) {
        CHUNKS.put(chunk.key(), references(chunk));
    }

    static void removeChunk(long key) {
        CHUNKS.remove(key);
    }

    static List<Reference> references(WorldState.Chunk chunk) {
        ArrayList<Reference> result = new ArrayList<>();
        int chunkSize = zombie.iso.IsoChunkMap.CHUNK_SIZE_IN_SQUARES;
        int originX = chunk.worldX() * chunkSize;
        int originY = chunk.worldY() * chunkSize;
        for (WorldState.Square square : chunk.squares()) {
            for (WorldState.TileObject object : square.objects()) {
                WorldState.WorldItem item = object.worldItem();
                if (!item.present()) continue;
                result.add(new Reference(
                        originX + square.localX(),
                        originY + square.localY(),
                        square.z(),
                        object.index(),
                        item.itemId(),
                        item.worldX(),
                        item.worldY(),
                        item.worldZ()));
            }
        }
        return List.copyOf(result);
    }

    /** Called on the game/render-state producer thread after the perspective world is queued. */
    static void queueVisible(IsoPlayer player) {
        if (player == null || FAILED.get() || !InProcessWorldRenderer.isReady()) return;
        long now = System.nanoTime();
        WorldState.Player viewpoint = WorldCapture.player(
                player, 0L, now, System.currentTimeMillis());
        ArrayList<Reference> candidates = new ArrayList<>();
        for (List<Reference> chunk : CHUNKS.values()) {
            for (Reference reference : chunk) {
                if (withinHorizontalRange(viewpoint, reference)) candidates.add(reference);
            }
        }
        candidates.sort(Comparator.comparingDouble(
                reference -> horizontalDistanceSquared(viewpoint, reference)));
        int queuedThisFrame = 0;
        for (Reference reference : candidates) {
            IsoWorldInventoryObject worldObject = resolve(player, reference);
            if (worldObject == null) {
                UNRESOLVED.incrementAndGet();
                continue;
            }
            Drawer drawer = DRAWER_POOL.poll();
            if (drawer == null) drawer = new Drawer();
            ItemModelRenderer.RenderStatus status;
            try {
                status = drawer.prepare(worldObject);
            } catch (Throwable error) {
                drawer.recycle();
                fail("model preparation", error);
                return;
            }
            if (status == ItemModelRenderer.RenderStatus.Ready) {
                SpriteRenderer.instance.drawGeneric(drawer);
                QUEUED.incrementAndGet();
                if (++queuedThisFrame >= MAXIMUM_ITEMS_PER_FRAME) break;
            } else {
                drawer.recycle();
                if (status == ItemModelRenderer.RenderStatus.NoModel
                        || status == ItemModelRenderer.RenderStatus.Failed) {
                    NO_MODEL.incrementAndGet();
                }
            }
        }
        if (++frames % 300 == 0) {
            System.out.printf(
                    "[PZFPS items] candidates=%d queuedThisFrame=%d indexed=%d queuedTotal=%d completedCallbacks=%d frustumCulled=%d unresolvedTotal=%d noModelTotal=%d%n",
                    candidates.size(),
                    queuedThisFrame,
                    indexedCount(),
                    QUEUED.get(),
                    COMPLETED_CALLBACKS.get(),
                    FRUSTUM_CULLED.get(),
                    UNRESOLVED.get(),
                    NO_MODEL.get());
        }
    }

    static boolean withinHorizontalRange(WorldState.Player player, Reference item) {
        return horizontalDistanceSquared(player, item) <= MAXIMUM_DISTANCE * MAXIMUM_DISTANCE;
    }

    static float horizontalDistanceSquared(WorldState.Player player, Reference item) {
        float dx = item.worldX() - player.x();
        float dy = item.worldY() - player.y();
        return dx * dx + dy * dy;
    }

    private static IsoWorldInventoryObject resolve(IsoPlayer player, Reference reference) {
        IsoGridSquare square = player.getCell().getGridSquare(
                reference.squareX(), reference.squareY(), reference.z());
        if (square == null) return null;
        PZArrayList<IsoObject> objects = square.getObjects();
        if (reference.objectIndex() >= 0 && reference.objectIndex() < objects.size()) {
            IsoObject indexed = objects.get(reference.objectIndex());
            if (matches(indexed, reference)) return (IsoWorldInventoryObject) indexed;
        }
        IsoWorldInventoryObject unique = null;
        for (int index = 0; index < objects.size(); index++) {
            IsoObject candidate = objects.get(index);
            if (!matches(candidate, reference)) continue;
            if (unique != null) return null;
            unique = (IsoWorldInventoryObject) candidate;
        }
        return unique;
    }

    private static boolean matches(IsoObject object, Reference reference) {
        if (!(object instanceof IsoWorldInventoryObject worldObject)) return false;
        InventoryItem item = worldObject.getItem();
        return item != null && item.getID() == reference.itemId();
    }

    private static int indexedCount() {
        int result = 0;
        Collection<List<Reference>> chunks = CHUNKS.values();
        for (List<Reference> values : chunks) result += values.size();
        return result;
    }

    private static void fail(String operation, Throwable error) {
        if (!FAILED.compareAndSet(false, true)) return;
        System.err.printf(
                "[PZFPS items] native item pass disabled after %s failure: %s%n",
                operation,
                error);
        error.printStackTrace(System.err);
    }

    private static final class Drawer extends TextureDraw.GenericDrawer {
        private final ItemModelRenderer renderer = new ItemModelRenderer();
        private final Vector3f angle = new Vector3f();
        private FirstPersonModelCamera camera;

        ItemModelRenderer.RenderStatus prepare(IsoWorldInventoryObject worldObject) {
            InventoryItem item = worldObject.getItem();
            IsoGridSquare square = worldObject.getSquare();
            ItemModelRenderer.RenderStatus status = renderer.renderMain(
                    item,
                    square,
                    worldObject.getRenderSquare(),
                    worldObject.getWorldPosX(),
                    worldObject.getWorldPosY(),
                    worldObject.getWorldPosZ(),
                    0.0f,
                    -1.0f,
                    false);
            if (status == ItemModelRenderer.RenderStatus.Ready) {
                angle.set(renderer.angle);
                camera = new FirstPersonModelCamera(
                        renderer.x, renderer.y, renderer.z, angle);
            }
            return status;
        }

        @Override
        public void render() {
            if (camera == null
                    || FAILED.get()
                    || InProcessWorldRenderer.currentCameraMatrices() == null) {
                return;
            }
            if (!InProcessWorldRenderer.currentViewIntersectsSphere(
                    renderer.x,
                    renderer.z * 3.0f,
                    renderer.y,
                    MODEL_BOUND_RADIUS)) {
                FRUSTUM_CULLED.incrementAndGet();
                return;
            }
            boolean priorChunkFbo = PerformanceSettings.fboRenderChunk;
            try {
                // PZ's model shader otherwise adds its isometric chunk depth to clip-space Z.
                // The draw is synchronous on the render thread, so scope the override exactly
                // around this perspective model and restore the engine flag before returning.
                PerformanceSettings.fboRenderChunk = false;
                renderer.DoRender(camera, false, false);
                COMPLETED_CALLBACKS.incrementAndGet();
            } catch (Throwable error) {
                fail("render", error);
            } finally {
                PerformanceSettings.fboRenderChunk = priorChunkFbo;
            }
        }

        @Override
        public void postRender() {
            recycle();
        }

        void recycle() {
            renderer.reset();
            camera = null;
            DRAWER_POOL.offer(this);
        }
    }
}
