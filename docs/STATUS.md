# Project status

Recorded 17 September 2026 (America/Denver).

## Current phase

ACTIVE DIAGNOSTIC — source, a reversible project-local runtime, a live Java
integration bridge, authoritative state snapshots, PZ asset indexing, and a
single-window perspective world-render replacement are implemented. The world
replacement has not yet produced an accepted live first-person gameplay run.

Godot is retained only as an offline/protocol diagnostic. It is not in the live
presentation path. The intended live frame order is now:

```text
PZ frame setup -> PZFPS perspective world -> PZ world text -> normal PZ UI
```

The patch replaces only `zombie.iso.IsoWorld.render()`. PZ continues to own the
simulation, actions, saves, animation evaluation, frame, text and UI passes.
The replacement fails open to vanilla world rendering until its registry,
player snapshot and at least one chunk mesh are ready, and after any GL failure.

Exactly one PZ process is currently used. No Godot process is running.

## Truthful acceptance state

- **Offline appearance:** diagnostic only. The installed B42 tile-geometry
  registry has 21,523 tile identities and 11,793 geometry primitives. The
  official `Tiles2x.pack` was indexed and one needed atlas page was extracted
  beneath `.local/`. A synthetic Godot test rendered indexed geometry and one
  source-sprite projection, but this is not photorealistic completion.
- **Live overlay operation:** not operational. The pinned native Mac neural
  overlay cannot build on this host, and no lawful model payload was supplied.
- **Live bridge operation:** patch loading and loopback protocol handshakes are
  verified on PZ 42.20.4. A previous live run produced only the protocol Hello
  packet because the selected `IsoPlayer.update()` capture boundary emitted no
  local snapshots on this path.
- **Accepted live first-person gameplay:** **NO**. The new render-boundary
  snapshot fix is built and loaded, but a live perspective frame with normal PZ
  UI, movement and interaction has not yet been visually accepted or captured.
- **Performance checkpoint:** none. A single `top` sample reported 181.2% CPU
  for vanilla isometric drawing in an idle live save; this is not a benchmark
  and has no matched replacement-render comparison yet.

## Last accepted checkpoint

The latest accepted engineering checkpoint is:

1. PZ 42.20.4 starts from the project-local disposable profile as one process.
2. Pinned ZombieBuddy `a403dafae4e8a37e1ccefda1936927782709f82d`
   loads the bridge JAR and applies the input, player and `IsoWorld.render()`
   patches.
3. The in-process registry loads 5,158 tile identities that have explicit
   geometry from the full 21,523-identity asset index.
4. All Java and Python tests pass.
5. The normal PZ client and UI still render while the guarded replacement is
   not ready.

This is a diagnostic checkpoint, not accepted first-person gameplay.

## Implemented paths

- `bridge/src/main/java/dev/pzfps/bridge/InProcessWorldRenderer.java` — queues a
  renderer-owned `TextureDraw.GenericDrawer` into PZ's render thread, owns GL
  shaders/VBOs/VAOs, draws a perspective world and nearby entity fallbacks, and
  restores GL state for the later PZ UI pass.
- `bridge/src/main/java/dev/pzfps/bridge/WorldMeshBuilder.java` — converts
  immutable chunk snapshots into floor, box, cylinder, polygon and conservative
  fallback triangles on a bounded coalescing worker queue.
- `bridge/src/main/java/dev/pzfps/bridge/TileGeometryRegistry.java` — loads the
  project-local compiled B42 geometry registry.
- `bridge/src/main/java/dev/pzfps/bridge/patches/WorldRenderPatch.java` — observes
  the verified world-render boundary, enqueues the replacement, and skips only
  the original isometric world method when ready.
- `bridge/src/main/java/dev/pzfps/bridge/{BridgeRuntime,BridgeServer,WorldCapture,
  WorldState,WireProtocol}.java` — immutable game-thread snapshots, evaluated
  actor poses, bounded/coalesced world updates, input messages and loopback
  transport protocol version 2.
- `bridge/src/main/java/dev/pzfps/bridge/patches/` — ZombieBuddy hooks for local
  movement, aim, keyboard, mouse, player update and world rendering.
- `bridge/mod/42/media/lua/client/PZFPS_AutoTest.lua` — marker-gated automation
  used only by the project-local disposable profile.
- `src/pzfps/assets.py` and `src/pzfps/texture_packs.py` — installed B42 geometry
  and texture-pack index/extraction without copying proprietary assets into Git.
- `src/pzfps/runtime.py` — one-process guard and project-local disposable cache,
  mod staging and runtime configuration. It does not alter Steam launch options,
  installed game binaries, normal saves or unrelated mods.
- `renderer/` — Godot protocol/mesh diagnostic retained for offline and
  synthetic tests; it is not the live renderer.
- `src/pzfps/{doctor,evidence,deployment}.py` — host discovery, evidence gates,
  deployment hashing and conflict-safe rollback bookkeeping.

Bulk/generated material remains under `.local/`, including:

- `.local/assets/pz-42.20/tile-geometry.json`
- `.local/assets/pz-42.20/first-asset/`
- `.local/pz-runtime/`
- `.local/upstream/ZombieBuddy/`
- `.local/upstream/dlss5-macos-overlay/`
- `.local/toolchains/`

