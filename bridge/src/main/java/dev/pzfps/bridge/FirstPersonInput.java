package dev.pzfps.bridge;

import org.lwjglx.input.Keyboard;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.input.GameKeyboard;
import zombie.input.Mouse;
import zombie.iso.Vector2;
import zombie.ui.UIElementInterface;
import zombie.ui.UIManager;

/** Native single-window mouse look and camera-relative movement for the local PZ client. */
public final class FirstPersonInput {
    private static final String CAPTURE_ACTION = "PZFPS Mouse Capture";
    private static final float MOUSE_RADIANS_PER_PIXEL =
            Float.parseFloat(System.getProperty("pzfps.mouseSensitivity", "0.0025"));
    private static final float MAX_PITCH = (float) Math.toRadians(89.0);
    private static final CursorCaptureState CURSOR = new CursorCaptureState(2);
    private static boolean initialized;
    private static boolean hardwareCaptured;
    private static float yaw;
    private static float pitch;
    private static MovementRequest lastMovementRequest = MovementRequest.inactive();
    private static MovementRequest deferredMovementRequest = MovementRequest.inactive();
    private static float lastLoggedForward = Float.NaN;
    private static float lastLoggedStrafe = Float.NaN;
    private static boolean lastLoggedCaptured;
    private static boolean menuContinueRequested;
    private static int menuProbeTicks;

    private FirstPersonInput() {}

    /** Runs only at the verified PZ local-player/render boundary. */
    public static void update(IsoPlayer player) {
        if (!initialized) {
            yaw = normalizeYaw(
                    (float) Math.atan2(player.getForwardDirectionY(), player.getForwardDirectionX()));
            pitch = clamp(
                    (float) Math.toRadians(player.getCurrentVerticalAimAngle()),
                    -MAX_PITCH,
                    MAX_PITCH);
            initialized = true;
            applyCaptureState();
            System.out.printf(
                    "[PZFPS input] mouse look captured; %s releases/captures the UI cursor%n",
                    Keyboard.getKeyName(captureKey()));
            return;
        }

    }

    /** Consumes each PZ mouse poll at most once, after Mouse.update has published it. */
    public static void onMouseUpdate() {
        if (!initialized) {
            maybeContinueDisposableSave();
            return;
        }
        boolean togglePressed = GameKeyboard.isKeyPressed(captureKey());
        boolean uiWantsCursor = uiWantsCursor(
                GameKeyboard.isKeyPressed(Keyboard.KEY_ESCAPE),
                GameKeyboard.isKeyPressed("Toggle Inventory"),
                UIManager.isModalVisible(),
                hasVisibleForceCursorUi());
        CursorCaptureState.Mode previousMode = CURSOR.mode();
        if (CURSOR.update(togglePressed, uiWantsCursor)) {
            applyCaptureState();
            System.out.printf(
                    "[PZFPS input] cursor mode %s -> %s%n", previousMode, CURSOR.mode());
        }
        if (!CURSOR.captured()) return;

        // GLFW's disabled-cursor mode supplies unbounded, window-relative deltas and
        // restores the prior pointer position when released.  Do not warp through
        // zombie.input.Mouse.setXY(): that method mixes offscreen-render and window
        // coordinates and can throw the native macOS pointer outside the PZ window.
        int deltaX = org.lwjglx.input.Mouse.getDX();
        int deltaY = org.lwjglx.input.Mouse.getDY();
        if (deltaX != 0 || deltaY != 0) {
            yaw = normalizeYaw(yaw + deltaX * MOUSE_RADIANS_PER_PIXEL);
            pitch = clamp(pitch + deltaY * MOUSE_RADIANS_PER_PIXEL, -MAX_PITCH, MAX_PITCH);
        }
        Mouse.setCursorVisible(false);
    }

