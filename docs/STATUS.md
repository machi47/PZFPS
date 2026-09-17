# Project status

Recorded 17 September 2026 (America/Denver).

## Current phase

ACTIVE LIVE DIAGNOSTIC — a reversible project-local PZ 42.20.4 client now
renders a perspective world inside the real game window, followed by PZ's own
text and UI passes. PZ remains authoritative for movement, collisions, actions,
inventory, simulation and saves. Godot remains an offline/protocol diagnostic;
it is not another concurrently running game client or the live presentation
path.

The current frame path is:

```text
PZ simulation/update -> immutable authoritative snapshot
                     -> bounded/coalescing mesh work
PZ render thread     -> PZFPS perspective world -> PZ text/UI
```

Exactly one isolated PZ process is running. The source tree is ahead of that
loaded process; the hashes below distinguish the live-tested JAR from the
newest built JAR.

## Truthful acceptance state

- **Offline appearance:** implemented and diagnostic only. The actual installed
  B42 geometry registry and `Tiles2x.pack` feed the persistent `canonical/`
  compiler. The recorded bed prototype has 36 triangles, 24,309 surface texels,
  6,283 source-observed texels (25.846%), and 18,026 prior-only texels. Its
  source-silhouette fit IoU is 0.7850. Harmonic completion is a deliberately
  plain baseline, not neural completion, recovered reflectance, or gameplay.
- **Live neural overlay operation:** not operational. The pinned upstream native
  Mac overlay requires macOS 26+ and a Swift 6.3 dependency while this host is
  macOS 15.7.3 with Swift 6.2.3; no licensed model payload was supplied.
- **Live replacement rendering:** operational as a diagnostic. The owner
  accepted the current room view as substantially more coherent and observed
  that the previous whole-world flashing had stopped. Source textures are
  projected onto indexed geometry where available; PZ's normal UI remains
  visible over the perspective world. Expected holes, wrong/incomplete object
  shapes, missing unseen surfaces, crude actor/world-item representations and
  material/projection errors remain visible.
- **Accepted live first-person gameplay:** **NO**. Forward running was observed,
  but simultaneous camera-relative diagonal movement, cursor/UI transitions,
  center-view interaction, aiming/combat and ordinary inventory/container play
  are not yet accepted. The newest simultaneous-key fix is built but not loaded
  in the running process.
- **Performance checkpoint:** unavailable. The log records completed render
  callbacks and queue behavior, not game FPS. At its last unpaused fresh-state
  sample it reported `completedFrames=300`, `enqueuedFrames=300`, `meshes=169`,
  `built=169`, `dropped=0`, `stateAgeMs=4`. Later completed callbacks reached
  10,500 while the game was paused at simulation frame 470, so their increasing
  state age is expected and must not be reported as gameplay throughput.

## Last accepted checkpoint

The latest accepted checkpoint is a **live visual diagnostic**, not accepted
gameplay:

1. PZ 42.20.4 starts from the project-local disposable profile as one process.
2. Pinned ZombieBuddy `a403dafae4e8a37e1ccefda1936927782709f82d`
   loads the bridge without modifying installed game binaries.
3. Capture occurs only after the authoritative local `IsoPlayer.update()`;
   `IsoWorld.render()` is now a draw boundary only.
4. A stable set of 169 renderer-owned chunk meshes remains present while the
   player is stationary. The former 169 -> 117/104 -> 169 oscillation is gone.
5. The perspective room uses source PZ textures on known geometry and retains
   the normal PZ text/UI pass. The owner reported no further whole-world flash.
6. The current source build passes 15 Java tests and 42 Python tests.

No source/enhanced video pair or matched gameplay performance capture has yet
been accepted.

## Implemented paths

- `bridge/src/main/java/dev/pzfps/bridge/BridgeRuntime.java` — captures only the
  authoritative local player on the verified game update boundary, publishes
  immutable snapshots, caches accepted chunk snapshots, and keeps rendering
  separate from simulation capture.
- `bridge/src/main/java/dev/pzfps/bridge/ChunkLifecycle.java` — prevents a
  rotating/incrementally populated PZ chunk view from deleting or replacing
  renderer geometry on one transient observation. Missing chunks receive an
  eight-capture grace period and changed fingerprints require confirmation.
