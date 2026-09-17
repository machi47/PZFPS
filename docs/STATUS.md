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
  but simultaneous camera-relative diagonal movement, the newly implemented
  cursor/UI transitions, center-view interaction, aiming/combat and ordinary
  inventory/container play are not yet accepted. The newest input/UI build is
  not loaded in the running process.
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
6. The current source build passes 48 Java tests and 45 Python tests.

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
  restores state for PZ's later UI pass. Its periodic evidence now separates
  measured completed-callback rate from gameplay FPS and reports visible,
  empty, distance-culled and frustum-culled chunk counts for the completed frame.
  It now also aggregates representation coverage only across that completed
  frame's visible chunks; these counts are not presented as visual quality.
- `bridge/src/main/java/dev/pzfps/bridge/WorldMeshBuilder.java` — converts
  immutable chunk snapshots into source-textured indexed geometry, puts
  structural north/west faces on square boundaries instead of centered slabs,
  and retains conservative fallbacks for unsupported objects. Each built chunk
  carries explicit counts for source-textured versus flat floors, indexed
  objects, structural fallbacks, native items, unsupported-object holes and
  safety-cap truncation so later completion work can be prioritized from real
  scene evidence.
- `bridge/src/main/java/dev/pzfps/bridge/WorldCapture.java` and
  `WorldState.java` — capture geometry/state plus authoritative stair, stairs-
  below and stair-top flags. These flags preserve portal facts; they do not yet
  fabricate ceiling or stairwell geometry.
- `bridge/src/main/java/dev/pzfps/bridge/FirstPersonInput.java` — uses GLFW
  relative mouse deltas without macOS cursor warping, reads PZ's actual physical
  key bindings simultaneously, and returns the inverse of B42's isometric input
  transform so camera-space WASD remains in world space.
- `bridge/src/main/java/dev/pzfps/bridge/CursorCaptureState.java` — distinguishes
  gameplay capture, deliberate F8 release and temporary UI ownership. Modal or
  explicitly force-cursor UI releases mouse-look and requires two consecutive
  clear input updates before automatic recapture; manual release never
  auto-recaptures. `FirstPersonInput` deliberately inspects the force-cursor
  property itself because B42's aggregate helper also treats incidental UI
  hover as force-cursor state.
- `bridge/src/main/java/dev/pzfps/bridge/MovementDiagnostics.java` — compares
  requested FPS world direction with authoritative post-`IsoPlayer.update()`
  displacement over bounded 180-input-update windows. It reports directional
  alignment, opposed displacement and stationary updates separately; the last
  category explicitly includes collision/action constraints and is not called
  renderer or gameplay FPS.
- `bridge/src/main/java/dev/pzfps/bridge/patches/StrafingPatch.java` — preserves
  FPS view/actor facing while the authoritative PZ movement path handles left,
  right and backward movement.
- `bridge/src/main/java/dev/pzfps/bridge/patches/AimVectorPatch.java` and
  `AimStatePatch.java` — cover both B42 aim-vector routes: the public
  `getAimVector(Vector2)` used during movement and the private
  `calculateAimVector(Vector2)` called directly by `setAngleFromAim()` during
  combat. After that method invokes B42's isometric ballistics calculation, the
  patch restores the local actor's horizontal facing and vertical presentation
  to the FPS camera. PZ still decides whether an attack is authorized and
  executes `AttemptAttack`. This path is source-built but not live-accepted.
- `bridge/src/main/java/dev/pzfps/bridge/PerspectiveBallistics.java` and
  `patches/BallisticsAimPatch.java` — adapt the FPS ray to B42's existing
  ballistics system at two exact installed-build boundaries. Every
  `calculateMuzzlePosition(Vector3, Vector3)` retains PZ's evaluated attachment
  position but receives camera yaw/pitch as its direction. Immediately before
  `getCameraTargets(float, boolean)`, the bridge supplies native Bullet with the
  same normalized muzzle direction, a reticle point at the muzzle, and a
  quaternion whose local negative-Z axis follows the perspective ray. The
  stale Java isometric reticle point is moved to the muzzle so it cannot admit
  an off-axis target before the native query. B42 still performs native
  collision/body-part queries, range/LOS checks, hit chance, damage and network
  actions. Installed native disassembly verified how the camera ray is formed;
  pure coordinate/quaternion tests pass, but an aimed firearm hit/miss/occlusion
  sequence has not been run live, so ranged combat remains unaccepted.
