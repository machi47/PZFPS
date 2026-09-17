package dev.pzfps.bridge;

import net.bytebuddy.description.type.TypeDescription;
import org.junit.jupiter.api.Test;
import zombie.characters.IsoPlayer;

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
}