- `bridge/src/main/java/dev/pzfps/bridge/InProcessWorldRenderer.java` — queues a
  renderer-owned `TextureDraw.GenericDrawer`, owns GL resources, draws a
  perspective source-textured world with back-face culling/alpha handling, and
  restores state for PZ's later UI pass.
- `bridge/src/main/java/dev/pzfps/bridge/WorldMeshBuilder.java` — converts
  immutable chunk snapshots into source-textured indexed geometry, puts
  structural north/west faces on square boundaries instead of centered slabs,
  and retains conservative fallbacks for unsupported objects.
- `bridge/src/main/java/dev/pzfps/bridge/WorldCapture.java` and
  `WorldState.java` — capture geometry/state plus authoritative stair, stairs-
  below and stair-top flags. These flags preserve portal facts; they do not yet
  fabricate ceiling or stairwell geometry.
- `bridge/src/main/java/dev/pzfps/bridge/FirstPersonInput.java` — uses GLFW
  relative mouse deltas without macOS cursor warping, reads PZ's actual physical
  key bindings simultaneously, and returns the inverse of B42's isometric input
  transform so camera-space WASD remains in world space.
- `bridge/src/main/java/dev/pzfps/bridge/patches/StrafingPatch.java` — preserves
  FPS view/actor facing while the authoritative PZ movement path handles left,
  right and backward movement.
- `bridge/mod/42/media/lua/client/PZFPS_KeyBinding.lua` — registers the F8 mouse
  capture action through PZ's key-binding system.
- `bridge/src/main/java/dev/pzfps/bridge/WireProtocol.java`,
  `renderer/scripts/bridge_client.gd`, and `src/pzfps/runtime.py` — protocol
  version 3, including eye height/actor pose and stair-state flags; Godot is
  retained only as an offline/protocol consumer.
- `canonical/` — persistent, identity-keyed geometry/material compilation with
  hard bounds, protected source observations, shared-atlas completion evidence
  and immutable reuse. A learned provider interface exists but no actual neural
  model has been validated.
- `src/pzfps/assets.py` and `src/pzfps/texture_packs.py` — index the installed B42
  geometry and texture packs without committing proprietary assets.
- `src/pzfps/runtime.py` — enforces a single isolated process and project-local
  cache/mod staging. It does not alter Steam launch options, normal saves,
  installed game binaries or unrelated mods.

Bulk/generated material remains under `.local/`, including:

- `.local/assets/pz-42.20/`
- `.local/canonical-bed-v64/`
- `.local/pz-runtime/`
- `.local/upstream/ZombieBuddy/`
- `.local/upstream/dlss5-macos-overlay/`
- `.local/toolchains/`

## Commands actually run

Principal reproducible inspection/build commands include:

```sh
bin/pzfps doctor --write
bin/pzfps upstream fetch
bin/pzfps upstream verify --force-unsupported-host
bin/pzfps assets index-geometry
bin/pzfps assets index-textures --pack Tiles2x.pack
bin/pzfps assets extract-sprite furniture_bedding_01_0

PZ_JAR='/Users/machi/Library/Application Support/Steam/steamapps/common/ProjectZomboid/Project Zomboid.app/Contents/Java/projectzomboid.jar' \
ZOMBIE_BUDDY_JAR='.local/upstream/ZombieBuddy/java/build/jdk26/libs/ZombieBuddy.jar' \
.local/toolchains/gradle-9.3.1/bin/gradle -p bridge clean test jar

PYTHONPATH=src .local/canonical-venv/bin/python -m pytest -q

bin/pzfps game stage-isolated
bin/pzfps game launch-app-isolated
bin/pzfps game status-isolated
```

Targeted installed-class inspection used `javap -c -p` on `IsoPlayer`,
`IsoWorld`, `UIManager`, `IsoWorldInventoryObject`, `InventoryItem` and related
input/context-action classes. Socket/log diagnostics used `lsof`, `nc`, `xxd`
and `rg`; these did not modify the installation.

Most recent test results:

- Python/pytest: 42 passed, 0 failed (49 deprecation warnings).
- Java/Gradle: 15 passed, 0 failed across `ChunkLifecycleTest`,
  `DirectPatchInstallerTest`, `FirstPersonInputTest`, `InputStateTest`,
  `WireProtocolTest` and `WorldMeshBuilderTest`.