- `bridge/mod/42/media/lua/client/PZFPS_KeyBinding.lua` — registers the F8 mouse
  capture action and F7 perspective context-menu action through PZ's editable
  key-binding system. Its context wrapper feeds the exact target's isometric
  location to B42's normal menu builder, then positions the resulting PZ UI at
  the centre of the first-person viewport.
- `WorldState.WorldItem` and `WorldCapture.worldItem(...)` — preserve a dropped
  item's real ID/type, static/world model identities, world texture, absolute
  placement, rotations, scale and extended-placement state. The mesh builder
  now refuses to treat its generated item sprite as map-tile geometry.
- `bridge/src/main/java/dev/pzfps/bridge/NativeWorldItemPass.java` and
  `FirstPersonModelCamera.java` — cull captured dropped items in the perspective
  view, re-resolve each by square/index/item ID on PZ's game thread, and queue
  PZ's own `ItemModelRenderer` after the replacement world. This delegates
  state-specific static/world model choice, installed mesh/texture loading,
  attachments, tint and scale to the game instead of re-parsing FBX/X or
  drawing a sprite proxy. The camera adapter maps model space into the same
  perspective view and scopes `PerformanceSettings.fboRenderChunk=false` to
  each synchronous item draw so PZ's isometric `targetDepth` offset cannot
  corrupt perspective depth. The prior value is restored in `finally`. This is
  source-built and unit-tested, not yet live-accepted.
- `bridge/src/main/java/dev/pzfps/bridge/NativeActorPass.java` and
  `FirstPersonCharacterCamera.java` — select visible nonlocal characters with
  validated active model slots, snapshot them through B42's own
  `ModelSlotRenderData`, and queue them after the replacement world so they use
  its perspective depth. This preserves the client's already-evaluated
  animation matrices, clothing, attachments, held models, seated transforms,
  ambient state and model lifecycle rather than advancing a second animator.
  Native-queued entity IDs suppress only their corresponding diagnostic boxes;
  missing/unusable model slots retain the box fallback. The local character is
  deliberately excluded to prevent head/neck/shoulder clipping until a
  first-person body treatment is implemented. Isometric chunk `targetDepth` is
  disabled only around the synchronous native draw and restored in `finally`.
  Queued and completed callbacks are logged separately and are not called game
  FPS. This path is source-built and unit-tested, not yet live-accepted.
- `bridge/src/main/java/dev/pzfps/bridge/NativeFirstPersonHandsPass.java` —
  snapshots the local player's real `ModelSlotRenderData` but submits only the
  evaluated primary/secondary hand-model roots and their descendants. The local
  body, head, neck and torso are never selected, while weapon/tool parts retain
  PZ's evaluated attachment transforms and action timing. Snapshot reference
  counts and OpenGL/model-camera state are restored on success or failure. This
  is source-built and unit-tested, not live-accepted; third-person attachment
  poses may still require a perspective-specific viewmodel adjustment after a
  live placement, animation and world-occlusion check.
- `bridge/src/main/java/dev/pzfps/bridge/NativeVehiclePass.java` — replaces a
  visible vehicle's uniform diagnostic box only after validating its active
  B42 `ModelSlot` and snapshotting it through `ModelSlotRenderData`. The native
  vehicle renderer therefore retains the game's evaluated body/part/wheel
  transforms, installed textures, damage masks, lights and ambient state while
  sharing the perspective depth buffer. The camera adapter explicitly treats
  a vehicle root as B42 vehicle space (unit scale and no character foot offset),
  which `ModelSlotRenderData.inVehicle` alone cannot identify. Missing slots
  keep the old box fallback, and render preparation/callback counts remain
  separate. This is source-built and unit-tested, not live-accepted; a locally
  occupied vehicle also needs a dedicated interior/near-camera visual check.
- `bridge/src/main/java/dev/pzfps/bridge/InteractionTarget.java` — chooses only
  authoritative door/window/container candidates intersected by a short 3D
  perspective ray from the real eye height and pitch. Door/window volumes use
  their actual north/west square edge; conservative container volumes occupy
  their tile. The result is re-resolved by square, index, Java type, object
  type, sprite and item ID against the live object on PZ's game thread. Before
  the handoff, B42's own `LosUtil.lineClear` rejects ordinary wall occlusion; a
  closed door or window admits only itself and cannot expose a container behind
  it.
