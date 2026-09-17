package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import org.junit.jupiter.api.Test;
import zombie.characters.ContextualAction;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.physics.BallisticsController;
import zombie.input.Mouse;

final class DirectPatchInstallerTest {
    @Test
    void matchesInstalledVectorInputDescriptors() {
        TypeDescription player = new TypeDescription.ForLoadedType(IsoPlayer.class);
        DirectPatchInstaller.requireOneTarget(
                player,
                DirectPatchInstaller.inputMoveMatcher(),
                "getInputMoveVector(Lzombie/iso/Vector2;)Lzombie/iso/Vector2;");
        DirectPatchInstaller.requireOneTarget(
                player,
                DirectPatchInstaller.aimMatcher(),
                "getAimVector(Lzombie/iso/Vector2;)Lzombie/iso/Vector2;");
        DirectPatchInstaller.requireOneTarget(
                player,
                DirectPatchInstaller.calculateAimMatcher(),
                "calculateAimVector(Lzombie/iso/Vector2;)Lzombie/iso/Vector2;");
        DirectPatchInstaller.requireOneTarget(
                player,
                DirectPatchInstaller.setAngleFromAimMatcher(),
                "setAngleFromAim()V");
        DirectPatchInstaller.requireOneTarget(
                player,
                DirectPatchInstaller.doContextMatcher(),
                "doContext()Z");
        DirectPatchInstaller.requireOneTarget(
                player,
                DirectPatchInstaller.pickContextMatcher(),
                "pickBestContextualAction(Ljava/util/ArrayList;)Lzombie/characters/ContextualAction;");
        DirectPatchInstaller.requireOneTarget(
                player,
                DirectPatchInstaller.performContextMatcher(),
                "performContextualAction(Lzombie/characters/ContextualAction;)V");
    }

    @Test
    void matchesInstalledBallisticsDescriptors() {
        TypeDescription ballistics = new TypeDescription.ForLoadedType(BallisticsController.class);
        DirectPatchInstaller.requireOneTarget(
                ballistics,
                DirectPatchInstaller.ballisticsMuzzleMatcher(),
                "calculateMuzzlePosition(Lzombie/iso/Vector3;Lzombie/iso/Vector3;)F");
        DirectPatchInstaller.requireOneTarget(
                ballistics,
                DirectPatchInstaller.ballisticsCameraTargetsMatcher(),
                "getCameraTargets(FZ)V");
    }

    @Test
    void matchesAndInlinesInstalledLocomotionDescriptors() {
        TypeDescription character = new TypeDescription.ForLoadedType(IsoGameCharacter.class);
        DirectPatchInstaller.requireOneTarget(
                character,
                DirectPatchInstaller.deferredMovementMatcher(),
                "getDeferredMovement(Lzombie/iso/Vector2;Z)Lzombie/iso/Vector2;");
        DirectPatchInstaller.requireOneTarget(
                character,
                DirectPatchInstaller.isStrafingMatcher(),
                "isStrafing()Z");
        byte[] transformed = new ByteBuddy()
                .redefine(IsoGameCharacter.class)
                .visit(Advice.to(LocomotionPatch.DeferredMovement.class)
                        .on(DirectPatchInstaller.deferredMovementMatcher()))
                .visit(Advice.to(LocomotionPatch.StrafePresentation.class)
                        .on(DirectPatchInstaller.isStrafingMatcher()))
                .make()
                .getBytes();
        assertTrue(transformed.length > 0);
    }

    @Test
    void matchesAndInlinesInstalledCursorVisibilityDescriptor() {
        TypeDescription mouse = new TypeDescription.ForLoadedType(Mouse.class);
        DirectPatchInstaller.requireOneTarget(
                mouse,
                DirectPatchInstaller.mouseCursorVisibilityMatcher(),
                "setCursorVisible(Z)V");
        byte[] transformed = new ByteBuddy()
                .redefine(Mouse.class)
                .visit(Advice.to(CursorVisibilityPatch.class)
                        .on(DirectPatchInstaller.mouseCursorVisibilityMatcher()))
                .make()
                .getBytes();
        assertTrue(transformed.length > 0);
    }

