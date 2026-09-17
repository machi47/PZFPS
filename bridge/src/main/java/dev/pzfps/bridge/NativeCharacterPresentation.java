package dev.pzfps.bridge;

import zombie.core.skinnedmodel.model.ModelInstanceRenderData;
import zombie.core.skinnedmodel.model.ModelInstance;
import zombie.core.skinnedmodel.model.ModelSlotRenderData;
import zombie.core.skinnedmodel.ModelManager;

/** Presentation-only corrections on retained native snapshots; never changes live actor state. */
final class NativeCharacterPresentation {
    private NativeCharacterPresentation() {}

    /** Native producer-thread preparation normally performed by character/sprite rendering. */
    static void prepareModel(ModelManager.ModelSlot slot) {
        // Texture creation is not part of ModelSlotRenderData.init: IsoGameCharacter.render
        // requests it first. Without this, body.tex remains null and RenderCharacter skips the
        // body while independently textured hair/clothing still draw. PZ also handles outfit,
        // blood, damage and equipped-texture invalidation here; don't synthesize another skin.
        slot.character.checkUpdateModelTextures();
        slot.model.updateLights();
    }

    /** Don't erase the diagnostic actor silhouette for an incomplete native outfit snapshot. */
    static boolean completeSnapshot(ModelSlotRenderData data) {
        if (data.textureCreator != null && !data.textureCreator.isRendered()) return false;
        if (!containsReadyPart(data, data.modelSlot.model)) return false;
        return containsReadyParts(data, data.modelSlot.sub);
    }

    private static boolean containsReadyParts(ModelSlotRenderData data, Iterable<ModelInstance> parts) {
        for (ModelInstance part : parts) {
            if (!containsReadyPart(data, part) || !containsReadyParts(data, part.sub)) return false;
        }
        return true;
    }

    private static boolean containsReadyPart(ModelSlotRenderData data, ModelInstance part) {
        if (part == null || part.model == null || !part.model.isReady()) return false;
        if (part.tex == null && part.model.tex == null) return false;
        if (part.getTextureInitializer() != null && !part.getTextureInitializer().isRendered()) return false;
        for (ModelInstanceRenderData prepared : data.modelData) {
            if (prepared.modelInstance == part) return true;
        }
        return false;
    }

    static void removeIsometricFade(ModelSlotRenderData data) {
        data.alpha = 1.0f;
        // init() copied alpha into each shader property block already. Both the newly prepared
        // models and PZ's last-ready fallback need the same correction (body, clothes, equipment).
        for (ModelInstanceRenderData model : data.modelData) {
            model.properties.SetFloat("Alpha", 1.0f);
        }
        for (ModelInstanceRenderData model : data.getModelData()) {
            model.properties.SetFloat("Alpha", 1.0f);
        }
    }
}
