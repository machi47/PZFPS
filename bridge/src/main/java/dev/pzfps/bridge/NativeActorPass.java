package dev.pzfps.bridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
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
public final class NativeActorPass {
    private static final float MAXIMUM_DISTANCE = 48.0f;
    private static final float MODEL_BOUND_RADIUS = 2.5f;
    private static final int MAXIMUM_ACTORS_PER_FRAME = 256;
    private static final ConcurrentLinkedQueue<Drawer> DRAWER_POOL = new ConcurrentLinkedQueue<>();
    private static final Set<IsoGameCharacter> ACTIVATED_BY_PASS =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private static final AtomicBoolean FAILED = new AtomicBoolean();
    private static final AtomicLong QUEUED = new AtomicLong();
    private static final AtomicLong COMPLETED_CALLBACKS = new AtomicLong();
    private static final AtomicLong FRUSTUM_CULLED = new AtomicLong();
    private static final AtomicLong NO_ACTIVE_MODEL = new AtomicLong();
    private static final AtomicLong ACTIVATED_MODELS = new AtomicLong();
    private static final AtomicLong RELEASED_MODELS = new AtomicLong();
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
        ArrayList<Candidate> candidates = new ArrayList<>();
        for (IsoMovingObject object : player.getCell().getObjectList()) {
            if (!(object instanceof IsoGameCharacter character)
                    || character == player
                    || character.isDestroyed()
                    || character.isInvisible()
                    || !withinHorizontalRange(
                            viewpoint, character.getX(), character.getY())) {
                continue;
            }
            float dx = character.getX() - viewpoint.x();
            float dy = character.getY() - viewpoint.y();
            candidates.add(new Candidate(character, dx * dx + dy * dy));
        }
        candidates.sort(Comparator.comparingDouble(Candidate::distanceSquared));

        Set<IsoGameCharacter> desired = Collections.newSetFromMap(new IdentityHashMap<>());
        int count = Math.min(candidates.size(), MAXIMUM_ACTORS_PER_FRAME);
        for (int index = 0; index < count; index++) {
            IsoGameCharacter character = candidates.get(index).character();
            desired.add(character);
            ensurePerspectiveModel(character);
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
        }
        releaseActorsOutside(desired);
        return new PreparedFrame(drawers, Set.copyOf(entityIds));
    }

    /**
     * IsoWorld.render normally performs an isometric screen-space scene cull before drawing.
     * The replacement world deliberately skips that draw, so activate only the bounded set the
     * perspective renderer may use. setSceneCulled is PZ's own ModelManager lifecycle seam and
     * runs here at the same render boundary as the original culler.
     */
    private static void ensurePerspectiveModel(IsoGameCharacter character) {
        if (!character.isSceneCulled()
                && character.isAddedToModelManager()
                && character.hasActiveModel()) {
            return;
        }
        character.setSceneCulled(false);
        if (ACTIVATED_BY_PASS.add(character)) ACTIVATED_MODELS.incrementAndGet();
    }

    private static void releaseActorsOutside(Set<IsoGameCharacter> desired) {
        var iterator = ACTIVATED_BY_PASS.iterator();
        while (iterator.hasNext()) {
            IsoGameCharacter character = iterator.next();
            if (desired.contains(character)) continue;
            iterator.remove();
            character.setSceneCulled(true);
            RELEASED_MODELS.incrementAndGet();
        }
    }

    static boolean withinHorizontalRange(
            WorldState.Player player, float worldX, float worldY) {
        float dx = worldX - player.x();
        float dy = worldY - player.y();
        return dx * dx + dy * dy <= MAXIMUM_DISTANCE * MAXIMUM_DISTANCE;
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
                        "[PZFPS actors] queuedThisFrame=%d queuedTotal=%d completedCallbacks=%d frustumCulled=%d noActiveModel=%d activated=%d released=%d managed=%d%n",
                        queuedThisFrame,
                        QUEUED.get(),
                        COMPLETED_CALLBACKS.get(),
                        FRUSTUM_CULLED.get(),
                        NO_ACTIVE_MODEL.get(),
                        ACTIVATED_MODELS.get(),
                        RELEASED_MODELS.get(),
                        ACTIVATED_BY_PASS.size());
            }
        }

        public void discard() {
            if (consumed) return;
            consumed = true;
            releaseAll(drawers);
        }
    }

    private record Candidate(IsoGameCharacter character, float distanceSquared) {}

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
            if (!InProcessWorldRenderer.currentViewIntersectsSphere(
                    renderData.x,
                    renderData.z * 3.0f + 0.9f,
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
