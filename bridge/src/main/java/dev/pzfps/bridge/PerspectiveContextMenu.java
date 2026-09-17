package dev.pzfps.bridge;

import zombie.Lua.LuaManager;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoObject;

/** Hands an identity-checked perspective target to PZ's own B42 context-menu builder. */
final class PerspectiveContextMenu {
    private static final String LUA_FUNCTION = "PZFPS_OpenWorldContext";

    private PerspectiveContextMenu() {}

    /** Must run on PZ's game thread. Returns true only when PZ produced a non-empty menu. */
    static boolean open(IsoPlayer player, IsoObject object) {
        if (player == null
                || object == null
                || LuaManager.env == null
                || LuaManager.thread == null
                || LuaManager.caller == null) {
            return false;
        }
        Object function = LuaManager.env.rawget(LUA_FUNCTION);
        if (function == null) {
            System.err.printf("[PZFPS interaction] Lua function %s is unavailable%n", LUA_FUNCTION);
            return false;
        }
        try {
            Boolean opened = LuaManager.caller.pcallBoolean(
                    LuaManager.thread,
                    function,
                    new Object[] {player.getIndex(), object});
            return Boolean.TRUE.equals(opened);
        } catch (RuntimeException error) {
            System.err.printf("[PZFPS interaction] context-menu request failed: %s%n", error);
            return false;
        }
    }
}
