package dev.pzfps.bridge;

import me.zed_0xff.zombie_buddy.annotations.Patch;

public final class KeyboardInputPatch {
    private KeyboardInputPatch() {}

    @Patch(className = "zombie.input.GameKeyboard", methodName = "isKeyDown")
    public static final class Down {
        private Down() {}

        @Patch.OnExit
        public static void exit(
                @Patch.Argument(0) String action,
                @Patch.Return(readOnly = false) boolean result) {
            if (InputState.current().active() && InputState.isActionDown(action)) result = true;
        }
    }

    @Patch(className = "zombie.input.GameKeyboard", methodName = "isKeyPressed")
    public static final class Pressed {
        private Pressed() {}

        @Patch.OnExit
        public static void exit(
                @Patch.Argument(0) String action,
                @Patch.Return(readOnly = false) boolean result) {
            if (InputState.current().active() && InputState.isActionPressed(action)) result = true;
        }
    }
}
