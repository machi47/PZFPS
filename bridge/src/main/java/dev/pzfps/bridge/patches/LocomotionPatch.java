package dev.pzfps.bridge;

import net.bytebuddy.asm.Advice;
import zombie.characters.IsoGameCharacter;
import zombie.iso.Vector2;

/** Adapts native root motion and strafe selection to camera-relative FPS locomotion. */
public final class LocomotionPatch {
    private LocomotionPatch() {}

    public static final class DeferredMovement {
        private DeferredMovement() {}

        @Advice.OnMethodExit
        public static void exit(
                @Advice.This IsoGameCharacter character,
                @Advice.Return Vector2 result) {
            FirstPersonInput.redirectDeferredMovement(character, result);
        }
    }

    public static final class StrafePresentation {
        private StrafePresentation() {}

        @Advice.OnMethodExit
        public static void exit(
                @Advice.This IsoGameCharacter character,
                @Advice.Return(readOnly = false) boolean result) {
            if (!result && FirstPersonInput.requiresStrafePresentation(character)) {
                result = true;
            }
        }
    }
}