## Evidence and identities

- Installed game:
  `/Users/machi/Library/Application Support/Steam/steamapps/common/ProjectZomboid`
- PZ version/build: 42.20.4, Steam build `24909800`; game JAR SHA-256
  `80e405a4bfc42f6072e75b3735f458a6514143da011d3226007ded305a442f44`.
- Staging manifest: `.local/pz-runtime/staging-manifest.json`.
- Live console: `.local/pz-runtime/user-cache/Zomboid/console.txt`.
- Disposable save:
  `.local/pz-runtime/user-cache/Zomboid/Saves/Top Of The World/46507890207760758489`.
- Live-tested staged bridge JAR SHA-256:
  `c0904da6d2f775c6dcd8bfac90ccc1096093640fff7fc05d61149cc8bd8946d2`.
- Newest built but not live-tested bridge JAR SHA-256:
  `a4b0d24064b7b8bb974898e1446e577ef053225bf22ae3a4d6a05dad425029e7`.
- Offline canonical report:
  `.local/canonical-bed-v64/store/objects/5107aa94b47977535039da77ac4018329c238388ad51c94829dc5a69d01d1a0a/report.json`.
- Geometry source SHA-256:
  `a092640469b62fc34f38146ceb8dd270b2c8517db65a4c361826479fd274db18`.
- Compiled registry SHA-256:
  `8e109160f904c6cd506337af4752ba800abae64c454df3b8f9d1a8ff74b8b532`.
- ZombieBuddy JAR SHA-256:
  `cc5642ef7d91f5a5957af6dd30000151c8c3674ea040b9e739b8dd2c309668de`.
- Pinned overlay commit: `0d4caa1fd36d2581785efa80e6ade09983a18f05`.

Source and enhanced gameplay captures do not yet exist. Configured capture or
update rates have not been reported as achieved performance.

## Failures and remaining prerequisites

1. Capturing from both `IsoPlayer.update()` and `IsoWorld.render()` published
   three incompatible player positions (approximately z=0, z=11 and z=16) and
   churned the renderer between incompatible chunk sets. Capture is now limited
   to the authoritative local player update; render is draw-only, and the chunk
   lifecycle rejects transient absence/change. This fixed the observed static
   whole-world flicker.
2. The earlier mouse path used PZ/offscreen coordinates and could throw the
   native macOS cursor outside the window. It now uses GLFW grabbed relative
   motion. A complete state machine for context menus, inventory/modal UI,
   Escape and automatic recapture remains to be implemented and live-tested.
3. Current fallback capture treats dropped inventory objects like generic tile
   sprites. It does not yet carry `InventoryItem` model identity, offsets,
   rotations and world scale into the renderer, so dropped/placed/held objects
   cannot yet be correct.
4. Center-view doors/containers/context menus and combat must resolve a stable
   snapshot reference back to the exact live PZ object on the game thread and
   request PZ's own validated action. Direct position or state mutation is not
   an acceptable substitute.
5. The native neural overlay is blocked by the host/toolchain/model prerequisites
   above. No other renderer/model rewrite has been substituted for it.
6. Visible holes and unsupported backs/ceilings are now honestly exposed. Their
   constrained completion is intentionally deferred until the live controls and
   authoritative interaction seam are coherent enough to judge moving views.

## Next smallest experiment

Without restarting the current accepted visual session merely to inspect it:

1. Add and unit-test explicit cursor states: gameplay-captured, manually
   released, and UI-released; make modal/context UI release the cursor and
   recapture only after the UI has been clear for consecutive updates.
2. Capture dropped-item identity and authoritative placement transforms rather
   than treating them as anonymous tile sprites.
3. Stage one batched build and live-test simultaneous W+A/W+D, backward/strafe
   movement, F8, Escape/UI release, and stable rendering in the disposable save.
4. Then implement one center-view door or container interaction by re-resolving
   and validating the live object on PZ's game thread. Acceptance requires PZ's
   real action/context code to run; a visual or locally simulated interaction
   does not count.

The later appearance experiment is one identity-stable real asset carried
through constrained completion and inspected from multiple moving views. It is
not started until the owner returns to choose and judge that hole-filling pass.
