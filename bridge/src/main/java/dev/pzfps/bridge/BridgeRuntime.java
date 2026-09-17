package dev.pzfps.bridge;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoChunk;

/** Coordinates game-thread capture and the isolated transport worker. */
public final class BridgeRuntime {
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final AtomicBoolean RESNAPSHOT_REQUESTED = new AtomicBoolean(true);
    private static final AtomicLong FRAME_SEQUENCE = new AtomicLong();
    private static final AtomicBoolean WORLD_RENDER_SEEN = new AtomicBoolean();
    private static final AtomicBoolean FIRST_SNAPSHOT_SEEN = new AtomicBoolean();
    private static final Map<Long, Long> CHUNK_FINGERPRINTS = new HashMap<>();
    private static final Set<Long> LOADED_CHUNKS = new HashSet<>();
    private static BridgeConfig config;
    private static BridgeServer server;
    private static long lastEntityCapture;
    private static long lastWorldCapture;

    private BridgeRuntime() {}

    public static void start() {
        if (!STARTED.compareAndSet(false, true)) return;
        try {
            config = BridgeConfig.fromSystemProperties();
            server = new BridgeServer(config);
            server.start();
            String assetRegistry = System.getProperty("pzfps.assetRegistry", "").trim();
            if (!assetRegistry.isEmpty()) {
                InProcessWorldRenderer.start(Path.of(assetRegistry));
            } else if (Boolean.getBoolean("pzfps.renderer.enabled")) {
                System.err.println(
                        "[PZFPS] in-process renderer disabled: pzfps.assetRegistry is absent");
            }
            System.out.printf(
                    "[PZFPS] authoritative bridge started radius=%d worldHz=%.1f entityHz=%.1f%n",
                    config.chunkRadius(),
                    1_000_000_000.0 / config.worldIntervalNanos(),
                    1_000_000_000.0 / config.entityIntervalNanos());
        } catch (RuntimeException error) {
            STARTED.set(false);
            System.err.printf("[PZFPS] bridge startup failed: %s%n", error);
            throw error;
        }
    }

    public static void requestResnapshot() {
        RESNAPSHOT_REQUESTED.set(true);
    }

    public static void onPlayerUpdate(IsoPlayer player) {
        capture(player, true, true);
    }

    /**
     * B42 does not route every local-player presentation frame through IsoPlayer.update().
     * IsoWorld.render() is still invoked on the main render-state producer thread, where PZ
     * itself reads these objects. Use it as the reliable snapshot boundary, without mutating
     * input state during rendering.
     */
    public static void onWorldRender() {
        IsoPlayer player = IsoPlayer.players.length > 0 ? IsoPlayer.players[0] : null;
        if (WORLD_RENDER_SEEN.compareAndSet(false, true)) {
            System.out.printf("[PZFPS] world render boundary observed playerPresent=%s%n", player != null);
        }
        capture(player, false, false);
    }

    private static void capture(IsoPlayer player, boolean applyInput, boolean requireLocalIdentity) {
        if (!STARTED.get() || server == null || player == null) return;
        if (requireLocalIdentity && (!player.isLocalPlayer() || player.getIndex() != 0)) return;

        applyLookInput(player);
        long now = System.nanoTime();
        long epochMillis = System.currentTimeMillis();
        long sequence = FRAME_SEQUENCE.incrementAndGet();
        WorldState.Player playerSnapshot = WorldCapture.player(player, sequence, now, epochMillis);
        server.publishPlayer(playerSnapshot);
        InProcessWorldRenderer.acceptPlayer(playerSnapshot);
        if (FIRST_SNAPSHOT_SEEN.compareAndSet(false, true)) {
            System.out.printf(
                    "[PZFPS] first authoritative snapshot player=(%.3f,%.3f,%.3f)%n",
                    playerSnapshot.x(), playerSnapshot.y(), playerSnapshot.z());
        }

        if (now - lastEntityCapture >= config.entityIntervalNanos()) {
            lastEntityCapture = now;
            WorldState.Entities entities = WorldCapture.entities(player, sequence, now, epochMillis);
            server.publishEntities(entities);
            InProcessWorldRenderer.acceptEntities(entities);
        }
        boolean fullResnapshot = RESNAPSHOT_REQUESTED.getAndSet(false);
        if (fullResnapshot) {
            CHUNK_FINGERPRINTS.clear();
            LOADED_CHUNKS.clear();
            lastWorldCapture = 0;
        }
        if (now - lastWorldCapture >= config.worldIntervalNanos()) {
            lastWorldCapture = now;
            captureWorld(player, sequence, fullResnapshot);
        }
    }

    private static void applyLookInput(IsoPlayer player) {
        InputState.Sample input = InputState.current();
        float yaw;
        float pitch;
        if (input.active()) {
            yaw = input.yaw();
            pitch = input.pitch();
        } else {
            FirstPersonInput.update(player);
            if (!FirstPersonInput.isCaptured()) return;
            yaw = FirstPersonInput.yaw();
            pitch = FirstPersonInput.pitch();
        }
        float forwardX = (float) Math.cos(yaw);
        float forwardY = (float) Math.sin(yaw);
        player.setForwardDirection(forwardX, forwardY);
        player.setTargetAndCurrentDirection(forwardX, forwardY);
        float pitchDegrees = (float) Math.toDegrees(pitch);
        player.setTargetVerticalAimAngle(pitchDegrees);
        player.setCurrentVerticalAimAngle(pitchDegrees);
        if (input.active()) player.setIsAiming((input.buttons() & InputState.AIM) != 0);
    }

    private static void captureWorld(IsoPlayer player, long sequence, boolean fullResnapshot) {
        Set<Long> currentKeys = WorldCapture.loadedChunkKeys(player, config.chunkRadius());
        for (Long oldKey : Set.copyOf(LOADED_CHUNKS)) {
            if (currentKeys.contains(oldKey)) continue;
            LOADED_CHUNKS.remove(oldKey);
            CHUNK_FINGERPRINTS.remove(oldKey);
            server.removeChunk(oldKey);
            InProcessWorldRenderer.removeChunk(oldKey);
        }
        for (IsoChunk chunk : WorldCapture.loadedChunks(player, config.chunkRadius())) {
            long key = ((long) chunk.wx << 32) ^ (chunk.wy & 0xffff_ffffL);
            LOADED_CHUNKS.add(key);
            long fingerprint = WorldCapture.fingerprint(chunk, player.getIndex());
            Long previous = CHUNK_FINGERPRINTS.put(key, fingerprint);
            if (previous == null || previous.longValue() != fingerprint) {
                WorldState.Chunk snapshot =
                        WorldCapture.chunk(chunk, player.getIndex(), fingerprint);
                server.publishChunk(snapshot);
                InProcessWorldRenderer.submitChunk(snapshot);
            }
        }
        if (fullResnapshot) server.publishResnapshotDone(sequence);
    }
}
