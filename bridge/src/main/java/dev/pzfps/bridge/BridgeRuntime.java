package dev.pzfps.bridge;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import zombie.GameTime;
import zombie.characters.IsoPlayer;
import zombie.input.GameKeyboard;
import zombie.iso.IsoChunk;
import zombie.iso.IsoObject;

/** Coordinates game-thread capture and the isolated transport worker. */
public final class BridgeRuntime {
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final AtomicBoolean RESNAPSHOT_REQUESTED = new AtomicBoolean(true);
    private static final AtomicLong FRAME_SEQUENCE = new AtomicLong();
    private static final AtomicBoolean WORLD_RENDER_SEEN = new AtomicBoolean();
    private static final AtomicBoolean FIRST_SNAPSHOT_SEEN = new AtomicBoolean();
    private static final AtomicBoolean INTERACTION_SELECTION_FAILURE_REPORTED =
            new AtomicBoolean();
    private static final int CHUNK_MISSING_GRACE_CAPTURES = 8;
    private static final int CHUNK_CHANGE_CONFIRMATION_CAPTURES = 2;
    private static final float INTERACTION_REACH = 2.3f;
    private static final String CONTEXT_MENU_ACTION = "PZFPS Context Menu";
    private static final ChunkLifecycle CHUNK_LIFECYCLE = new ChunkLifecycle(
            CHUNK_MISSING_GRACE_CAPTURES, CHUNK_CHANGE_CONFIRMATION_CAPTURES);
    private static final Map<Long, WorldState.Chunk> ACCEPTED_CHUNKS = new HashMap<>();
    private static BridgeConfig config;
    private static BridgeServer server;
    private static long lastEntityCapture;
    private static long lastWorldCapture;
    private static long worldCaptureCount;

    private BridgeRuntime() {}

