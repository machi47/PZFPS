package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import zombie.characters.ContextualAction;
import zombie.iso.IsoObject;

final class PerspectiveInteractTest {
    @Test
    void prefersHighestPriorityValidatedActionForExactReticleObject() {
        IsoObject originalObject = new IsoObject();
        IsoObject target = new IsoObject();
        ContextualAction original = action(originalObject, 50, false);
        ContextualAction lowTarget = action(target, 10, false);
        ContextualAction highTarget = action(target, 20, false);

        assertSame(
                highTarget,
                PerspectiveInteract.preferTarget(
                        List.of(original, lowTarget, highTarget), original, target));
    }

    @Test
    void retainsPzChoiceWhenTargetHasNoValidatedAction() {
        ContextualAction original = action(new IsoObject(), 50, false);

        assertSame(
                original,
                PerspectiveInteract.preferTarget(
                        List.of(original), original, new IsoObject()));
    }

    @Test
    void preservesPzBehindTieBreakWithinExactTarget() {
        IsoObject target = new IsoObject();
        ContextualAction front = action(target, 20, false);
        ContextualAction behindTie = action(target, 20, true);
        ContextualAction behindHigher = action(target, 21, true);

        assertSame(
                front,
                PerspectiveInteract.preferTarget(
                        List.of(front, behindTie), behindTie, target));
        assertSame(
                behindHigher,
                PerspectiveInteract.preferTarget(
                        List.of(front, behindHigher), front, target));
    }

    @Test
    void preventsAnotherNearbyActionWhenPinnedTargetHasNoValidatedAction() {
        IsoObject target = new IsoObject();
        ContextualAction other = action(new IsoObject(), 50, false);
        ContextualAction exact = action(target, 10, false);

        assertTrue(PerspectiveInteract.allowsExecution(other, null));
        assertFalse(PerspectiveInteract.allowsExecution(other, target));
        assertTrue(PerspectiveInteract.allowsExecution(exact, target));
    }

    private static ContextualAction action(IsoObject object, int priority, boolean behind) {
        ContextualAction value = new ContextualAction();
        value.object = object;
        value.priority = priority;
        value.behind = behind;
        return value;
    }
}
