package dev.pzfps.bridge;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.PerformanceSettings;
import zombie.core.SpriteRenderer;
import zombie.core.skinnedmodel.ModelCamera;
import zombie.core.skinnedmodel.ModelManager;
import zombie.core.skinnedmodel.model.ModelSlotRenderData;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoMovingObject;

/** Renders nonlocal characters through PZ's native evaluated model snapshots. */
final class NativeActorPass {
    private static final float MAXIMUM_DISTANCE = 48.0f;
    private static final float MINIMUM_FORWARD_DOT = (float) Math.cos(Math.toRadians(52.0));
    private static final int MAXIMUM_ACTORS_PER_FRAME = 256;
    private static final ConcurrentLinkedQueue<Drawer> DRAWER_POOL = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean FAILED = new AtomicBoolean();
    private static final AtomicLong QUEUED = new AtomicLong();
    private static final AtomicLong COMPLETED_CALLBACKS = new AtomicLong();
    private static final AtomicLong NO_ACTIVE_MODEL = new AtomicLong();
    private static long frames;

    private NativeActorPass() {}

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
            if (!(object instanceof IsoGameCharacter character)
                    || character == player
                    || character.isDestroyed()
                    || character.isInvisible()
                    || !visible(viewpoint, character.getX(), character.getY())) {
                continue;
            }
            ModelManager.ModelSlot slot = character.legsSprite == null
                    ? null
                    : character.legsSprite.modelSlot;
            if (!usable(slot, character)) {
                NO_ACTIVE_MODEL.incrementAndGet();
                continue;
            }
            Drawer drawer = DRAWER_POOL.poll();
            if (drawer == null) drawer = new Drawer();
            try {
                drawer.prepare(slot);
                drawers.add(drawer);
                entityIds.add(character.getID());
            } catch (Throwable error) {
                drawer.releaseAfterPreparationFailure();
                fail("model snapshot preparation", error);
                releaseAll(drawers);
                return PreparedFrame.empty();
            }
            if (drawers.size() >= MAXIMUM_ACTORS_PER_FRAME) break;
        }
        return new PreparedFrame(drawers, Set.copyOf(entityIds));
    }

    static boolean visible(WorldState.Player player, float worldX, float worldY) {
        float dx = worldX - player.x();
        float dy = worldY - player.y();
        float distanceSquared = dx * dx + dy * dy;
        if (distanceSquared > MAXIMUM_DISTANCE * MAXIMUM_DISTANCE) return false;
        if (distanceSquared < 1.0f) return true;
        float distance = (float) Math.sqrt(distanceSquared);
        float forwardLength = (float) Math.hypot(player.forwardX(), player.forwardY());
        if (forwardLength < 0.0001f) return false;
        float dot = (dx * player.forwardX() + dy * player.forwardY())
                / (distance * forwardLength);
        return dot >= MINIMUM_FORWARD_DOT;
    }

    private static boolean usable(ModelManager.ModelSlot slot, IsoGameCharacter character) {
        return slot != null
                && slot.active
                && !slot.remove
                && slot.character == character
                && slot.model != null
                && slot.model.object == character;
    }

    private static void releaseAll(List<Drawer> drawers) {
        for (Drawer drawer : drawers) drawer.discard();
        drawers.clear();
    }

    private static void fail(String operation, Throwable error) {
        if (!FAILED.compareAndSet(false, true)) return;
        System.err.printf(
                "[PZFPS actors] native actor pass disabled after %s failure: %s%n",
                operation,
                error);
        error.printStackTrace(System.err);
    }

    static final class PreparedFrame {
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

        Set<Integer> entityIds() {
            return entityIds;
        }

        void queueAfterWorld() {
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
                        "[PZFPS actors] queuedThisFrame=%d queuedTotal=%d completedCallbacks=%d noActiveModel=%d%n",
                        queuedThisFrame,
                        QUEUED.get(),
                        COMPLETED_CALLBACKS.get(),
                        NO_ACTIVE_MODEL.get());
            }
        }

        void discard() {
            if (consumed) return;
            consumed = true;
            releaseAll(drawers);
        }
    }

    private static final class Drawer extends TextureDraw.GenericDrawer {
        private ModelSlotRenderData renderData;
        private boolean retained;

        void prepare(ModelManager.ModelSlot slot) {
            renderData = ModelSlotRenderData.alloc();
            renderData.initModel(slot);
            slot.renderRefCount++;
            retained = true;
            renderData.init(slot);
        }

        @Override
        public void render() {
            if (renderData == null || FAILED.get()) return;
            boolean priorChunkFbo = PerformanceSettings.fboRenderChunk;
            ModelCamera priorCamera = ModelCamera.instance;
            try {
                FirstPersonCharacterCamera.INSTANCE.configure(renderData);
                ModelCamera.instance = FirstPersonCharacterCamera.INSTANCE;
                // PZ's chunk mode writes isometric targetDepth into model shaders. Perspective
                // depth is supplied by our camera and shared replacement-world depth buffer.
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
