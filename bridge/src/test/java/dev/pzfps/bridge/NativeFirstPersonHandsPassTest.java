package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import zombie.core.skinnedmodel.model.ModelInstance;

final class NativeFirstPersonHandsPassTest {
    @Test
    void selectsOnlyHeldModelRootsAndTheirWeaponParts() {
        ModelInstance body = new ModelInstance();
        ModelInstance primary = new ModelInstance();
        ModelInstance weaponPart = new ModelInstance();
        ModelInstance secondary = new ModelInstance();
        primary.parent = body;
        weaponPart.parent = primary;
        secondary.parent = body;

        assertFalse(NativeFirstPersonHandsPass.isHeldModel(body, primary, secondary));
        assertTrue(NativeFirstPersonHandsPass.isHeldModel(primary, primary, secondary));
        assertTrue(NativeFirstPersonHandsPass.isHeldModel(weaponPart, primary, secondary));
        assertTrue(NativeFirstPersonHandsPass.isHeldModel(secondary, primary, secondary));
    }

    @Test
    void localBodyUsesAttachmentsWithoutPuttingCameraInsideRootOrHeadwear() {
        ModelInstance body = new ModelInstance();
        ModelInstance trousers = new ModelInstance();
        ModelInstance hair = new ModelInstance();
        ModelInstance held = new ModelInstance();
        trousers.parent = body;
        hair.parent = body;
        held.parent = body;

        assertFalse(NativeFirstPersonHandsPass.isFirstPersonVisible(body, held, null));
        assertTrue(NativeFirstPersonHandsPass.isFirstPersonVisible(trousers, held, null));
        assertTrue(NativeFirstPersonHandsPass.isFirstPersonVisible(held, held, null));
        assertTrue(NativeFirstPersonHandsPass.headAdjacentIdentity("Base.Hair_Long"));
        assertTrue(NativeFirstPersonHandsPass.headAdjacentIdentity("Base.BalaclavaFull"));
        assertFalse(NativeFirstPersonHandsPass.headAdjacentIdentity("Base.Trousers_DefaultTEXTURE"));
    }
}
