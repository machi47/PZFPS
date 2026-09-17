package dev.pzfps.bridge;

import net.bytebuddy.asm.Advice;
import zombie.characters.IsoGameCharacter;
import zombie.core.physics.BallisticsController;
import zombie.iso.Vector3;

/** Connects the FPS view to B42's existing native ballistics queries. */
public final class BallisticsAimPatch {
    private BallisticsAimPatch() {}

    public static final class Muzzle {
        private Muzzle() {}

        @Advice.OnMethodExit
        public static void exit(
                @Advice.FieldValue("isoGameCharacter") IsoGameCharacter owner,
                @Advice.Argument(1) Vector3 muzzleDirection) {
            PerspectiveBallistics.overrideMuzzleDirection(owner, muzzleDirection);
        }
    }

    public static final class CameraTargets {
        private CameraTargets() {}

        @Advice.OnMethodEnter
        public static void enter(
                @Advice.This BallisticsController controller,
                @Advice.FieldValue("isoGameCharacter") IsoGameCharacter owner) {
            PerspectiveBallistics.configureNativeCameraRay(controller, owner);
        }
    }
}
