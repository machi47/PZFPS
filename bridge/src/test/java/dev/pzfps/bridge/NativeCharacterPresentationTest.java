package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import zombie.core.skinnedmodel.ModelManager;
import zombie.core.skinnedmodel.model.ModelInstanceRenderData;
import zombie.core.skinnedmodel.model.ModelSlotRenderData;

final class NativeCharacterPresentationTest {
    @Test
    void removesCopiedIsometricAlphaFromEveryPreparedPart() {
        ModelSlotRenderData data = new ModelSlotRenderData();
        data.alpha = 0;
        for (int i = 0; i < 3; i++) {
            ModelInstanceRenderData part = new ModelInstanceRenderData();
            part.properties.SetFloat("Alpha", 0.15f * i);
            data.modelData.add(part);
        }

        NativeCharacterPresentation.removeIsometricFade(data);

        assertEquals(1, data.alpha);
        for (ModelInstanceRenderData part : data.modelData) {
            assertEquals(1, part.properties.GetParameter("Alpha").GetFloat());
        }
    }

    @Test
    void missingRootNeverSuppressesDiagnosticSilhouette() {
        ModelSlotRenderData data = new ModelSlotRenderData();
        data.modelSlot = new ModelManager.ModelSlot(1, null, null);
        // Clothing-like entries alone do not establish that a body can be drawn.
        data.modelData.add(new ModelInstanceRenderData());
        assertFalse(NativeCharacterPresentation.completeSnapshot(data));
    }
}
