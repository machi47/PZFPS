package dev.pzfps.bridge;

import net.bytebuddy.asm.Advice;

public final class MouseUpdatePatch {
    private MouseUpdatePatch() {}

    @Advice.OnMethodExit
    public static void exit() {
        FirstPersonInput.onMouseUpdate();
    }
}