    @Test
    void matchesAndInlinesInstalledPointerGrabDescriptor() {
        TypeDescription mouse = new TypeDescription.ForLoadedType(org.lwjglx.input.Mouse.class);
        DirectPatchInstaller.requireOneTarget(
                mouse, DirectPatchInstaller.pointerGrabMatcher(), "setGrabbed(Z)V");
        byte[] transformed = new ByteBuddy()
                .redefine(org.lwjglx.input.Mouse.class)
                .visit(Advice.to(PointerGrabPatch.class)
                        .on(DirectPatchInstaller.pointerGrabMatcher()))
                .make()
                .getBytes();
        assertTrue(transformed.length > 0);
    }

    @Test
    void inlinesBallisticsAdviceIntoInstalledClass() {
        byte[] transformed = new ByteBuddy()
                .redefine(BallisticsController.class)
                .visit(Advice.to(BallisticsAimPatch.Muzzle.class)
                        .on(DirectPatchInstaller.ballisticsMuzzleMatcher()))
                .visit(Advice.to(BallisticsAimPatch.CameraTargets.class)
                        .on(DirectPatchInstaller.ballisticsCameraTargetsMatcher()))
                .make()
                .getBytes();

        assertTrue(transformed.length > 0);
    }

    @Test
    void inlinesContextSelectionAdviceIntoInstalledClass() {
        byte[] transformed = new ByteBuddy()
                .redefine(IsoPlayer.class)
                .visit(Advice.to(ContextActionPatch.Scope.class)
                        .on(DirectPatchInstaller.doContextMatcher()))
                .visit(Advice.to(ContextActionPatch.PickBest.class)
                        .on(DirectPatchInstaller.pickContextMatcher()))
                .visit(Advice.to(ContextActionPatch.Execute.class)
                        .on(DirectPatchInstaller.performContextMatcher()))
                .make()
                .getBytes();

        assertTrue(transformed.length > 0);
    }

    @Test
    void exposesEveryTypeAndOperationReferencedByInlinedWorldAdvice() throws Exception {
        assertWorldAdviceApi(
                NativeActorPass.class, NativeActorPass.PreparedFrame.class, true);
        assertWorldAdviceApi(
                NativeVehiclePass.class, NativeVehiclePass.PreparedFrame.class, true);
        assertWorldAdviceApi(
                NativeFirstPersonHandsPass.class,
                NativeFirstPersonHandsPass.PreparedFrame.class,
                false);
    }

    @Test
    void exposesEveryTypeAndOperationReferencedByInlinedInteractionAndBallisticsAdvice()
            throws Exception {
        assertTrue(Modifier.isPublic(PerspectiveInteract.class.getModifiers()));
        assertTrue(Modifier.isPublic(
                PerspectiveInteract.class.getDeclaredMethod("end").getModifiers()));
        assertTrue(Modifier.isPublic(
                PerspectiveInteract.class
                        .getDeclaredMethod(
                                "preferTarget", java.util.List.class, ContextualAction.class)
                        .getModifiers()));
        assertTrue(Modifier.isPublic(
                PerspectiveInteract.class
                        .getDeclaredMethod("allowsExecution", ContextualAction.class)
                        .getModifiers()));

        assertTrue(Modifier.isPublic(PerspectiveBallistics.class.getModifiers()));
        assertTrue(Modifier.isPublic(
                PerspectiveBallistics.class
                        .getDeclaredMethod(
                                "overrideMuzzleDirection",
                                zombie.characters.IsoGameCharacter.class,
                                zombie.iso.Vector3.class)
                        .getModifiers()));
        assertTrue(Modifier.isPublic(
                PerspectiveBallistics.class
                        .getDeclaredMethod(
                                "configureNativeCameraRay",
                                BallisticsController.class,
                                zombie.characters.IsoGameCharacter.class)
                        .getModifiers()));
    }

    private static void assertWorldAdviceApi(
            Class<?> owner, Class<?> frame, boolean exposesEntityIds) throws Exception {
        assertTrue(Modifier.isPublic(owner.getModifiers()));
        assertTrue(Modifier.isPublic(frame.getModifiers()));
        assertTrue(Modifier.isPublic(frame.getDeclaredMethod("queueAfterWorld").getModifiers()));
        assertTrue(Modifier.isPublic(frame.getDeclaredMethod("discard").getModifiers()));
        if (exposesEntityIds) {
            assertTrue(Modifier.isPublic(frame.getDeclaredMethod("entityIds").getModifiers()));
        }
    }
}
