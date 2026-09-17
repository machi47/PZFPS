package dev.pzfps.bridge;

import java.util.List;
import zombie.characters.ContextualAction;
import zombie.iso.IsoObject;

/** Pins B42's own validated short-press contextual action to the FPS reticle object. */
final class PerspectiveInteract {
    private static final ThreadLocal<IsoObject> TARGET = new ThreadLocal<>();

    private PerspectiveInteract() {}

    static void begin(IsoObject target) {
        if (target == null) TARGET.remove();
        else TARGET.set(target);
    }

    static void end() {
        TARGET.remove();
    }

    static ContextualAction preferTarget(
            List<ContextualAction> actions, ContextualAction original) {
        return preferTarget(actions, original, TARGET.get());
    }

    /** Prevents a different nearby isometric action from firing when the target has no action. */
    static boolean allowsExecution(ContextualAction action) {
        return allowsExecution(action, TARGET.get());
    }

    static boolean allowsExecution(ContextualAction action, IsoObject target) {
        return target == null || (action != null && action.object == target);
    }

    /** Mirrors B42's priority/behind ordering, but only among actions for the exact live target. */
    static ContextualAction preferTarget(
            List<ContextualAction> actions, ContextualAction original, IsoObject target) {
        if (target == null || actions == null || actions.isEmpty()) return original;
        ContextualAction bestFront = null;
        ContextualAction bestBehind = null;
        for (ContextualAction action : actions) {
            if (action == null || action.object != target) continue;
            if (action.behind) {
                if (bestBehind == null || action.priority > bestBehind.priority) {
                    bestBehind = action;
                }
            } else if (bestFront == null || action.priority > bestFront.priority) {
                bestFront = action;
            }
        }
        if (bestFront == null) return bestBehind == null ? original : bestBehind;
        if (bestBehind != null && bestBehind.priority > bestFront.priority) return bestBehind;
        return bestFront;
    }
}
