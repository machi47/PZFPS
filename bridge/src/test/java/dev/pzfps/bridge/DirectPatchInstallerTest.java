package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.assertTrue;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import org.junit.jupiter.api.Test;
import zombie.characters.IsoPlayer;
import zombie.core.physics.BallisticsController;

final class DirectPatchInstallerTest {
    @Test
    void matchesInstalledVectorInputDescriptors() {
        TypeDescription player = new TypeDescription.ForLoadedType(IsoPlayer.class);
        DirectPatchInstaller.requireOneTarget(
                player,
                DirectPatchInstaller.inputMoveMatcher(),
                "getInputMoveVector(Lzombie/iso/Vector2;)Lzombie/iso/Vector2;");
        DirectPatchInstaller.requireOneTarget(
                player,
                DirectPatchInstaller.aimMatcher(),
                "getAimVector(Lzombie/iso/Vector2;)Lzombie/iso/Vector2;");
        DirectPatchInstaller.requireOneTarget(
                player,
                DirectPatchInstaller.calculateAimMatcher(),
                "calculateAimVector(Lzombie/iso/Vector2;)Lzombie/iso/Vector2;");
        DirectPatchInstaller.requireOneTarget(
                player,
                DirectPatchInstaller.setAngleFromAimMatcher(),
                "setAngleFromAim()V");
    }

    @Test
    void matchesInstalledBallisticsDescriptors() {
        TypeDescription ballistics = new TypeDescription.ForLoadedType(BallisticsController.class);
        DirectPatchInstaller.requireOneTarget(
                ballistics,
                DirectPatchInstaller.ballisticsMuzzleMatcher(),
                "calculateMuzzlePosition(Lzombie/iso/Vector3;Lzombie/iso/Vector3;)F");
        DirectPatchInstaller.requireOneTarget(
                ballistics,
                DirectPatchInstaller.ballisticsCameraTargetsMatcher(),
                "getCameraTargets(FZ)V");
    }

    @Test
    void inlinesBallisticsAdviceIntoInstalledClass() {
        byte[] transformed = new ByteBuddy()
                .redefine(BallisticsController.class)
                .visit(Advice.to(BallisticsAimPatch.Muzzle.class)
                        .on(DirectPatchInstaller.ballisticsMuzzleMatcher()))
                .visit(Advice.to(BallisticsAimPatch.CameraTargets.class)
                        .on(DirectPatchInstaller.ballisticsCameraTargetsMatcher()))
                .make()
                .getBytes();

        assertTrue(transformed.length > 0);
    }
}