    static boolean uiWantsCursor(
            boolean escapePressed,
            boolean inventoryTogglePressed,
            boolean modalVisible,
            boolean forceCursorVisible) {
        // Release on the same input poll that asks PZ to expose inventory. The Lua handler then
        // marks the visible inventory/loot pair as force-cursor owners for its entire lifetime.
        return escapePressed || inventoryTogglePressed || modalVisible || forceCursorVisible;
    }

    /**
     * Do not use {@link UIManager#isForceCursorVisible()} here: B42 folds ordinary
     * mouse-over into that answer, so a remembered pointer position over a collapsed UI strip can
     * spuriously release relative mouse look. Only explicit force-cursor elements own the cursor.
     */
    private static boolean hasVisibleForceCursorUi() {
        for (UIElementInterface element : UIManager.getUI()) {
            if (element != null
                    && Boolean.TRUE.equals(element.isVisible())
                    && element.isForceCursorVisible()) {
                return true;
            }
        }
        return false;
    }

    /** Uses PZ's own menu function, only in the explicitly isolated project profile. */
    private static void maybeContinueDisposableSave() {
        if (!Boolean.getBoolean("pzfps.autoContinue")
                || menuContinueRequested
                || (++menuProbeTicks % 30) != 0
                || LuaManager.env == null
                || LuaManager.thread == null
                || LuaManager.caller == null) {
            return;
        }
        try {
            Object value = LuaManager.env.rawget("MainScreen");
            if (!(value instanceof KahluaTable mainScreen)) return;
            Object gameMode = mainScreen.rawget("latestSaveGameMode");
            Object world = mainScreen.rawget("latestSaveWorld");
            Object continueFunction = mainScreen.rawget("continueLatestSave");
            if (!(gameMode instanceof String)
                    || !(world instanceof String)
                    || continueFunction == null) {
                return;
            }
            menuContinueRequested = true;
            LuaManager.caller.pcallvoid(
                    LuaManager.thread, continueFunction, gameMode, world);
            System.out.printf(
                    "[PZFPS input] requested project-local save through PZ MainScreen mode=%s world=%s%n",
                    gameMode, world);
        } catch (RuntimeException error) {
            menuContinueRequested = false;
            System.err.printf("[PZFPS input] project-local menu continue failed: %s%n", error);
        }
    }

    public static boolean isCaptured() {
        return initialized && CURSOR.captured();
    }

    /**
     * PZ's shove/aim transition may request its isometric cursor after Mouse.update(), while the
     * GLFW pointer remains grabbed and still supplies FPS deltas. Reject only that contradictory
     * request. Deliberate F8/UI release changes CURSOR first, so its visible request is preserved.
     */
    public static boolean filterCursorVisibilityRequest(boolean requestedVisible) {
        return filterCursorVisibilityRequest(requestedVisible, isCaptured());
    }

    static boolean filterCursorVisibilityRequest(boolean requestedVisible, boolean captured) {
        return requestedVisible && !captured;
    }

    /**
     * The FPS coordinate system remains authoritative while its cursor is released for PZ UI.
     * Capture controls only relative mouse deltas; it must never switch WASD, character facing or
     * aiming back to PZ's isometric interpretation.
     */
    public static boolean isPerspectiveActive() {
        return initialized;
    }

    /**
     * The isolated auto-continue path reaches B42's frame-polled "Click to Start" gate after
     * requesting the known disposable save. GUI automation emits a shorter click than that poll
     * reliably observes, so hold only the loading-screen left-button state until the first real
     * player update initializes FPS input. This cannot activate on an ordinary/non-isolated run.
     */
    public static boolean shouldAdvanceDisposableLoadingScreen() {
        return shouldAdvanceDisposableLoadingScreen(
                Boolean.getBoolean("pzfps.autoContinue"), menuContinueRequested, initialized);
    }

    static boolean shouldAdvanceDisposableLoadingScreen(
            boolean autoContinue, boolean continueRequested, boolean playerInitialized) {
        return autoContinue && continueRequested && !playerInitialized;
    }

