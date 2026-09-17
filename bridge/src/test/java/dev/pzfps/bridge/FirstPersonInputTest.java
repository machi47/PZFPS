package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import zombie.iso.Vector2;

final class FirstPersonInputTest {
    @Test
    void makesForwardInputFollowCameraYaw() {
        Vector2 input = FirstPersonInput.rotateDigitalMovement(
                new Vector2(0.0f, -1.95f), (float) (Math.PI / 2.0), 1.0f, 0.0f);
        Vector2 world = pzWorldMovement(input);
        assertEquals(0.0f, world.x, 0.0001f);
        assertEquals(1.95f, world.y, 0.0001f);
    }

    @Test
    void makesStrafeInputPerpendicularToCamera() {
        Vector2 input = FirstPersonInput.rotateDigitalMovement(
                new Vector2(1.95f, 0.0f), 0.0f, 0.0f, 1.0f);
        Vector2 world = pzWorldMovement(input);
        assertEquals(0.0f, world.x, 0.0001f);
        assertEquals(1.95f, world.y, 0.0001f);
    }

    @Test
    void mapsAllFourDigitalDirectionsWithoutCollapsingThemToForward() {
        float yaw = 0.37f;
        Vector2 forward = pzWorldMovement(FirstPersonInput.rotateDigitalMovement(
                new Vector2(0, -1), yaw, 1, 0));
        Vector2 backward = pzWorldMovement(FirstPersonInput.rotateDigitalMovement(
                new Vector2(0, 1), yaw, -1, 0));
        Vector2 right = pzWorldMovement(FirstPersonInput.rotateDigitalMovement(
                new Vector2(1, 0), yaw, 0, 1));
        Vector2 left = pzWorldMovement(FirstPersonInput.rotateDigitalMovement(
                new Vector2(-1, 0), yaw, 0, -1));

        assertEquals(-forward.x, backward.x, 0.0001f);
        assertEquals(-forward.y, backward.y, 0.0001f);
        assertEquals(-right.x, left.x, 0.0001f);
        assertEquals(-right.y, left.y, 0.0001f);
        assertEquals(0.0f, forward.x * right.x + forward.y * right.y, 0.0001f);
    }

    @Test
    void mapsOppositeDiagonalKeysToOppositeSidesOfForward() {
        float yaw = 0.73f;
        FirstPersonInput.MovementRequest forward = FirstPersonInput.movementFor(yaw, 1, 0, 1);
        FirstPersonInput.MovementRequest forwardLeft =
                FirstPersonInput.movementFor(yaw, 1, -1, 1);
        FirstPersonInput.MovementRequest forwardRight =
                FirstPersonInput.movementFor(yaw, 1, 1, 1);
        float leftSide = signedSide(forward, forwardLeft);
        float rightSide = signedSide(forward, forwardRight);

        assertTrue(leftSide < 0.0f);
        assertTrue(rightSide > 0.0f);
        assertEquals(-leftSide, rightSide, 0.0001f);
    }

    @Test
    void releasesImmediatelyForInventoryAndOtherCursorOwningUi() {
        assertTrue(FirstPersonInput.uiWantsCursor(false, true, false, false));
        assertTrue(FirstPersonInput.uiWantsCursor(true, false, false, false));
        assertTrue(FirstPersonInput.uiWantsCursor(false, false, true, false));
        assertTrue(FirstPersonInput.uiWantsCursor(false, false, false, true));
        assertFalse(FirstPersonInput.uiWantsCursor(false, false, false, false));
    }

    @Test
    void advancesOnlyTheRequestedIsolatedSaveLoadingGate() {
        assertTrue(FirstPersonInput.shouldAdvanceDisposableLoadingScreen(true, true, false));
        assertFalse(FirstPersonInput.shouldAdvanceDisposableLoadingScreen(false, true, false));
        assertFalse(FirstPersonInput.shouldAdvanceDisposableLoadingScreen(true, false, false));
        assertFalse(FirstPersonInput.shouldAdvanceDisposableLoadingScreen(true, true, true));
    }

    @Test
    void handsCurrentIntentToTheNextDeferredMovementUpdate() {
        FirstPersonInput.beginMovementSample();
        FirstPersonInput.rotateDigitalMovement(new Vector2(0, -1), 0.4f, 1, 1);
        FirstPersonInput.finishMovementSample();

        FirstPersonInput.MovementRequest deferred =
                FirstPersonInput.deferredMovementRequest();
        assertTrue(deferred.active());
        float diagonal = (float) (1.0 / Math.sqrt(2.0));
        assertEquals(diagonal, deferred.forward(), 0.0001f);
        assertEquals(diagonal, deferred.strafe(), 0.0001f);

        FirstPersonInput.beginMovementSample();
        assertFalse(FirstPersonInput.lastMovementRequest().active());
        assertTrue(FirstPersonInput.deferredMovementRequest().active());
    }

    private static Vector2 pzWorldMovement(Vector2 input) {
        return new Vector2(input.y + input.x, input.y - input.x);
    }

    private static float signedSide(
            FirstPersonInput.MovementRequest forward,
            FirstPersonInput.MovementRequest diagonal) {
        return forward.worldX() * diagonal.worldY()
                - forward.worldY() * diagonal.worldX();
    }
}
