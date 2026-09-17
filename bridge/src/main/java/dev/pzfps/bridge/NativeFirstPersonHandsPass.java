package dev.pzfps.bridge;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.lwjgl.opengl.GL11;
import zombie.characters.IsoPlayer;
import zombie.core.PerformanceSettings;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.skinnedmodel.ModelCamera;
import zombie.core.skinnedmodel.ModelManager;
import zombie.core.skinnedmodel.model.Model;
import zombie.core.skinnedmodel.model.ModelInstance;
import zombie.core.skinnedmodel.model.ModelInstanceRenderData;
import zombie.core.skinnedmodel.model.ModelInstanceRenderDataList;
import zombie.core.skinnedmodel.model.ModelSlotRenderData;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;

/** Draws only the local player's PZ-evaluated held models, never the head or torso mesh. */
final class NativeFirstPersonHandsPass {
    private static final ConcurrentLinkedQueue<Drawer> DRAWER_POOL = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean FAILED = new AtomicBoolean();
    private static final AtomicLong QUEUED = new AtomicLong();
    private static final AtomicLong COMPLETED_CALLBACKS = new AtomicLong();
    private static final AtomicLong NO_HELD_MODEL = new AtomicLong();
    private static long frames;

    private NativeFirstPersonHandsPass() {}

    static PreparedFrame prepare(IsoPlayer player) {
        if (player == null || FAILED.get() || !InProcessWorldRenderer.isReady()) {
            return PreparedFrame.empty();
        }
        ModelInstance primary = player.primaryHandModel;
        ModelInstance secondary = player.secondaryHandModel;
        if (primary == null && secondary == null) {
            NO_HELD_MODEL.incrementAndGet();
            return PreparedFrame.empty();
        }
        ModelManager.ModelSlot slot = player.legsSprite == null
                ? null
                : player.legsSprite.modelSlot;
        if (!usable(slot, player)) return PreparedFrame.empty();

        Drawer drawer = DRAWER_POOL.poll();
        if (drawer == null) drawer = new Drawer();
        try {
            drawer.prepare(slot, primary, secondary);
            return new PreparedFrame(drawer);
        } catch (Throwable error) {
            drawer.releaseAfterPreparationFailure();
            fail("model snapshot preparation", error);
            return PreparedFrame.empty();
        }
    }

    static boolean isHeldModel(
            ModelInstance candidate, ModelInstance primary, ModelInstance secondary) {
        for (ModelInstance current = candidate; current != null; current = current.parent) {
            if (current == primary || current == secondary) return true;
        }
        return false;
    }

    private static boolean usable(ModelManager.ModelSlot slot, IsoPlayer player) {
        return slot != null
                && slot.active
                && !slot.remove
                && slot.character == player
                && slot.model != null
                && slot.model.object == player;
    }

    private static void fail(String operation, Throwable error) {
        if (!FAILED.compareAndSet(false, true)) return;
        System.err.printf(
                "[PZFPS hands] native held-model pass disabled after %s failure: %s%n",
                operation,
                error);
        error.printStackTrace(System.err);
    }

    static final class PreparedFrame {
        private Drawer drawer;

        private PreparedFrame(Drawer drawer) {
            this.drawer = drawer;
        }

        static PreparedFrame empty() {
            return new PreparedFrame(null);
        }

        void queueAfterWorld() {
            Drawer value = drawer;
            drawer = null;
            if (value == null) return;
            try {
                SpriteRenderer.instance.drawGeneric(value);
                QUEUED.incrementAndGet();
            } catch (Throwable error) {
                value.discard();
                fail("render-queue submission", error);
            }
            if (++frames % 300 == 0) {
                System.out.printf(
                        "[PZFPS hands] queuedTotal=%d completedCallbacks=%d noHeldModel=%d%n",
                        QUEUED.get(),
                        COMPLETED_CALLBACKS.get(),
                        NO_HELD_MODEL.get());
            }
        }

        void discard() {
            Drawer value = drawer;
            drawer = null;
            if (value != null) value.discard();
        }
    }

    private static final class Drawer extends TextureDraw.GenericDrawer {
        private ModelSlotRenderData renderData;
        private ModelInstance primary;
        private ModelInstance secondary;
        private boolean retained;

        void prepare(
                ModelManager.ModelSlot slot, ModelInstance primary, ModelInstance secondary) {
            renderData = ModelSlotRenderData.alloc();
            renderData.initModel(slot);
            slot.renderRefCount++;
            retained = true;
            renderData.init(slot);
            this.primary = primary;
            this.secondary = secondary;
        }

        @Override
        public void render() {
            if (renderData == null || FAILED.get()) return;
            boolean priorChunkFbo = PerformanceSettings.fboRenderChunk;
            ModelCamera priorCamera = ModelCamera.instance;
            boolean cameraBegun = false;
            boolean clientAttributesPushed = false;
            boolean attributesPushed = false;
            try {
                // LWJGL's compatibility binding omits the symbolic all-client mask; B42 uses -1.
                GL11.glPushClientAttrib(-1);
                clientAttributesPushed = true;
                GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
                attributesPushed = true;
                PerformanceSettings.fboRenderChunk = false;
                FirstPersonCharacterCamera.INSTANCE.configure(renderData);
                ModelCamera.instance = FirstPersonCharacterCamera.INSTANCE;
                renderData.checkReady();
                ModelInstanceRenderDataList values = renderData.getModelData();
                if (values.isEmpty()) return;

                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                GL11.glEnable(GL11.GL_ALPHA_TEST);
                GL11.glAlphaFunc(GL11.GL_GREATER, 0.0f);
                GL11.glEnable(GL11.GL_DEPTH_TEST);
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                Model.CharacterModelCameraBegin(renderData);
                cameraBegun = true;
                int renderedModels = 0;
                for (ModelInstanceRenderData value : values) {
                    if (value.modelInstance == null
                            || !isHeldModel(value.modelInstance, primary, secondary)) {
                        continue;
                    }
                    value.RenderCharacter(renderData);
                    renderedModels++;
                }
                if (renderedModels > 0) COMPLETED_CALLBACKS.incrementAndGet();
            } catch (Throwable error) {
                fail("render", error);
            } finally {
                if (cameraBegun) Model.CharacterModelCameraEnd();
                if (attributesPushed) GL11.glPopAttrib();
                if (clientAttributesPushed) GL11.glPopClientAttrib();
                Texture.lastTextureID = -1;
                SpriteRenderer.ringBuffer.restoreVbos = true;
                GLStateRenderThread.restore();
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
            primary = null;
            secondary = null;
            if (retained && data != null) {
                retained = false;
                data.postRender();
            }
            DRAWER_POOL.offer(this);
        }
    }
}
