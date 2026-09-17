package dev.pzfps.bridge;

import net.bytebuddy.asm.Advice;

/** Owns GLFW cursor mode at PZ's display-thread writer, before vanilla can overwrite it. */
public final class DisplayCursorPatch {
    private DisplayCursorPatch() {}

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean enter() {
        return FirstPersonInput.updateNativeCursor();
    }
}
