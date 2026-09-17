package dev.pzfps.bridge;

import net.bytebuddy.asm.Advice;
import zombie.iso.Vector2;

public final class InputMovePatch {
    private InputMovePatch() {}

    @Advice.OnMethodExit
    public static void exit(@Advice.Return(readOnly = false) Vector2 result) {
        if (result != null) InputState.movementVector(result);
    }
}
