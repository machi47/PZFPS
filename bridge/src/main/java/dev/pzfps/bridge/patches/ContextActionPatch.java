package dev.pzfps.bridge;

import java.util.ArrayList;
import net.bytebuddy.asm.Advice;
import zombie.characters.ContextualAction;
import zombie.characters.IsoPlayer;

/** Keeps PZ's contextual-action construction/execution and only changes its final target choice. */
public final class ContextActionPatch {
    private ContextActionPatch() {}

    public static final class Scope {
        private Scope() {}

        @Advice.OnMethodEnter
        public static void enter(@Advice.This IsoPlayer player) {
            BridgeRuntime.beginPerspectiveInteract(player);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit() {
            PerspectiveInteract.end();
        }
    }

    public static final class PickBest {
        private PickBest() {}

        @Advice.OnMethodExit
        public static void exit(
                @Advice.Argument(0) ArrayList<ContextualAction> actions,
                @Advice.Return(readOnly = false) ContextualAction result) {
            result = PerspectiveInteract.preferTarget(actions, result);
        }
    }

    public static final class Execute {
        private Execute() {}

        @Advice.OnMethodEnter(skipOn = Advice.OnDefaultValue.class)
        public static boolean enter(@Advice.Argument(0) ContextualAction action) {
            return PerspectiveInteract.allowsExecution(action);
        }
    }
}
