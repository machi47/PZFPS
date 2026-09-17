package dev.pzfps.bridge;

import net.bytebuddy.asm.Advice;

/** Prevents a late vanilla/macOS compatibility-path ungrab during active FPS gameplay. */
public final class PointerGrabPatch {
    private PointerGrabPatch() {}

    @Advice.OnMethodEnter
    public static void enter(@Advice.Argument(value = 0, readOnly = false) boolean grabbed) {
        grabbed = FirstPersonInput.filterHardwareCaptureRequest(grabbed);
    }
}