    public static void start() {
        if (!STARTED.compareAndSet(false, true)) return;
        try {
            config = BridgeConfig.fromSystemProperties();
            server = new BridgeServer(config);
            server.start();
            String assetRegistry = System.getProperty("pzfps.assetRegistry", "").trim();
            if (!assetRegistry.isEmpty()) {
                String supplemental = System.getProperty("pzfps.supplementalAssetRegistry", "").trim();
                String propSurfaces = System.getProperty("pzfps.propSurfaceRegistry", "").trim();
                java.util.ArrayList<Path> supplementalPaths = new java.util.ArrayList<>(2);
                if (!supplemental.isEmpty()) supplementalPaths.add(Path.of(supplemental));
                if (!propSurfaces.isEmpty()) supplementalPaths.add(Path.of(propSurfaces));
                InProcessWorldRenderer.start(
                        Path.of(assetRegistry),
                        List.copyOf(supplementalPaths));
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

    /** Capture the collision-authoritative local player after PZ has completed its update. */
    public static void onPlayerUpdate(IsoPlayer player) {
        if (!isAuthoritativeLocalPlayer(player)) return;
        MovementDiagnostics.end(player);
        FirstPersonInput.finishMovementSample();
        if (FirstPersonInput.normalLocomotion(player)
                || player.isAttacking()
                || player.isPerformingHostileAnimation()) {
            applyPerspectiveFacing(player);
        }
        capture(player);
    }

    /** Establish FPS facing before PZ derives movement-facing and animation state. */
    public static void onPlayerUpdateStart(IsoPlayer player) {
        if (!STARTED.get() || !isAuthoritativeLocalPlayer(player)) return;
        updateAndApplyLookInput(player);
        MovementDiagnostics.begin(player);
        FirstPersonInput.beginMovementSample();
        updateReticleTracking(player);
        observeInteractionRequest(player);
        openPerspectiveContextMenu(player);
    }

    /**
     * This boundary owns drawing only. PZ exposes buffered player copies to render-state code;
     * reading those as simulation authority caused the FPS camera to alternate between unrelated
     * positions. Immutable state is captured after the authoritative IsoPlayer update instead.
     */
    public static void onWorldRender() {
        if (WORLD_RENDER_SEEN.compareAndSet(false, true)) {
            System.out.printf(
                    "[PZFPS] world render boundary observed thread=%s%n",
                    Thread.currentThread().getName());
        }
    }

    /** Queue native item models after the replacement world so they share its depth buffer. */
    public static void queueNativeWorldItems() {
        NativeWorldItemPass.queueVisible(IsoPlayer.getInstance());
    }

    /** Snapshot native actor render data before the replacement world is queued. */
    public static NativeActorPass.PreparedFrame prepareNativeActors() {
        return NativeActorPass.prepareVisible(IsoPlayer.getInstance());
    }

    /** Snapshot native vehicle render data before the replacement world is queued. */
    public static NativeVehiclePass.PreparedFrame prepareNativeVehicles() {
        return NativeVehiclePass.prepareVisible(IsoPlayer.getInstance());
    }

    /** Snapshot only the local player's evaluated primary/secondary held models. */
    public static NativeFirstPersonHandsPass.PreparedFrame prepareNativeHands() {
        return NativeFirstPersonHandsPass.prepare(IsoPlayer.getInstance());
    }

    private static int lightingCursor;

    private static void captureLighting(IsoPlayer player) {
        if (!InProcessWorldRenderer.isReady()) return;
        List<IsoChunk> chunks = WorldCapture.loadedChunks(player, config.chunkRadius());
        if (chunks.isEmpty()) return;
        long deadline = System.nanoTime() + 2_000_000L;
        for (int count = 0; count < Math.min(4, chunks.size()); count++) {
            lightingCursor = Math.floorMod(lightingCursor, chunks.size());
            IsoChunk chunk = chunks.get(lightingCursor++);
            InProcessWorldRenderer.acceptLighting(chunkKey(chunk), WorldCapture.lighting(chunk, player.getIndex()));
            // Budget checked between chunks: never leave a half-copied light grid.
            if (System.nanoTime() >= deadline) break;
        }
    }

    private static void capture(IsoPlayer player) {
        if (!STARTED.get() || server == null || player == null) return;
        long now = System.nanoTime();
        long epochMillis = System.currentTimeMillis();
        long sequence = FRAME_SEQUENCE.incrementAndGet();
        WorldState.Player playerSnapshot = WorldCapture.player(player, sequence, now, epochMillis);
        server.publishPlayer(playerSnapshot);
        InProcessWorldRenderer.acceptPlayer(playerSnapshot);
        GameTime clock = GameTime.getInstance();
        InProcessWorldRenderer.acceptSky(TimeOfDaySky.from(
                clock.getTimeOfDay(), clock.getDawn(), clock.getDusk(), clock.getNight()));
        captureLighting(player);
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
            lastWorldCapture = 0;
        }
        if (now - lastWorldCapture >= config.worldIntervalNanos()) {
            lastWorldCapture = now;
            captureWorld(player, sequence, fullResnapshot);
        }
    }

    private static void updateAndApplyLookInput(IsoPlayer player) {
        InputState.Sample input = InputState.current();
        if (!input.active()) {
            FirstPersonInput.update(player);
        } else {
            player.setIsAiming((input.buttons() & InputState.AIM) != 0);
        }
        applyPerspectivePitch(player);
        if (FirstPersonInput.normalLocomotion(player)
                || player.isAiming()
                || player.isAttacking()
                || player.isPerformingHostileAnimation()) {
            applyPerspectiveFacing(player);
        }
    }

    /** Reasserts the view after setAngleFromAim's isometric reticle/ballistics calculation. */
    public static void restorePerspectiveAim(IsoPlayer player) {
        if (!STARTED.get() || !isAuthoritativeLocalPlayer(player)) return;
        applyPerspectiveFacing(player);
    }

    /**
     * Called at the exact start of B42's doContext(). PZ still builds, validates and executes the
     * action; the companion selection hook can only prefer an action carrying this same live
     * object identity.
     */
    public static void beginPerspectiveInteract(IsoPlayer player) {
        PerspectiveInteract.end();
        if (!STARTED.get()
                || !isAuthoritativeLocalPlayer(player)
                || (!FirstPersonInput.isCaptured() && !InputState.current().active())) {
            return;
        }
        try {
            long now = System.nanoTime();
            WorldState.Player viewpoint = WorldCapture.player(
                    player, FRAME_SEQUENCE.get(), now, System.currentTimeMillis());
            InteractionTarget.nearestInteractive(
                            viewpoint, ACCEPTED_CHUNKS.values(), INTERACTION_REACH)
                    .filter(reference -> reference.door() || reference.window())
                    .map(reference -> InteractionTarget.resolveLive(
                            player, reference, INTERACTION_REACH))
                    .ifPresent(PerspectiveInteract::begin);
        } catch (RuntimeException | LinkageError error) {
            PerspectiveInteract.end();
            if (INTERACTION_SELECTION_FAILURE_REPORTED.compareAndSet(false, true)) {
                System.err.printf(
                        "[PZFPS interaction] reticle action selection failed for this request: %s%n",
                        error);
                error.printStackTrace(System.err);
            }
        }
    }

    private static void applyPerspectiveFacing(IsoPlayer player) {
        PerspectiveViewRay.Orientation view = PerspectiveViewRay.currentOrientation();
        if (view == null) return;
        player.setForwardDirection(view.horizontalX(), view.horizontalY());
        player.setTargetAndCurrentDirection(view.horizontalX(), view.horizontalY());
        applyPerspectivePitch(player);
    }

    private static void applyPerspectivePitch(IsoPlayer player) {
        PerspectiveViewRay.Orientation view = PerspectiveViewRay.currentOrientation();
        if (view == null) return;
        float pitchDegrees = (float) Math.toDegrees(view.pitch());
        player.setTargetVerticalAimAngle(pitchDegrees);
        player.setCurrentVerticalAimAngle(pitchDegrees);
    }

    private static void updateReticleTracking(IsoPlayer player) {
        if (!FirstPersonInput.isCaptured() && !InputState.current().active()) return;
        long now = System.nanoTime();
        WorldState.Player viewpoint = WorldCapture.player(
                player, FRAME_SEQUENCE.get(), now, System.currentTimeMillis());
        ReticleTracker.updateWorld(
                player, viewpoint, ACCEPTED_CHUNKS.values(), INTERACTION_REACH, now);
    }

    /**
     * Records the perspective candidate while leaving PZ's normal Interact/doContext path in
     * complete control of validity and the resulting action.
     */
    private static void observeInteractionRequest(IsoPlayer player) {
        InputState.Sample input = InputState.current();
        boolean pressed = input.active()
                ? InputState.isActionPressed("Interact")
                : FirstPersonInput.isCaptured() && GameKeyboard.isKeyPressed("Interact");
        if (!pressed) return;

        long now = System.nanoTime();
        WorldState.Player viewpoint = WorldCapture.player(
                player, FRAME_SEQUENCE.get(), now, System.currentTimeMillis());
        InteractionTarget.nearestInteractive(
                        viewpoint, ACCEPTED_CHUNKS.values(), INTERACTION_REACH)
                .ifPresentOrElse(
                        reference -> {
                            IsoObject live = InteractionTarget.resolveLive(
                                    player, reference, INTERACTION_REACH);
                            System.out.printf(
                                    "[PZFPS interaction] Interact candidate square=(%d,%d,%d) index=%d type=%s sprite=%s liveResolved=%s; PZ doContext remains authoritative%n",
                                    reference.squareX(),
                                    reference.squareY(),
                                    reference.z(),
                                    reference.objectIndex(),
                                    reference.javaType(),
                                    reference.sprite(),
                                    live != null);
                        },
                        () -> System.out.println(
                                "[PZFPS interaction] Interact has no perspective candidate; PZ doContext remains authoritative"));
    }

    /**
     * F7 opens PZ's normal world context menu for the identity-checked reticle target. The menu
     * owns all option construction and action validation; this layer only replaces isometric
     * screen picking and gives the cursor to the resulting PZ UI.
     */
    private static void openPerspectiveContextMenu(IsoPlayer player) {
        if (!FirstPersonInput.isCaptured()
                || !GameKeyboard.isKeyPressed(CONTEXT_MENU_ACTION)) {
            return;
        }
        long now = System.nanoTime();
        WorldState.Player viewpoint = WorldCapture.player(
                player, FRAME_SEQUENCE.get(), now, System.currentTimeMillis());
        List<InteractionTarget.Reference> candidates = InteractionTarget.contextCandidates(
                viewpoint, ACCEPTED_CHUNKS.values(), INTERACTION_REACH);
        int resolved = 0;
        int tested = 0;
        for (InteractionTarget.Reference reference : candidates) {
            IsoObject live = InteractionTarget.resolveLive(player, reference, INTERACTION_REACH);
            if (live == null) continue;
            resolved++;
            tested++;
            if (!PerspectiveContextMenu.hasOptions(player, live)) continue;
            boolean opened = PerspectiveContextMenu.open(player, live);
            if (opened) FirstPersonInput.releaseForUi();
            System.out.printf(
                    "[PZFPS interaction] context candidates=%d resolved=%d tested=%d selected=(%d,%d,%d)#%d type=%s menuOpened=%s%n",
                    candidates.size(),
                    resolved,
                    tested,
                    reference.squareX(),
                    reference.squareY(),
                    reference.z(),
                    reference.objectIndex(),
                    reference.javaType(),
                    opened);
            return;
        }
        System.out.printf(
                "[PZFPS interaction] context request candidates=%d resolved=%d tested=%d; PZ reported no valid menu%n",
                candidates.size(), resolved, tested);
    }

    private static void captureWorld(IsoPlayer player, long sequence, boolean fullResnapshot) {
        worldCaptureCount++;
        List<IsoChunk> currentChunks = WorldCapture.loadedChunks(player, config.chunkRadius());
        Set<Long> currentKeys = new HashSet<>();
        for (IsoChunk chunk : currentChunks) {
            currentKeys.add(chunkKey(chunk));
        }

        ChunkLifecycle.Presence presence = CHUNK_LIFECYCLE.observePresence(currentKeys);
        for (Long removedKey : presence.removed()) {
            ACCEPTED_CHUNKS.remove(removedKey);
            server.removeChunk(removedKey);
            InProcessWorldRenderer.removeChunk(removedKey);
            NativeWorldItemPass.removeChunk(removedKey);
        }

        int acceptedChanges = 0;
        for (IsoChunk chunk : currentChunks) {
            long key = chunkKey(chunk);
            long fingerprint = WorldCapture.fingerprint(chunk, player.getIndex());
            if (CHUNK_LIFECYCLE.acceptFingerprint(key, fingerprint)) {
                WorldState.Chunk snapshot =
                        WorldCapture.chunk(chunk, player.getIndex(), fingerprint);
                ACCEPTED_CHUNKS.put(key, snapshot);
                server.publishChunk(snapshot);
                InProcessWorldRenderer.submitChunk(snapshot);
                NativeWorldItemPass.acceptChunk(snapshot);
                acceptedChanges++;
            }
        }

        if (fullResnapshot) {
            for (WorldState.Chunk snapshot : ACCEPTED_CHUNKS.values()) {
                server.publishChunk(snapshot);
            }
            server.publishResnapshotDone(sequence);
        }

        if ((!presence.removed().isEmpty() || currentChunks.size() < CHUNK_LIFECYCLE.activeCount())
                && worldCaptureCount % 20 == 0) {
            System.out.printf(
                    "[PZFPS] transient chunk window current=%d retained=%d removed=%d acceptedChanges=%d%n",
                    currentChunks.size(),
                    CHUNK_LIFECYCLE.activeCount(),
                    presence.removed().size(),
                    acceptedChanges);
        }
    }

    private static long chunkKey(IsoChunk chunk) {
        return ((long) chunk.wx << 32) ^ (chunk.wy & 0xffff_ffffL);
    }

    private static boolean isAuthoritativeLocalPlayer(IsoPlayer player) {
        return player != null
                && player == IsoPlayer.getInstance()
                && player.isLocalPlayer()
                && player.getIndex() == 0;
    }
}
