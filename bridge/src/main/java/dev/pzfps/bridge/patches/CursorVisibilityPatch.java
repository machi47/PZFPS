package dev.pzfps.bridge;

import net.bytebuddy.asm.Advice;

/** Prevents vanilla isometric combat/pan code from exposing a cursor during FPS capture. */
public final class CursorVisibilityPatch {
    private CursorVisibilityPatch() {}

    @Advice.OnMethodEnter
    public static void enter(@Advice.Argument(value = 0, readOnly = false) boolean visible) {
        visible = FirstPersonInput.filterCursorVisibilityRequest(visible);
    }
}
