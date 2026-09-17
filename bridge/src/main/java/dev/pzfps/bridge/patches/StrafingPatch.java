package dev.pzfps.bridge;

import net.bytebuddy.asm.Advice;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;

/** Keep the local evaluated actor facing the FPS view during side/back movement. */
public final class StrafingPatch {
    private StrafingPatch() {}

    @Advice.OnMethodExit
    public static void exit(
            @Advice.This IsoGameCharacter character,
            @Advice.Return(readOnly = false) boolean result) {
        if (character instanceof IsoPlayer player
                && player.isLocalPlayer()
                && player.getIndex() == 0
                && FirstPersonInput.requiresStrafePresentation()) {
            result = true;
        }
    }
}