    /** Called immediately before opening a PZ-owned context/menu surface. */
    public static void releaseForUi() {
        if (!initialized) return;
        CursorCaptureState.Mode previousMode = CURSOR.mode();
        if (CURSOR.releaseForUi()) {
            applyCaptureState();
            System.out.printf(
                    "[PZFPS input] cursor mode %s -> %s%n", previousMode, CURSOR.mode());
        }
    }

    public static float yaw() {
        return yaw;
    }

    public static float pitch() {
        return pitch;
    }

    public static Vector2 movementVector(Vector2 value) {
        if (!isPerspectiveActive()) {
            lastMovementRequest = MovementRequest.inactive();
            return value;
        }
        float forward = boundKeyAxis("Forward", "Backward");
        float strafe = boundKeyAxis("Right", "Left");
        logMovementChange(forward, strafe);
        if (forward != 0.0f || strafe != 0.0f) {
            return rotateDigitalMovement(value, yaw, forward, strafe);
        }
        lastMovementRequest = MovementRequest.inactive();
        return value;
    }

    public static Vector2 aimVector(Vector2 value) {
        if (!isPerspectiveActive()) return value;
        return value.set((float) Math.cos(yaw), (float) Math.sin(yaw));
    }

    public static boolean requiresStrafePresentation() {
        InputState.Sample external = InputState.current();
        if (external.active()) return external.strafe() != 0.0f || external.forward() < 0.0f;
        return isPerspectiveActive()
                && (isBoundKeyDown("Left")
                        || isBoundKeyDown("Right")
                        || isBoundKeyDown("Backward"));
    }

    public static boolean requiresStrafePresentation(IsoGameCharacter character) {
        return character instanceof IsoPlayer player
                && player.isLocalPlayer()
                && normalLocomotion(player)
                && requiresStrafePresentation();
    }

    /**
     * PZ's walk/run clips emit root motion in the body's facing direction. FPS locomotion instead
     * keeps the body on camera yaw and applies that native movement magnitude along the previous
     * frame's camera-relative WASD request. PZ's own moveUnmodded path still performs collision,
     * bumping, vault checks, endurance and authoritative position updates after this vector.
     */
    public static void redirectDeferredMovement(
            IsoGameCharacter character, Vector2 movement) {
        if (!(character instanceof IsoPlayer player)
                || !player.isLocalPlayer()
                || !normalLocomotion(player)) {
            return;
        }
        redirectDeferredMovement(movement, deferredMovementRequest, true);
    }

    static void redirectDeferredMovement(
            Vector2 movement, MovementRequest request, boolean eligible) {
        if (!eligible || movement == null || request == null || !request.active()) return;
        float length = movement.getLength();
        if (length <= 0.000001f) return;
        movement.set(request.worldX(), request.worldY());
        if (movement.getLengthSquared() <= 0.000001f) return;
        movement.setLength(length);
    }

    /** Normal locomotion only; canned actions retain their authored facing and root motion. */
    public static boolean normalLocomotion(IsoPlayer player) {
        return player != null
                && isPerspectiveActive()
                && !player.isDead()
                && player.getVehicle() == null
                && !player.isRagdoll()
                && !player.isClimbing()
                && !player.isBlockMovement()
                && !player.getIgnoreMovement()
                && !player.isPerformingAnAction();
    }

    static Vector2 rotateDigitalMovement(
            Vector2 value, float cameraYaw, float forward, float strafe) {
        float sourceSpeed = value.getLength();
        if (sourceSpeed <= 0.0001f) sourceSpeed = 1.0f;
        lastMovementRequest = movementFor(cameraYaw, forward, strafe, sourceSpeed);
        float worldX = lastMovementRequest.worldX();
        float worldY = lastMovementRequest.worldY();

        // IsoPlayer.UpdateMovementFromInput applies its isometric keyboard transform
        // after getInputMoveVector returns:
        //   playerMoveDir.x = input.y + input.x
        //   playerMoveDir.y = input.y - input.x
        // Return the exact inverse so that the resulting world-space motion follows
        // the FPS camera rather than being rotated through the isometric axes twice.
        return value.set((worldX - worldY) * 0.5f, (worldX + worldY) * 0.5f);
    }

