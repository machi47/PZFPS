package dev.pzfps.bridge;

import org.lwjglx.input.Keyboard;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
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
        boolean uiWantsCursor = GameKeyboard.isKeyPressed(Keyboard.KEY_ESCAPE)
                || UIManager.isModalVisible()
                || hasVisibleForceCursorUi();
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
        if (!isCaptured()) {
            lastMovementRequest = MovementRequest.inactive();
            return value;
        }
        float forward = boundKeyAxis("Forward", "Backward");
        float strafe = boundKeyAxis("Right", "Left");
        if (forward != 0.0f || strafe != 0.0f) {
            return rotateDigitalMovement(value, yaw, forward, strafe);
        }
        lastMovementRequest = MovementRequest.inactive();
        return value;
    }

    public static Vector2 aimVector(Vector2 value) {
        if (!isCaptured()) return value;
        return value.set((float) Math.cos(yaw), (float) Math.sin(yaw));
    }

    public static boolean requiresStrafePresentation() {
        return isCaptured()
                && (isBoundKeyDown("Left")
                        || isBoundKeyDown("Right")
                        || isBoundKeyDown("Backward"));
    }

    static Vector2 rotateDigitalMovement(
            Vector2 value, float cameraYaw, float forward, float strafe) {
        float sourceSpeed = value.getLength();
        if (sourceSpeed <= 0.0001f) sourceSpeed = 1.0f;
        float axisLength = (float) Math.sqrt(forward * forward + strafe * strafe);
        if (axisLength > 1.0f) {
            forward /= axisLength;
            strafe /= axisLength;
        }
        float sin = (float) Math.sin(cameraYaw);
        float cos = (float) Math.cos(cameraYaw);
        float worldX = (forward * cos - strafe * sin) * sourceSpeed;
        float worldY = (forward * sin + strafe * cos) * sourceSpeed;
        lastMovementRequest = new MovementRequest(
                true, forward, strafe, worldX, worldY, sourceSpeed);

        // IsoPlayer.UpdateMovementFromInput applies its isometric keyboard transform
        // after getInputMoveVector returns:
        //   playerMoveDir.x = input.y + input.x
        //   playerMoveDir.y = input.y - input.x
        // Return the exact inverse so that the resulting world-space motion follows
        // the FPS camera rather than being rotated through the isometric axes twice.
        return value.set((worldX - worldY) * 0.5f, (worldX + worldY) * 0.5f);
    }

    static MovementRequest lastMovementRequest() {
        return lastMovementRequest;
    }

    static void beginMovementSample() {
        lastMovementRequest = MovementRequest.inactive();
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