- `bridge/src/main/java/dev/pzfps/bridge/PerspectiveInteract.java` and
  `patches/ContextActionPatch.java` — scope that identity-checked live target to
  the exact execution of B42's `doContext()`. PZ still constructs and validates
  its normal contextual-action list. Its private chooser then prefers the
  highest-priority action whose `IsoObject` is the reticle target, preserving
  the game's front/behind tie-break. If PZ generated no action for that exact
  object, execution of a different nearby isometric action is suppressed. The
  scope is thread-local and cleared on normal or exceptional exit. This path is
  source-built and Byte Buddy inlining-tested, not live-accepted.
- `bridge/src/main/java/dev/pzfps/bridge/PerspectiveContextMenu.java` — hands an
  identity-checked live target to the PZ Lua context system on the game thread.
  It releases mouse capture only after PZ reports a non-empty menu; every menu
  option and resulting action remains owned by the game's existing code.
- `bridge/src/main/java/dev/pzfps/bridge/WireProtocol.java`,
  `renderer/scripts/bridge_client.gd`, and `src/pzfps/runtime.py` — protocol
  version 4, including eye height/actor pose, stair-state flags and world-item
  identity/placement; Godot is retained only as an offline/protocol consumer.
- `canonical/` — persistent, identity-keyed geometry/material compilation with
  hard bounds, protected source observations, shared-atlas completion evidence
  and immutable reuse. A learned provider interface exists but no actual neural
  model has been validated.
- `src/pzfps/assets.py` and `src/pzfps/texture_packs.py` — index the installed B42
  geometry and texture packs without committing proprietary assets.
- `src/pzfps/model_assets.py` — parses the installed B42 model scripts and
  writes a deterministic identity index for PZ's actual FBX/X meshes, PNG
  textures, scales and `attachment world` transforms. The 42.20 scan found
  3,835 model definitions across 235 source files; 3,732 resolve to an exact
  mesh and 3,442 to an explicit or exact same-identity texture. This is an
  offline registry, not a live model renderer.
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
bin/pzfps assets index-models
bin/pzfps assets extract-sprite furniture_bedding_01_0

PZ_JAR='/Users/machi/Library/Application Support/Steam/steamapps/common/ProjectZomboid/Project Zomboid.app/Contents/Java/projectzomboid.jar' \
ZOMBIE_BUDDY_JAR='/Users/machi/Code/MyProjects/PZFPS/.local/upstream/ZombieBuddy/java/build/jdk26/libs/ZombieBuddy.jar' \
.local/toolchains/gradle-9.3.1/bin/gradle -p bridge clean test jar

PYTHONPATH=src .local/canonical-venv/bin/python -m pytest -q

bin/pzfps game stage-isolated
bin/pzfps game launch-app-isolated
bin/pzfps game status-isolated

PZ_JAR='/Users/machi/Library/Application Support/Steam/steamapps/common/ProjectZomboid/Project Zomboid.app/Contents/Java/projectzomboid.jar'
PZ_BULLET='/Users/machi/Library/Application Support/Steam/steamapps/common/ProjectZomboid/Project Zomboid.app/Contents/Java/libPZBullet.dylib'
javap -classpath "$PZ_JAR" -c -p zombie.core.physics.BallisticsController
java -jar .local/toolchains/downloads/cfr-0.152.jar "$PZ_JAR" \
  --outputdir <temporary-directory> --jarfilter CombatManager
