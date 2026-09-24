package dev.pzfps.bridge;

import zombie.core.skinnedmodel.model.ModelInstanceRenderData;
import zombie.core.skinnedmodel.model.ModelSlotRenderData;
import zombie.vehicles.BaseVehicle;

/** Restores PZ vehicle draw preparation while using perspective-scene visibility. */
final class NativeVehiclePresentation {
    private NativeVehiclePresentation() {}

    /** PZ's BaseVehicle.render refreshes live paint, damage, window and light shader state. */
    static void prepare(BaseVehicle vehicle) {
        vehicle.updateLights();
    }

    /** Isometric seen/could-see fading does not describe perspective depth visibility. */
    static void usePerspectiveVisibility(ModelSlotRenderData data) {
        data.alpha = 1.0f;
        for (int index = 0; index < data.modelData.size(); index++) {
            ModelInstanceRenderData model = data.modelData.get(index);
            if (model != null) model.properties.SetFloat("Alpha", 1.0f);
        }
        for (int index = 0; index < data.getModelData().size(); index++) {
            ModelInstanceRenderData model = data.getModelData().get(index);
            if (model != null) model.properties.SetFloat("Alpha", 1.0f);
        }
    }
}
