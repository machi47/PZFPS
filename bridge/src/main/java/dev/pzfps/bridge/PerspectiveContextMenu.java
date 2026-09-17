package dev.pzfps.bridge;

import zombie.Lua.LuaManager;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoObject;

/** Hands an identity-checked perspective target to PZ's own B42 context-menu builder. */
final class PerspectiveContextMenu {
    private static final String TEST_FUNCTION = "PZFPS_HasWorldContext";
    private static final String OPEN_FUNCTION = "PZFPS_OpenWorldContext";

    private PerspectiveContextMenu() {}

    /** Uses B42's documented hidden test pass; it does not expose or execute an option. */
    static boolean hasOptions(IsoPlayer player, IsoObject object) {
        return call(TEST_FUNCTION, "context-menu test", player, object);
    }

    /** Must run on PZ's game thread. Returns true only when PZ produced a non-empty menu. */
    static boolean open(IsoPlayer player, IsoObject object) {
        return call(OPEN_FUNCTION, "context-menu request", player, object);
    }

    private static boolean call(
            String functionName, String operation, IsoPlayer player, IsoObject object) {
        if (player == null
                || object == null
                || LuaManager.env == null
                || LuaManager.thread == null
                || LuaManager.caller == null) {
            return false;
        }
        Object function = LuaManager.env.rawget(functionName);
        if (function == null) {
            System.err.printf("[PZFPS interaction] Lua function %s is unavailable%n", functionName);
            return false;
        }
        try {
            Boolean opened = LuaManager.caller.pcallBoolean(
                    LuaManager.thread,
                    function,
                    new Object[] {player.getIndex(), object});
            return Boolean.TRUE.equals(opened);
        } catch (RuntimeException error) {
            System.err.printf("[PZFPS interaction] %s failed: %s%n", operation, error);
            return false;
        }
    }
}
