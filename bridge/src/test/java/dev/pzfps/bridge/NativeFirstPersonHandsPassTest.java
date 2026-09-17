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
}