    static MovementRequest movementFor(
            float cameraYaw, float forward, float strafe, float sourceSpeed) {
        float axisLength = (float) Math.sqrt(forward * forward + strafe * strafe);
        if (axisLength > 1.0f) {
            forward /= axisLength;
            strafe /= axisLength;
        }
        float sin = (float) Math.sin(cameraYaw);
        float cos = (float) Math.cos(cameraYaw);
        float worldX = (forward * cos - strafe * sin) * sourceSpeed;
        float worldY = (forward * sin + strafe * cos) * sourceSpeed;
        return new MovementRequest(
                forward != 0.0f || strafe != 0.0f,
                forward,
                strafe,
                worldX,
                worldY,
                sourceSpeed);
    }

    static MovementRequest lastMovementRequest() {
        return lastMovementRequest;
    }

    static MovementRequest deferredMovementRequest() {
        return deferredMovementRequest;
    }

    static void beginMovementSample() {
        lastMovementRequest = MovementRequest.inactive();
    }

    static void finishMovementSample() {
        deferredMovementRequest = lastMovementRequest;
    }

    record MovementRequest(
            boolean active,
            float forward,
            float strafe,
            float worldX,
            float worldY,
            float sourceSpeed) {
        static MovementRequest inactive() {
            return new MovementRequest(false, 0, 0, 0, 0, 0);
        }
    }

    private static float boundKeyAxis(String positive, String negative) {
        float value = isBoundKeyDown(positive) ? 1.0f : 0.0f;
        if (isBoundKeyDown(negative)) value -= 1.0f;
        return value;
    }

    /** Read the actual configured physical keys together; PZ's action wrapper can be sequential. */
    private static boolean isBoundKeyDown(String action) {
        int key = Core.getInstance().getKey(action);
        return key != Keyboard.KEY_NONE && Keyboard.isKeyDown(key);
    }

    private static void logMovementChange(float forward, float strafe) {
        boolean captured = isCaptured();
        if (forward == lastLoggedForward
                && strafe == lastLoggedStrafe
                && captured == lastLoggedCaptured) {
            return;
        }
        lastLoggedForward = forward;
        lastLoggedStrafe = strafe;
        lastLoggedCaptured = captured;
        MovementRequest request = movementFor(yaw, forward, strafe, 1.0f);
        System.out.printf(
                "[PZFPS input] axes forward=%.0f strafe=%.0f yaw=%.3f world=(%.3f,%.3f) cursorCaptured=%s%n",
                forward,
                strafe,
                yaw,
                request.worldX(),
                request.worldY(),
                captured);
    }

    private static void applyCaptureState() {
        boolean value = CURSOR.captured();
        if (hardwareCaptured == value) return;
        hardwareCaptured = value;
        org.lwjglx.input.Mouse.setGrabbed(value);
        Mouse.setCursorVisible(!value);
    }

    private static int captureKey() {
        String configured = System.getProperty("pzfps.captureKey", "").trim();
        if (!configured.isEmpty()) {
            String name = configured.toUpperCase(java.util.Locale.ROOT);
            if (name.startsWith("KEY_")) name = name.substring(4);
            int key = Keyboard.getKeyIndex(name);
            if (key == Keyboard.KEY_NONE) {
                throw new IllegalArgumentException("unknown pzfps.captureKey: " + configured);
            }
            return key;
        }
        int registered = Core.getInstance().getKey(CAPTURE_ACTION);
        return registered == Keyboard.KEY_NONE ? Keyboard.KEY_F8 : registered;
    }

    private static float normalizeYaw(float value) {
        float twoPi = (float) (Math.PI * 2.0);
        value %= twoPi;
        return value < 0.0f ? value + twoPi : value;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
