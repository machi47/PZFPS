package dev.pzfps.bridge;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import zombie.characters.IsoPlayer;
import zombie.core.PerformanceSettings;
import zombie.core.SpriteRenderer;
import zombie.core.skinnedmodel.ModelCamera;
import zombie.core.skinnedmodel.ModelManager;
import zombie.core.skinnedmodel.model.ModelSlotRenderData;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoMovingObject;
import zombie.iso.sprite.IsoSprite;
import zombie.vehicles.BaseVehicle;

/** Renders live vehicles through PZ's own evaluated model slot instead of a debug box. */
public final class NativeVehiclePass {
    private static final float MAXIMUM_DISTANCE = 64.0f;
    private static final float MODEL_BOUND_RADIUS = 8.0f;
    private static final int MAXIMUM_VEHICLES_PER_FRAME = 128;
    private static final ConcurrentLinkedQueue<Drawer> DRAWER_POOL = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean FAILED = new AtomicBoolean();
    private static final AtomicLong QUEUED = new AtomicLong();
    private static final AtomicLong COMPLETED_CALLBACKS = new AtomicLong();
    private static final AtomicLong FRUSTUM_CULLED = new AtomicLong();
    private static final AtomicLong NO_ACTIVE_MODEL = new AtomicLong();
    private static long frames;

    private NativeVehiclePass() {}

    static PreparedFrame prepareVisible(IsoPlayer player) {
        if (player == null || FAILED.get() || !InProcessWorldRenderer.isReady()) {
            return PreparedFrame.empty();
        }
        long now = System.nanoTime();
        WorldState.Player viewpoint = WorldCapture.player(
                player, 0L, now, System.currentTimeMillis());
        ArrayList<Drawer> drawers = new ArrayList<>();
        LinkedHashSet<Integer> entityIds = new LinkedHashSet<>();
        for (IsoMovingObject object : player.getCell().getObjectList()) {
            if (!(object instanceof BaseVehicle vehicle)
                    || vehicle.isDestroyed()
                    || !withinHorizontalRange(viewpoint, vehicle.getX(), vehicle.getY())) {
                continue;
            }
            IsoSprite sprite = vehicle.getSprite();
            ModelManager.ModelSlot slot = sprite == null ? null : sprite.modelSlot;
            if (!usable(slot, vehicle)) {
                NO_ACTIVE_MODEL.incrementAndGet();
                continue;
            }
            Drawer drawer = DRAWER_POOL.poll();
            if (drawer == null) drawer = new Drawer();
            try {
                drawer.prepare(slot, vehicle);
                drawers.add(drawer);
                entityIds.add(vehicle.getID());
            } catch (Throwable error) {
                drawer.releaseAfterPreparationFailure();
                fail("model snapshot preparation", error);
                releaseAll(drawers);
                return PreparedFrame.empty();
            }
            if (drawers.size() >= MAXIMUM_VEHICLES_PER_FRAME) break;
        }
        return new PreparedFrame(drawers, Set.copyOf(entityIds));
    }

    static boolean withinHorizontalRange(
            WorldState.Player player, float worldX, float worldY) {
        float dx = worldX - player.x();
        float dy = worldY - player.y();
        return dx * dx + dy * dy <= MAXIMUM_DISTANCE * MAXIMUM_DISTANCE;
    }

    private static boolean usable(ModelManager.ModelSlot slot, BaseVehicle vehicle) {
        return slot != null
                && slot.active
                && !slot.remove
                && slot.character == null
                && slot.model != null
                && slot.model.object == vehicle;
    }

    private static void releaseAll(List<Drawer> drawers) {
        for (Drawer drawer : drawers) drawer.discard();
        drawers.clear();
    }

    private static void fail(String operation, Throwable error) {
        if (!FAILED.compareAndSet(false, true)) return;
        System.err.printf(
                "[PZFPS vehicles] native vehicle pass disabled after %s failure: %s%n",
                operation,
                error);
        error.printStackTrace(System.err);
    }