## Commands actually run

Principal reproducible commands include:

```sh
bin/pzfps doctor --write
bin/pzfps upstream fetch
bin/pzfps upstream verify --force-unsupported-host
bin/pzfps assets index-geometry
bin/pzfps assets index-textures --pack Tiles2x.pack
bin/pzfps assets extract-sprite furniture_bedding_01_0

PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=src \
  python3 -m unittest discover -s tests -v

cd bridge
../.local/toolchains/gradle-9.3.1/bin/gradle --no-daemon clean test jar \
  -PpzJar='/Users/machi/Library/Application Support/Steam/steamapps/common/ProjectZomboid/Project Zomboid.app/Contents/Java/projectzomboid.jar' \
  -PzombieBuddyJar='../.local/upstream/ZombieBuddy/java/build/jdk26/libs/ZombieBuddy.jar'

bin/pzfps game stage-isolated
bin/pzfps game launch-app-isolated
bin/pzfps game status-isolated
bin/pzfps game stop-isolated

javap -classpath '<installed projectzomboid.jar>' -c -p \
  zombie.gameStates.IngameState
javap -classpath '<installed projectzomboid.jar>' -c -p \
  zombie.characters.IsoPlayer
lsof -nP -iTCP:24872 -sTCP:LISTEN,ESTABLISHED
nc -w 5 127.0.0.1 24872 | xxd -g 1
top -l 3 -s 2 -pid '<PZ pid>' -stats pid,cpu,time,threads,mem
```

Test results at this checkpoint:

- Python: 16 passed, 0 failed.
- Java/Gradle: 6 tests passed, 0 failed across `InputStateTest`,
  `WireProtocolTest` and `WorldMeshBuilderTest`.
- Godot script/synthetic protocol compilation previously passed; it is not a
  live-game acceptance result.

## Evidence and identities

- Installed game:
  `/Users/machi/Library/Application Support/Steam/steamapps/common/ProjectZomboid`
- PZ version/build: 42.20.4, Steam build `24909800`, game JAR SHA-256
  `80e405a4bfc42f6072e75b3735f458a6514143da011d3226007ded305a442f44`.
- Staging manifest: `.local/pz-runtime/staging-manifest.json`.
- Live console: `.local/pz-runtime/user-cache/Zomboid/console.txt`.
- Disposable save:
  `.local/pz-runtime/user-cache/Zomboid/Saves/Top Of The World/46507890207760758489`.
- Current bridge JAR checksum is recorded in the staging manifest on every
  stage; the latest loaded checkpoint before this update used
  `a9cb7a9759f25547e844d1f3274a1bc5bdcee3cd9807a06090ebed765f200cda`.
- Geometry source SHA-256:
  `a092640469b62fc34f38146ceb8dd270b2c8517db65a4c361826479fd274db18`.
- Compiled registry SHA-256:
  `8e109160f904c6cd506337af4752ba800abae64c454df3b8f9d1a8ff74b8b532`.
- ZombieBuddy JAR SHA-256:
  `cc5642ef7d91f5a5957af6dd30000151c8c3674ea040b9e739b8dd2c309668de`.
- Pinned overlay commit:
  `0d4caa1fd36d2581785efa80e6ade09983a18f05`.

Source and enhanced gameplay captures do not yet exist. No performance values
have been inferred from configured capture or update rates.

## Failures and blockers

1. Two initial live loads failed before patch application because ZombieBuddy's
   unsafe injector defined a custom nested interface after classes that
   implemented it. The registry primitive is now one dependency-free value
   class, and the complete bridge JAR loads.
2. The first loaded bridge session emitted only Hello: `IsoPlayer.update()` did
   not provide snapshots on the observed local render path. Snapshot capture is
   now also called at `IsoWorld.render()` on PZ's main render-state producer
   thread. The overly restrictive identity filter was removed only at that
   already-selected `players[0]` boundary. This latest fix still needs a live
   acceptance run.
3. The native Mac overlay declares macOS 26+ and its pinned MLX Swift 0.31.6
   dependency requires Swift tools 6.3. The host is macOS 15.7.3 with Swift
   6.2.3. No model payload has been supplied. Overlay inference is blocked.
4. The rejected Workshop first-person implementation is a Lua/UI raycaster with
   documented visual and performance limits. It was never subscribed or
   installed.
5. The current in-process renderer has structural geometry and conservative
   entity proxies, not complete materials, unseen-surface completion, imported
   skinned PZ character meshes, effects or validated mouse-look/action mapping.

## Next smallest experiment

Enter the existing project-local disposable save once and require the following
in the same one-process run:

1. Log `world render boundary observed`, `first authoritative snapshot`, and
   `single-window first-person world renderer active`.
2. Complete GL frames without the fail-open message.
3. Visually verify a perspective world in the PZ window with the normal PZ UI
   drawn afterward.
4. Move through the room and exercise one real interaction while PZ remains
   authoritative.
5. Preserve source and replacement captures, then collect matched completed
   frame, state-age, CPU/GPU and interaction evidence.

Only that result may be called accepted live first-person gameplay. The next
appearance slice after it is one identity-stable object carried through the
chosen constrained completion/material method, compared from multiple moving
views without per-frame regeneration.
