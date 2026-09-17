package dev.pzfps.bridge;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoObject;

/** Publishes what the single centre-view ray currently tracks without authorizing an action. */
final class ReticleTracker {
    static final String LUA_KIND = "PZFPS_ReticleTargetKind";
    static final String LUA_TARGETS = "PZFPS_ReticleCombatTargets";
    private static final long PROBE_INTERVAL_NANOS = 50_000_000L;
    private static final AtomicBoolean PUBLISH_FAILURE_REPORTED = new AtomicBoolean();
    private static long lastProbeNanos;
    private static String worldKind = "none";
    private static String publishedKind = "";
    private static int publishedTargets = -1;

    private ReticleTracker() {}

    /** Runs on the game thread and tracks only identity-resolved objects inside normal reach. */
    static void updateWorld(
            IsoPlayer player,
            WorldState.Player viewpoint,
            Collection<WorldState.Chunk> chunks,
            float maximumReach,
            long nowNanos) {
        if (nowNanos - lastProbeNanos < PROBE_INTERVAL_NANOS) return;
        lastProbeNanos = nowNanos;
        worldKind = resolveWorldKind(player, viewpoint, chunks, maximumReach);
        publish(worldKind, 0);
    }

    /** Called after B42 has completed its own native camera-target query. */
    static void observeCombatTargets(int targetCount) {
        publish(targetCount > 0 ? "combat" : worldKind, Math.max(0, targetCount));
    }

    private static String resolveWorldKind(
            IsoPlayer player,
            WorldState.Player viewpoint,
            Collection<WorldState.Chunk> chunks,
            float maximumReach) {
        List<InteractionTarget.Reference> candidates =
                InteractionTarget.contextCandidates(viewpoint, chunks, maximumReach);
        for (InteractionTarget.Reference reference : candidates) {
            IsoObject live = InteractionTarget.resolveLive(player, reference, maximumReach);
            if (live == null) continue;
            return kindFor(reference);
        }
        return "none";
    }

    static String kindFor(InteractionTarget.Reference reference) {
        if (reference == null) return "none";
        if (reference.door()
                || reference.window()
                || reference.container()
                || reference.worldItemId() >= 0) {
            return "interact";
        }
        return "world";
    }

    private static void publish(String kind, int combatTargets) {
        if (kind.equals(publishedKind) && combatTargets == publishedTargets) return;
        KahluaTable environment = LuaManager.env;
        if (environment == null) return;
        try {
            environment.rawset(LUA_KIND, kind);
            environment.rawset(LUA_TARGETS, (double) combatTargets);
            publishedKind = kind;
            publishedTargets = combatTargets;
        } catch (RuntimeException | LinkageError error) {
            if (PUBLISH_FAILURE_REPORTED.compareAndSet(false, true)) {
                System.err.printf("[PZFPS reticle] UI-state publication failed: %s%n", error);
            }
        }
    }
}