    public static final class PreparedFrame {
        private final List<Drawer> drawers;
        private final Set<Integer> entityIds;
        private boolean consumed;

        private PreparedFrame(List<Drawer> drawers, Set<Integer> entityIds) {
            this.drawers = drawers;
            this.entityIds = entityIds;
        }

        static PreparedFrame empty() {
            return new PreparedFrame(new ArrayList<>(), Set.of());
        }

        public Set<Integer> entityIds() {
            return entityIds;
        }

        public void queueAfterWorld() {
            if (consumed) return;
            consumed = true;
            int queuedThisFrame = 0;
            for (int index = 0; index < drawers.size(); index++) {
                Drawer drawer = drawers.get(index);
                try {
                    SpriteRenderer.instance.drawGeneric(drawer);
                    queuedThisFrame++;
                    QUEUED.incrementAndGet();
                } catch (Throwable error) {
                    for (int remaining = index; remaining < drawers.size(); remaining++) {
                        drawers.get(remaining).discard();
                    }
                    fail("render-queue submission", error);
                    break;
                }
            }
            if (++frames % 300 == 0) {
                System.out.printf(
                        "[PZFPS vehicles] queuedThisFrame=%d queuedTotal=%d completedCallbacks=%d frustumCulled=%d noActiveModel=%d%n",
                        queuedThisFrame,
                        QUEUED.get(),
                        COMPLETED_CALLBACKS.get(),
                        FRUSTUM_CULLED.get(),
                        NO_ACTIVE_MODEL.get());
            }
        }

        public void discard() {
            if (consumed) return;
            consumed = true;
            releaseAll(drawers);
        }
    }

    private static final class Drawer extends TextureDraw.GenericDrawer {
        private ModelSlotRenderData renderData;
        private boolean retained;

        void prepare(ModelManager.ModelSlot slot, BaseVehicle vehicle) {
            NativeVehiclePresentation.prepare(vehicle);
            renderData = ModelSlotRenderData.alloc();
            renderData.initModel(slot);
            slot.renderRefCount++;
            retained = true;
            renderData.init(slot);
            // BaseVehicle.render normally updates alpha from the isometric seen/could-see
            // pass. This pass uses the perspective depth/frustum instead, so the replacement
            // must not inherit that unrelated alpha fade.
            NativeVehiclePresentation.usePerspectiveVisibility(renderData);
        }

        @Override
        public void render() {
            if (renderData == null || FAILED.get()) return;
            if (!InProcessWorldRenderer.currentViewIntersectsSphere(
                    renderData.x,
                    renderData.z * 3.0f + 1.0f,
                    renderData.y,
                    MODEL_BOUND_RADIUS)) {
                FRUSTUM_CULLED.incrementAndGet();
                return;
            }
            boolean priorChunkFbo = PerformanceSettings.fboRenderChunk;
            ModelCamera priorCamera = ModelCamera.instance;
            try {
                FirstPersonCharacterCamera.INSTANCE.configure(renderData);
                ModelCamera.instance = FirstPersonCharacterCamera.INSTANCE;
                PerformanceSettings.fboRenderChunk = false;
                renderData.render();
                COMPLETED_CALLBACKS.incrementAndGet();
            } catch (Throwable error) {
                fail("render", error);
            } finally {
                PerformanceSettings.fboRenderChunk = priorChunkFbo;
                ModelCamera.instance = priorCamera;
            }
        }

        @Override
        public void postRender() {
            discard();
        }

        void releaseAfterPreparationFailure() {
            if (retained) discard();
            else if (renderData == null) DRAWER_POOL.offer(this);
        }

        void discard() {
            ModelSlotRenderData data = renderData;
            renderData = null;
            if (retained && data != null) {
                retained = false;
                data.postRender();
            }
            DRAWER_POOL.offer(this);
        }
    }
}