nm -gU "$PZ_BULLET" | rg -i 'Ballistics|AimReticle'
otool -arch arm64 -tvV "$PZ_BULLET"
```

Targeted installed-class inspection used `javap -c -p` on `IsoPlayer`,
`IsoWorld`, `UIManager`, `IsoWorldInventoryObject`, `InventoryItem`,
`ItemModelRenderer`, `WorldItemModelDrawer`, `IModelCamera`, `ModelCamera`,
`CharacterModelCamera`, `VehicleModelCamera`, `ModelCameraRenderData`,
`ModelSlotRenderData`, `ModelInstance`, `ModelManager`, `BaseVehicle`,
`TextureDraw.drawModel`, `SpriteRenderer.drawModel`, `Model`, `Shader`,
`CharacterInputComponent`, `IsoPlayer.doContext()`, `ContextualAction`,
`BallisticsController`,
`AimingReticle`, `Bullet` and related input/context-action/model classes. This
confirmed that B42 snapshots evaluated model data on
the producer side, reference-counts it until `postRender()`, and delegates the
actual character camera through `ModelCamera.instance`. The installed
`basicEffect` shaders were also searched to verify that `targetDepth` changes
clip-space Z. Aim-path inspection established that B42's attack path calls the
private `calculateAimVector(Vector2)` directly and that ranged target selection
uses a native Bullet camera configured with a hard-coded isometric quaternion.
Read-only `nm` and arm64 `otool -tvV` inspection of the installed
`libPZBullet.dylib` then verified that `getCameraTargets` casts along the
negative-Z column of that quaternion through the supplied world point. No
native binary was changed. Socket/log diagnostics used `lsof`, `nc`, `xxd` and
`rg`; these did not modify the installation.

Most recent test results:

- Python/pytest: 45 passed, 0 failed (49 deprecation warnings).
- Java/Gradle: 48 passed, 0 failed across `ChunkLifecycleTest`,
  `CursorCaptureStateTest`, `DirectPatchInstallerTest`, `FirstPersonInputTest`,
  `FirstPersonCharacterCameraTest`, `FirstPersonModelCameraTest`,
  `InputStateTest`, `InteractionTargetTest`, `MovementDiagnosticsTest`,
  `NativeActorPassTest`, `NativeFirstPersonHandsPassTest`,
  `NativeVehiclePassTest`, `NativeWorldItemPassTest`,
  `PerspectiveBallisticsTest`, `PerspectiveInteractTest`,
  `WireProtocolTest` and `WorldMeshBuilderTest`.

## Evidence and identities

- Installed game:
  `/Users/machi/Library/Application Support/Steam/steamapps/common/ProjectZomboid`
- PZ version/build: 42.20.4, Steam build `24909800`; game JAR SHA-256
  `80e405a4bfc42f6072e75b3735f458a6514143da011d3226007ded305a442f44`.
- Inspected installed `libPZBullet.dylib` SHA-256:
  `ebba9eeaad47f5d9e17cc8106a43ace21b6b7d874fbaa7c8c0743450fd5a5be5`.
- Staging manifest: `.local/pz-runtime/staging-manifest.json`.
- Live console: `.local/pz-runtime/user-cache/Zomboid/console.txt`.
- Disposable save:
  `.local/pz-runtime/user-cache/Zomboid/Saves/Top Of The World/46507890207760758489`.
- Live-tested staged bridge JAR SHA-256:
  `c0904da6d2f775c6dcd8bfac90ccc1096093640fff7fc05d61149cc8bd8946d2`.
- Newest built but not live-tested bridge JAR SHA-256:
  `862e2d7110c75de2dc831b0f60980adb023ea5308ce50b61db7bfa99af2f2462`.
- Offline canonical report:
  `.local/canonical-bed-v64/store/objects/5107aa94b47977535039da77ac4018329c238388ad51c94829dc5a69d01d1a0a/report.json`.
- Geometry source SHA-256:
  `a092640469b62fc34f38146ceb8dd270b2c8517db65a4c361826479fd274db18`.
- Compiled registry SHA-256:
  `8e109160f904c6cd506337af4752ba800abae64c454df3b8f9d1a8ff74b8b532`.
- Installed-model index:
  `.local/assets/pz-42.20/model-index.json` (3,812 mesh files and 6,140
  texture files inventoried; 103 model definitions have no exact mesh match
  and 393 have no explicit or exact same-identity texture match).
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
   motion. B42's aggregate force-cursor query was also found to include ordinary
   mouse-over; the bridge now ignores that hover component and recognizes only
   modal or explicitly force-cursor UI. The inventory/loot pair is marked only
   when PZ's own toggle exposes it. The cursor state machine is unit-tested but
   still needs a live F8, Escape, inventory/modal and context-menu acceptance
   pass.
3. Dropped-item capture now carries `InventoryItem` model identity, position,
   rotations and world scale. The renderer deliberately omits the old wrong
   tile-geometry substitution. A source-built native item pass now calls PZ's
   own `ItemModelRenderer` through a perspective camera adapter; its remaining
   prerequisite is a live check that a real dropped item has the right model,
   texture, placement and occlusion from several viewpoints.
4. Center-view doors/containers/context menus and combat must resolve a stable
   snapshot reference back to the exact live PZ object on the game thread. That
   reference/re-resolution seam now includes eye height and pitch and is unit-
   tested; opening a PZ context menu for it and live acceptance remain. Direct
   position or state mutation is not an acceptable substitute. Inspection of
   the installed geometry also confirmed that PZ supplies distinct open/closed
   door sprite geometry, so the renderer continues to consume the live sprite
   rather than fabricating a second hinge transform.
5. Nonlocal native characters are now wired to B42's evaluated model render
   data rather than reanimated or approximated in the renderer. This has not
   been loaded into the live process. It still needs a disposable-session check
   of standing, walking, crawling, held equipment, occlusion and at least one
   seated actor; an exception disables only this pass and restores diagnostic
   boxes on the next frame. The local body remains intentionally absent.
6. The native neural overlay is blocked by the host/toolchain/model prerequisites
   above. No other renderer/model rewrite has been substituted for it.
7. Visible holes and unsupported backs/ceilings are now honestly exposed. Their
   constrained completion is intentionally deferred until the live controls and
   authoritative interaction seam are coherent enough to judge moving views.
8. A Java validation attempt using a project-relative `ZOMBIE_BUDDY_JAR` failed
   because Gradle resolves file dependencies relative to `bridge/`. The
   corrected absolute project-local path above produced a clean build with all
   then-current tests passing; this was an invocation error, not a source or dependency
   failure.
9. The first interaction-sightline test compilation omitted the static
   `assertFalse` import. The import was added and the complete 42-test Java
   build then passed; no failed artifact was staged or loaded.
10. The first held-model build used a symbolic `GL_ALL_CLIENT_ATTRIB_BITS`
    constant absent from PZ's LWJGL compatibility binding. It now passes `-1`,
    matching the installed B42 bytecode, and restores only stacks that were
    successfully pushed. The corrected 43-test build passed; no artifact from
    the failed compile was staged or loaded.
11. The first contextual-action build declared its advice in a different Java
    package from the existing bridge patches, so it could not access the
    package-private selector and the installer could not resolve the advice
    name. The declaration was aligned with the actual bridge package; the
    corrected 48-test build, including advice inlining against installed
    `IsoPlayer`, passed. No failed artifact was staged or loaded.

## Next smallest experiment

Without restarting the current accepted visual session merely to inspect it:

1. Stage one batched build and live-test simultaneous W+A/W+D, backward/strafe
   movement, F8, Escape/UI release, and stable rendering in the disposable save.
   Use the new post-update movement-alignment report to distinguish a coordinate
   transform failure from valid collision/action blocking; do not infer either
   from camera motion alone.
2. Aim at each of two neighboring doors/windows in turn and verify that the
   normal `Interact` key executes only the identity-matched reticle object's
   PZ-generated contextual action. Then aim at a locked/non-actionable target
   and verify that a different nearby isometric action does not fire. PZ's own
   `doContext()` still owns action construction, validation and execution.
3. Press F7 on a centre-view container and verify the new path opens B42's own
   non-empty menu, releases the cursor, executes one normal option, and
   recaptures only after the menu clears. The implementation is built but not
   live-tested; acceptance requires PZ's real action/context code to run.
4. Inspect one real dropped item from several angles and verify the new native
   item pass selects PZ's installed model/texture, placement and scale, shares
   the perspective depth buffer, and leaves unresolved identities as honest
   holes. The offline model index remains independent reproducibility evidence;
   the live path intentionally uses PZ's state-aware renderer rather than
   duplicating its asset-selection rules.
5. Approach one nonlocal actor and verify that B42's native evaluated model
   replaces only that actor's debug box with correct position, scale, facing,
   animation, clothing/held equipment and world-depth occlusion. Check standing,
   walking and crawling before accepting it; actor callback counts are not
   gameplay FPS.
6. Inspect one parked, one moving and—if available in the disposable save—one
   visibly damaged vehicle. Verify that B42's native body, part/wheel transforms,
   lights and damage textures replace the box at the correct world pose. Test an
   occupied local vehicle separately because near-camera exterior clipping is
   not evidence of a usable first-person interior.
7. Equip one primary-hand and one two-handed item, then verify that the local
   pass draws their evaluated PZ models through idle, walking and one real
   action without revealing the local head/torso or drawing through nearby
   world geometry. Treat a badly placed third-person attachment as a viewmodel
   calibration failure, not as accepted first-person hands.

The later appearance experiment is one identity-stable real asset carried
through constrained completion and inspected from multiple moving views. It is
not started until the owner returns to choose and judge that hole-filling pass.
