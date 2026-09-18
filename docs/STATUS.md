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

No isolated PZ process is currently running. PID 41825 was stopped at
18 September 02:44:31 UTC after the depth-derived roof checkpoint loaded but
failed to yield a capturable game window. The staged bridge JAR is
`d83584c3cced5b8a9e179ba5f2c35aee2ccf749ae359e3e73f498ee0906e2377` and the
supplemental roof registry is
`cf518011f16d35b726ea84eb11e5c0d7a5b8d5ca5a16c2297b6bce5c6d581c7b`.
The load/cadence diagnostics passed, but the desktop-only screenshot is not
visual roof evidence and the checkpoint is **not** accepted. No normal save,
installed game binary or unrelated mod was changed.

### Installed corpus ledger and depth-derived roof checkpoint

The owner's accumulated visual reports are now consolidated in
`docs/VISUAL_ISSUES.md` instead of being left across conversation turns. The 34
supplied screenshots are retained locally under
`.local/captures/user-visual-reports/2026-09-17/`; they are not committed because
they contain proprietary game art. `bin/pzfps assets audit-coverage` joins the
installed geometry, atlas and model indexes into
`.local/reports/asset-coverage-pz-42.20.json`. The current report inventories
21,523 tile-geometry identities, 35,746 atlas identities, 32,284 tile-definition
identities, 39,902 unique joined tile identities, 3,835 named models and 5,092
unique item identities from 5,105 installed item declarations. Thirteen
duplicate item identities remain explicit in the item index. Of the unique
items, 3,857 declare and resolve a world model and 1,235 declare none. The audit
explicitly labels accepted,
unaccepted, rejected and unsupported renderer paths instead of treating index
presence as successful visual coverage.

This corpus covers installed tile/sprite definitions, named models and item
scripts. It does not pretend to enumerate every modded identity, procedural
runtime object, character/clothing combination, damage variant, object state or
multi-tile scene grouping. Runtime captures are joined to the installed corpus
so those combinations can be measured when encountered.

The joined audit exposes the scale of the roof gap: 4,569 roof-related
identities are present and only 84 have installed authored source primitives.
`bin/pzfps assets compile-roof-surfaces` now reconstructs conservative connected
planar patches from PZ's installed depth atlases using the game's source
projection/depth equations. The local supplemental registry contains 3,998
identities and 38,372 triangles; 3,949 of those identities are roofs pending
live acceptance. Authored geometry wins when both paths exist. The compiler
rejects 8 entries with no depth image, 76 non-planar entries and 417 unsafe
patch hulls, leaving 536 roof identities rejected/unsupported after authored
precedence. This is a systemic identity-level representation path, not a
per-house patch and not a visual acceptance claim.

`bin/pzfps assets audit-scene` joins a runtime capture to that installed ledger.
For `.local/reports/prop-boundary-scene.json`, the generated
`.local/reports/prop-boundary-scene-coverage.json` records 586 object instances,
189 exact identities and zero identities absent from the corpus. All 91 roof
instances in that captured problem scene resolve to compiled or authored roof
geometry. The snapshot specifically includes `roofs_02_3/4/5/111`, the pictured
`roofs_accents_01_*` pieces and `walls_exterior_roofs_06_*`/
`walls_exterior_roofs_10_*` families.

Python discovery passes 34 tests. The clean Gradle build passes 129 tests,
including loading the installed supplemental registry, validating 3,998 entries
and rendering source-projected roof triangles. The Apple production-shader
probe passes shader link, roof-adjacent material, transparency, fence, physical
occlusion and wall-prop fixtures; its known far-depth residual remains 10,044
wrong pixels beyond 16m and is not hidden by this checkpoint. PID 41825 loaded
the staged registry and reported 59.91--60.08 completed callbacks/s, 4--15ms
state age and zero dropped mesh requests. The attempted live capture
`.local/captures/roof-depth-checkpoint-live.png` contains only the desktop, so
the visual roof check is explicitly inconclusive and V-12 remains open.

The indexed source properties retain `RoofGroup`, `BlockRain`, `attached*`,
`isEave`, `diamondFloor` and `solidfloor` roles for later topology grouping.
Doors remain rejected as non-volumetric, while windows remain rejected as
non-physical despite the narrower translucent-pass GPU fixture.

A guessed six-face door slab was briefly built and loaded. It mapped the full
isometric source crop onto multiple rectangular faces, producing grossly
distorted stacked door shapes and opaque panes. The owner rejected it
immediately. `DoorAssembly`, its shader surface kind and tests were removed;
the 126-test Gradle build and Apple production-shader probe pass after rollback.
PID 37548 containing that regression was stopped. PID 38323 loaded the rollback
JAR, confirmed the bad assembly was absent, and was then stopped; the restored
single edge-card door remains inadequate and is not described as a fix.

### Window transparency: separated opaque and translucent passes (loaded diagnostic)

The black-window failure is now reproduced from the installed source assets and
the production shader. Extracted diagnostic copies remain under
`.local/assets/pz-42.20/window-source/` only. In `fixtures_windows_01_24`, 868
pixels use RGBA alpha 99/255; variant 25 contains 902 such pixels. These are
authored translucent panes rather than alpha holes. The previous single source
pass blended them over the renderer's black clear colour while also writing
depth, so the opaque world behind a window could never reach the framebuffer.

The live renderer now draws source textures in two phases. Effectively opaque
samples render first with depth writes; partially transparent samples render
after all opaque chunks, back-to-front, with standard alpha blending and depth
writes disabled. Fence/gate cutouts remain in the existing opaque alpha-tested
path. Missing source textures retain their opaque fallback but are not drawn a
second time. Renderer state is restored before PZ's native entity and UI
callbacks. This repairs world-through-pane composition; it does not yet tint a
native actor/model callback that is submitted after the pane, sort translucent
triangles within a chunk, add glass refraction, or turn windows into physical
frame/glass assemblies.

The actual Java-embedded production GLSL compiles and links on Apple M4 Max.
The new regression composites alpha-99 red glass over an opaque green world as
RGB 99,156,0 and verifies a later depth-tested native-style callback remains
visible at RGB 0,255,0: 2/2 cases pass. Atlas isolation remains 0/9 leaks,
fence coverage 12/12, physical occlusion 10/10 and wall-prop clearance 10/10.
The known far-depth diagnostic remains 10,044 wrong pixels beyond 16m.
Raw output: `.local/reports/window-transparency-gpu.txt`. The pinned Gradle
build reports 113 tests, zero failures/errors. Built JAR SHA-256:
`6061eb48df2acd644ff789a5489d2d384a931328abadc19050a413d04d32b588`.
PID 34328 was stopped, the isolated profile was staged with the checksum above,
and PID 35118 loaded it. Startup logs show the expected transformed bridge,
geometry registry and texture packs. Frames 900–3600 report 59.91–60.08 completed
callbacks/s, 4–15ms state age and zero dropped mesh requests while the owner moves
through the disposable world. These remain callback/CPU diagnostics, not GPU
timings, and no broad moving-view visual acceptance is claimed yet.

Read-only captures around the current house also identify the next structural
slice. `lighting_indoor_01_1`, `_3`, `_10` and `_40` are wall attachments whose
isometric support geometry is currently rendered as literal volume, producing
floating/clipping switches and vents. Nearby beds, dressers and storage assets
still have open faces, and at least one interior furniture object remains visible
through an exterior shell. Short fence segments alternate `_24`/`_25` and a
`fixtures_doors_fences_01_17` gate but do not share physical boundary endpoints.
The roof uses `roofs_02_3/4/5` plus accent/exterior-roof families and is missing a
large assembled section. Evidence:
`.local/reports/window-switch-live.json` and
`.local/reports/exterior-leak-live.json`. These are rejected diagnostics, not
completed fixes. The next smallest accepted geometry work is a wall-owned,
shallow closed attachment assembly and exterior-shell clipping that applies to
all interior objects crossing a sealed boundary, including neighboring-tile
ownership; doors, closed furniture boxes, fences and roofs remain queued family
assemblies rather than per-house patches.

### Canonical wall clipping and wall-owned fixtures (built; reload pending)

The anchor-square furniture filter was too narrow. A multi-tile object could
cross a wall segment owned by its neighboring square, while authored fixtures
retained source-object foreground depth and could appear through the reverse of
a wall. `StructuralPropClip` now canonicalizes the chunk's finite opaque wall
segments by axis, coordinate, tile span and storey, deduplicating the same wall
reported as one square's east/south and its neighbor's west/north. Each authored
physical object is clipped against every intersecting segment from the side
containing its authoritative owner square. Doors, windows, structural wall
families, roofs and fences remain separate assemblies and are not self-clipped.
The existing 1mm clearance, UV interpolation and finite span/storey limits remain.

PZ identifies the pictured switches, vents and related indoor/outdoor wall
fixtures as `IsoLightSwitch`; their registry boxes/cylinders are isometric
interaction/support shapes, sometimes 2.4495 authored units tall, not visible
fixture volumes. `WallAttachmentAssembly` now requires that exact runtime family
and an authoritative sealed adjacent wall. It chooses a corner wall from the
support shape's thin axis, or the sole available boundary when geometry is absent,
then emits a shallow closed six-face housing wholly on the owning side. The
original sprite crop is fitted to this small assembly in a dedicated shader
surface kind. It does not reinterpret unrelated `lighting_*` art, invent PZ state,
or claim exact manufacturer-quality meshes.

118 Java tests pass with zero failures/errors. New tests cover canonical
neighbor-owned walls, duplicate boundary collapse, broad physical-object routing,
fixture runtime-family gating, corner-wall selection, six-face housing placement,
and absence of foreground depth bias. The Apple production-GLSL probe passes the
new wall-attachment crop mapping (`fitted=128`) while transparency stays 2/2,
atlas leakage 0/9, fence coverage 12/12, physical occlusion 10/10 and wall-prop
clearance 10/10. Raw output: `.local/reports/wall-attachment-gpu.txt`. Built JAR
SHA-256: `b0a5a4ff38572fe41db500b5995164165ba6435fc3e48b9abc16aca4d19d39e4`.
This JAR has not yet replaced PID 35118, so there is no live acceptance claim.

### Solid props crossing walls: constrained presentation (loaded diagnostic)

The previous atlas fix cannot prevent physical source primitives from crossing
walls. Inspection of the actual nearby snapshot and installed shapes found
cabinet support bounds extending 0.46–7.7mm past wall edges; some appliance and
bed bounds end exactly on the wall. All previously inherited the source-object
foreground depth layer, which can pull those faces up to 8mm toward an exterior
camera. The production-shader regression reproduces a 1mm-separated prop showing
through a wall in all 10 legacy-layer cases (0.5–32m, both draw orders).

`StructuralPropClip` now treats furniture/appliance families and the already
verified closed crates as physical props, with no foreground overlay bias.
On the verified game thread, `WorldCapture` copies four opaque-wall boundary
bits per square using explicit `WallN/W`, excluding translucent walls,
hoppable objects and door/window openings. East/south ownership is read from
adjacent squares, including adjacent chunks, and included in the chunk
fingerprint so changed boundaries trigger rebuilding. This is in-process
metadata; protocol 5 remains unchanged and old captures default to no clipping.

The mesh worker clips prop triangles beyond those finite wall segments with
1mm clearance, preserving interpolated UVs/normals/light indices. Segment ends,
other storeys and unverified/open boundaries remain unconstrained; source objects,
collision and placement are never mutated. Wall-mounted fixtures and arbitrary
box-shaped assets are not reclassified as free-standing furniture. This repairs
one cause of exterior leakage, not missing walls, wall alpha holes, arbitrary
multi-tile assembly errors or missing prop faces.

113 Java tests pass. New tests cover all four boundaries, UV preservation,
openings, segment/storey limits, exact-wall faces, repeated clipping, and builder
separation of physical props versus attachment layers. An initial repeat-clipping
test caught unnecessary retessellation; trivial outside rejection fixed it.
GPU `wallPropClearance`: 10/10 pass with the new ordering, versus 10/10 legacy
leaks. Atlas leakage stays 0/9 cases; fences pass 12/12; previous far-depth
residual remains 10,044 wrong pixels beyond 16m, not fixed by this pass.
Raw GPU output: `.local/reports/prop-boundary-gpu.txt`.

Live reload exposed an integration failure missed by pure geometry tests:
PZArrayList.iterator() throws UnsupportedOperationException. The wall scan now
uses indexed access. Failed PID 34156 was stopped; error evidence is retained in
`.local/reports/prop-boundary-first-launch-error.txt`. Corrected PID 34328 resumes
the disposable character and renders successfully. Logs confirm constraints on
real storage cabinets, shelving and crates, explicit-LOD sampling and 24-bit depth.
Frames 600–3300 show 59.99–60.01 completed callbacks/s, 5–6ms state age, 169 meshes,
zero dropped mesh requests; these are CPU callback diagnostics, not GPU timings.

Controlled native-input sweep: 482 authoritative samples, one position, unchanged
pitch, -0.800018rad yaw travel. Evidence:
`.local/reports/prop-boundary-level-sweep-player.json` and
`.local/captures/prop-boundary-level-sweep.mov` (frames 02/05 inspected).
Windows/radiator/wall remain present across the sweep; the dresser's source
projection and a hanging ceiling fragment remain visibly wrong. The initial
post-reload synthetic mouse event again produced a large yaw/pitch jump; that
failed input case is recorded separately in `prop-boundary-sweep-player.json`,
and pitch was restored with native input before the controlled test.
A bounded W test moved the authoritative player 1.068m north and returned to idle;
it is not a full collision/combat acceptance run. No new broad visual acceptance
checkpoint is declared. Normal saves/game binaries remain untouched.

Additional live traversal: ordinary A/W input reached the kitchen door; the
normal Interact key resolved `(10768,10267,0)`, object index 3, and PZ changed
`fixtures_doors_01_45` to its open sprite `fixtures_doors_01_47` (open flag set).
The player then walked through it to `(10768.848,10264.498,0)` without direct
position writes. Read-only evidence: `prop-boundary-door-state.json` and
`prop-boundary-exit-player.json` under `.local/reports/`.
`.local/captures/prop-boundary-exterior.png` shows the doorway still open and no
large prop protrusion on this facade; it also exposes a separate rejected result:
interior wall finishes show on the exterior and glass is still source-painted
opaque. This is not an exact before/after of the owner's pictured house.
The subsequent `prop-boundary-exterior-sweep.mov` and matching read-only player
trace include position/pitch changes not requested by the horizontal-only mouse
test. They are mixed-input evidence, not a controlled stationary sweep; further
automated input was stopped. Frames 02/05 show the source finish changing across
viewpoints and incorrect window visibility, still rejected. Source/diagnostic
implementation checkpoint: commit subject `Constrain physical prop surfaces at
authoritative opaque walls`; broad gameplay acceptance remains unchanged.

### Atlas boundary outlines: reproduced mip contamination

The latest wire-like outlines have a reproduced shared sampling defect.
Installed `GameWindow.enter()` loads Tiles2x with flag 0x40; `TextureID` uses
whole-page trilinear mipmaps. The old shader clamped to each sprite's half-texel
base-level rectangle but sampled coarse atlas mips containing neighboring art.
A production-GLSL GPU regression using a transparent sprite region surrounded
by unrelated opaque art produces 120 leaked pixels across nine alignment/scale
cases with the old sampler and zero after this repair. This demonstrates atlas
leakage, not proof that every line in the owner's screenshot shares that cause.

The shader computes source-pixel derivatives before divergent alpha branches,
then bounds both trilinear levels to a footprint contained in the sprite crop.
Near crop boundaries it uses base-level detail; the interior retains safe mip
minification. No PZ-owned texture data or sampler state is modified. On the tested
Apple GL implementation it uses `GL_ARB_shader_texture_lod`; a compatibility
fallback biases to base level, sacrificing mip antialiasing rather than sampling
unrelated art. The extension's explicit-LOD and derivative requirements were
checked against the [Khronos specification](https://registry.khronos.org/OpenGL/extensions/ARB/ARB_shader_texture_lod.txt).
This may retain more aliasing near crop edges; independent padded sprite mips
remain a possible quality improvement, not implemented here.

Raw GPU reports: `.local/reports/atlas-mips-before.txt` and
`.local/reports/atlas-mips-after.txt`. Fence coverage, crate edge, physical depth
occlusion and near coplanar tests remain passing; far-depth residual unchanged.
106 Java tests pass. Prior precision/fence batch is committed and remote-verified
as `de2681181f008d5e63b1fc1a3c3f7efc54c90055`.
PID 32654 was stopped before staging/launching PID 33199. This reload reached
PZ's new-character flow rather than a resumed living player; ordinary native
clicks selected the default Muldraugh location, occupation and generated character
in the disposable world. Live logs confirm `atlasMipIsolation=explicit-lod`,
24-bit depth and completed callback cadence 59.93–60.08Hz at frames 600–900,
fresh state 6–11ms and zero dropped mesh requests. This is not GPU frame timing.

Autonomous camera validation: `.local/reports/atlas-synchronized-sweep-player.json`
contains 481 authoritative samples with exactly one player position, unchanged
pitch and -0.45001rad horizontal travel; largest update step was 0.00250012rad.
Native input was `look -1 0 180`. Synchronized moving evidence:
`.local/captures/atlas-synchronized-sweep.mov`; inspected frames 02/04 show the
window/radiator/wall scene at different angles without the previous large wire
rectangles. This is one room, not acceptance of every attachment or all flicker.
The room still has missing furniture faces, source-projection distortion and a
small hanging wall fragment at the ceiling. No geometry completion is implied.

An earlier synthetic mouse sequence's first event caused a 1.875rad yaw and
-1.195rad pitch jump; subsequent events had the requested horizontal increments.
That first-event transition remains a failed automation/input case. Pitch was
restored through ordinary mouse events before the synchronized test. Another
trace completed before its intended motion and is not counted as motion evidence.

### Chunk-local construction and fence coverage (loaded; live acceptance pending)

The owner authorized unattended testing/reloads without preserving an old test
process. Only the isolated disposable client is targeted. The first reload
confirmed the production fragment shader on an actual 24-bit depth buffer.
Startup Continue and loading-screen continuation completed without a user click.
The loaded room differed from the owner's previous screenshots, so it is not a
controlled same-scene before/after. Stopping via SIGTERM can lose disposable-save
progress since the last save; do not use this on an ordinary user save.

`WorldMeshBuilder` previously constructed float vertices at large map coordinates,
then `GpuState.upload` subtracted the origin. This already lost small geometric
offsets before rebasing. Construction is now chunk-local from the start for all
three batch types, with the existing camera-relative matrix used directly.
World-space culling bounds are converted separately with conservative outward
rounding. A thin-polygon regression checks identical positions, normals, UVs and
layers at positive/negative/distant chunk origins. This repairs coordinate
precision, not intersecting source primitives or missing door geometry.

Fence/gate source families `fencing_*` and `fixtures_doors_fences_*` now use
alpha-tested coverage (threshold 0.5), opaque surviving samples, depth writes,
and no blending. They do not receive solid-wall edge expansion. Previously
partly transparent edges blended while writing depth without sorted rendering,
making background contribution dependent on submission order. Window glass and
blood overlays are deliberately NOT classified as opaque wire coverage. Proper
glass composition, including native actors behind it, remains outstanding.

106 Java tests pass, zero failures/errors (including installed polygon audit).
The actual production GLSL probe on Apple M4 Max passes 12 fence/background
coverage cases in both draw orders; the prior shader fails four of those cases.
Raw reports: `.local/reports/fence-coverage-before.txt` and
`.local/reports/fence-coverage-after.txt`. Existing physical-occlusion cases pass;
the previously reported depth residual beyond 16m is unchanged, not fixed.

Live PID 32654 logs `depth bits=24 ordering=fragment`. At completed frame 5400,
completed callback cadence was 60.01Hz, state age 13ms, 182 meshes, 66 visible,
425 cumulative builds, zero dropped requests. These are callback/CPU diagnostics,
not concurrent GPU timing. Native actor/item/vehicle callbacks continue.
`.local/captures/precision-fence-live.png` shows the actual scene; the accompanying
`precision-fence-live-motion.mov` is 351 captured frames, 5.85s at 3200x2056.
Capture rate is not gameplay FPS. The owner was still moving during collection;
this is not an automated controlled-input pass or visual acceptance of all seams.
`.local/reports/precision-fence-first-player-trace.json` records 182 authoritative
samples with real run/climbfence states and changing position/orientation.

New bounded helpers: `tools/observe_live_player.py --seconds 10 --output PATH`
only reads protocol snapshots; `tools/test_isolated_input.swift PID key CODE MS`
or `PID look DX DY FRAMES` validates the exact project-owned bundle and foreground
PID before native input, limits duration, releases held keys, and stops on focus
loss. Successful posting is not proof that PZ accepted the action. CUA native
pipe startup failed during this pass; native capture/input fallback is explicit.

Door-edge cracks, incorrectly placed props intersecting exterior walls, partial
roofs, source-painted glass and incomplete box-family faces remain rejected.
The newly requested time/weather-driven sky, longer streaming distance and
semantic physical light sources are not implemented by this repair batch.

### Concave source polygons: general topology repair (now loaded)

The preceding pass was progress: production-shader failure was reproduced and
the fragment-depth repair was committed/pushed as `e0a1b28`. This continuation
found an independent defect in `WorldMeshBuilder`: every authored polygon was
triangulated as a fan. Installed PZ `TileGeometryFile.Polygon.triangulate()` uses
a contour triangulator; its private shared native Clipper is not appropriate
to call from our background worker. The canonical compiler already used proper
concave triangulation, but the live renderer did not.

`PolygonTriangles` now triangulates simple contours with source-index-preserving
ear clipping, handles either winding, duplicate closure and straight intermediate
corners, and explicitly rejects degenerate/non-simple contours. The 256-point
bound exceeds this installed registry's largest contour (79 points). Registry
load logs and rejects an invalid polygon individually instead of letting it
terminate the mesh worker or silently inventing a fan. Both textured and flat
polygon paths use the triangles; textured normals come from a valid triangle,
not possibly collinear first contour points. The worker caches indices per
immutable registry primitive, so repeated map instances do not retriangulate.
No live object, collision, action or save is changed.

The installed-data audit actually read all **867 polygons** and found **279 old
fans with overlapping signed triangles**. The new triangulation preserves area
and winding for all 867. Examples include outdoor seating variants 16–19,
indoor seating, bedding, vegetation and commercial-wall primitives. This proves
a broad source-geometry repair, not that a particular screenshot's entire
artifact is fixed. Source projection, missing volumetric faces and real
primitive intersections remain independent problems.

Commands: the pinned `gradle -p bridge test jar` invocation below with
`PZFPS_GEOMETRY_AUDIT="$PWD/.local/assets/pz-42.20/tile-geometry.json"`;
105 tests pass, zero failures/errors. Additional fixtures test a U-shaped cutout
with both windings and every starting corner, invalid contours, and actual
`WorldMeshBuilder` output/UV correspondence. Without the audit environment
variable, the installed-data test is explicitly skipped. Raw audit output:
`.local/reports/polygon-triangulation-installed-audit.xml` (untracked).
Combined built JAR SHA-256:
`3d4cac781b377fa4aedf94d5e31eed06bd23d4e8b885091cb3e287114081b729`.

`tools/inspect_live_tiles.py --seconds 3 --radius 5 --output
.local/reports/polygon-repair-live-neighborhood.json` received 169 chunks and
102 nearby squares from the still-running original PID 31018. That read-only
snapshot is evidence of current authoritative state, not execution of the new
mesh code. The owner was continuing to explore; a nonblocking reload-choice
question was sent and no restart or external deployment performed this pass.
Next: load this combined artifact once coordinated, inspect affected contours
and the existing window/bracket/bench depth cases in motion, and measure actual
completed render cadence. Last accepted checkpoint is unchanged.

### Current repair: reproduced perspective depth-layer instability

The owner's window/sill, wall bracket and bench screenshots remain rejected
for camera-dependent surface ordering. The actual embedded shader applied a
nonlinear eye-distance bias at triangle vertices. Different tessellations of
the same plane therefore received different interpolated depth surfaces.
This is a reproduced renderer defect, not proof that every reported artifact
has the same cause. Frustum culling does not resolve overlapping visible faces.

`tools/check_world_shader.cpp` now renders coarse and subdivided coplanar
surfaces using the actual production shaders, 24-bit depth, five slopes, four
depths and both submission orders on the Apple M4 Max. The previous shader
failed 20 of 40 cases; raw output: `.local/reports/coplanar-depth-before.txt`.
`InProcessWorldRenderer` now applies ordering per fragment, preserving the
unmodified projected silhouette. It reads/logs the actual framebuffer depth
bits and uses a two-depth-unit minimum where possible, with an **8mm total
eye-depth displacement cap**. An intermediate uncapped version passed all
40 cases but could produce unacceptable distant displacement; it was rejected.

The final GPU probe reports zero wrong pixels within 16 metres eye depth,
10,044 wrong pixels beyond that range across eight cases, and zero failures
in ten physical-occlusion cases. The latter use 2cm separation through 32m,
10cm at 100m; 2cm at 100m was below reliable precision and failed an intermediate
check. Distant precision is explicitly unresolved, not hidden by a success
label. Alpha-cutout, closed-crate and light-only GPU checks still pass.
Output: `.local/reports/coplanar-depth-after.txt`.

Commands actually run: `clang++ -std=c++17 -Wno-deprecated-declarations
-framework OpenGL tools/check_world_shader.cpp -o .local/build/check_world_shader`,
the resulting executable with `InProcessWorldRenderer.java`, and the pinned
`gradle -p bridge test jar` command below (100 tests, zero failures/errors).
Built JAR SHA-256:
`6e3be0ea9c749ea5f910398a7297f741b44e4514a9cf38937a92a474dcb0be64`.
This batch is **source/GPU-tested, not live-tested or staged**. Explicit fragment
depth may affect early-depth rejection; concurrent gameplay performance must
be measured after loading. No performance claim is made from the tiny probe.

`.local/captures/depth-layer-before.png` preserves the running old shader's
world view with the guide reminder modal. F8 release was verified by cursor
mode `212995 -> 212993`, then the observed OK button was clicked. The second
region capture (`depth-layer-before-ui-cleared.png`) caught the owner's desktop
switch and is **not visual test evidence**. No restart was performed during
this pass; the owner continued exploring. The startup-guide checkbox change
from the preceding pass is confined to the disposable project profile.

The owner's latest red-wall building screenshots still show malformed prop
faces and wall/fixture interference. Current crate pictures confirm only the
loaded family baseline, not general box completion or a full orbit acceptance.
The last accepted checkpoints remain standing-zombie appearance and improved
ground/wall continuity; neither all props nor flicker-free gameplay is accepted.

Lighting reference checked: the developers explicitly distinguish B42's
separate view cone from propagated illumination in
[Hmm, Upgradez](https://projectzomboid.com/blog/news/2024/01/hmm-upgradez/).
The existing independent RGB-light transport remains in use; this depth repair
does not alter illumination, remove physical darkness or change gameplay LOS.

### Current repair: native character preparation and display-thread capture

The preceding goal turn made concrete progress (committed cursor/reticle work
and live evidence). This continuation reproduced the still-disabled actor pass
from the actual running process, then traced its exception to a skipped native
preparation call. `IsoSprite.renderActiveModel()` calls `model.updateLights()`
before `ModelSlotRenderData.initModel/init`. Our replacement now does the same
for actors and held equipment. An intermediate live run reached native model
callbacks without the former `playerData` null pointer; logs are retained in
`.local/reports/live-364f650-actor-lighting.txt`.

That run also exposed zero native alpha. The final source applies perspective
opacity to the retained snapshot and to the already-copied `Alpha` shader
properties, including PZ's last-ready fallback models. Live actors remain
authoritative and `isInvisible()` actors remain excluded. All local-player
representations are excluded from the nonlocal actor pass. New `notReady`
counters distinguish unavailable models from completed ready-model callbacks;
those callbacks do not prove visible pixels, correct pose or gameplay.

Cursor inspection found the direct competing writer in the installed private
`Display.updateMouseCursor()`: it selects GLFW hidden/normal mode independently
of `Mouse.setGrabbed()`. The new `DisplayCursorPatch` takes ownership at that
exact method after FPS initialization. Game input publishes volatile intent;
GLFW calls occur on the display/event thread, with readback. Focus loss releases
capture; the first delta after capture transitions is discarded. The live
intermediate run confirmed `212993 -> 212995`, release `212995 -> 212993`, and
recapture `212993 -> 212995`, all on PZ's thread named `main` (distinct from its
simulation `MainThread`). Evidence is in
`.local/reports/live-b62a9dc-display-cursor-actor.txt`. This supersedes the earlier
claim that a main-thread per-poll repair plus `setGrabbed` filtering fully
addressed cursor ownership.

Commands run for this repair: installed decompiled source reads and `rg`,
`gradle -p bridge test jar` with the project-local Gradle and pinned JAR paths
below, `game status-isolated`, `game stop-isolated`, `game stage-isolated`, and
`game launch-app-isolated`. All 83 Java tests pass, including bytecode inlining
of the exact private display method. Each restart tested a changed artifact:
lighting preparation, display-thread capture/actor opacity, then copied shader
opacity. Only one isolated process was running at each launch. The final
artifact's live observation follows below; accepted gameplay remains pending.

### Zombie-priority repair: world placement and missing skin textures

The owner's screenshots show bodies half below their floors, upstairs legs
protruding into the room below, invisible skin with separate clothing/hair, and
nearly invisible crawlers. These are rejected results, not accepted actors.
Preserved originals: `.local/captures/zombies-buried-missing-body-before.png`
and `.local/captures/zombies-upstairs-legs-before.png`. Wall/floor seams and
misprojected/misplaced stools, chairs, ovens, bathtubs and barrels remain open;
they must not be concealed by texture completion.

Two concrete causes were established:

1. `FirstPersonCharacterCamera` copied the isometric `-0.48` model-origin offset,
   sinking a sampled standing foot approximately 0.61 world metres below its
   square. It now uses the installed `Model.vectorToWorldCoords` mapping:
   reflect model X, rotate by the evaluated heading, convert native model height
   to elevation with `0.61237234`, then map levels to the renderer's 3 metres.
   A regression calls the installed function as an oracle for several headings
   and sampled standing/climbing bones. The shared unseated mapping also applies
   to prone/crawling poses; no independent animation or position update is added.
2. Live JAR `c1e29f2` reached native body meshes but logged `texture=missing` for
   both `Base.FemaleBody` and `Base.MaleBody`. The skipped native
   `IsoGameCharacter.render` calls `checkUpdateModelTextures()` before sprite
   rendering. `NativeCharacterPresentation.prepareModel` now preserves that
   producer-thread preparation, plus `updateLights`, for actors and held models.
   PZ creates its own skin/outfit/blood/equipment textures; the bridge does not
   invent replacements. Evidence: `.local/reports/live-c1e29f2-missing-body-texture.txt`.

Incomplete native root/outfit snapshots now retain the diagnostic silhouette
instead of suppressing it just because snapshot allocation succeeded. The
256-actor budget ranks actual 3D distance, so many distant storeys at similar
x/y cannot displace a closer threat. All elevations remain eligible. Native
draw logs distinguish root, texture presence, palette size and instanced path;
completed callbacks still do not certify visible/complete actors.

Added `tools/inspect_live_actors.py`, a bounded read-only loopback protocol-5
inspector. `python3 tools/inspect_live_actors.py --seconds 3` captured native
pose/part identity and bone coordinates; retained output is
`.local/reports/live-actor-poses-before-transform.json`. It sends no input or
game actions. The previous log is
`.local/reports/live-604785-before-bone-transform.txt`.

`gradle -p bridge test jar` now passes 87 Java tests, including native-coordinate
agreement, copied alpha correction, missing-body fallback, and tower priority.
Reloads tested the corrected world transform with per-part diagnostics, then
the newly evidenced missing texture-preparation call. The latest JAR above is
launched; complete standing/crawling/climbing appearance, combat and gameplay
acceptance remain pending. The last accepted checkpoint remains the earlier
coherent room diagnostic, not accepted zombie rendering.

**Subsequent live evidence:** JAR `f0b2125` now logs real `CharacterSmartTexture`
on `Base.MaleBody` plus native clothing textures, rather than missing skin.
`.local/reports/live-f0b2125-body-textures.txt` retains those part records.
`.local/captures/actors-texture-preparation-live.png` shows an approaching,
fully textured standing zombie with its body above the ground. The owner
explicitly accepted its appearance ("zombies actually look really ... great");
their screenshot is `.local/captures/standing-zombie-owner-accepted.png`.
This advances the **standing-zombie visual checkpoint**, not crawler, climbing,
ragdoll, multi-floor, combat or overall gameplay acceptance. Actor/cursor repair
was committed and remote-verified at `502dd458f6effabd485cfc4b986f2b8516efc475`.

### Surface continuity and source-coordinate repair

The owner requested two-sided existing surfaces, then lighting/sky/distance,
then carefully reused first-person body/action presentation and interactions.
Current implementation remains on the first step, not the entire roadmap:

- `InProcessWorldRenderer` draws source-textured batches without face culling;
  `WorldMeshBuilder` no longer duplicates the previous reverse wall quads. The
  same existing triangle and source alpha are visible from either side. This
  does not create the missing back of a volumetric object or new gameplay geometry.
- Per-batch solid-floor/wall classification permits narrowly bounded alpha-edge
  repair. Three extracted floor sprites store 126x64 raster diamonds inside a
  nominal 128-pixel footprint; ordinary wall side faces also stop before their
  nominal tile edge. Atlas sampling stays inside the sprite's texel centres.
  Floor repair samples within two source pixels at the perimeter only. Wall
  repair samples along the panel tangent within six pixels at outer joins only.
  Door/window/fence/prop batches do not receive wall repair. Stairwell geometry
  remains absent and internal texture alpha is not globally filled.
- `tools/check_surface_edges.py` evaluates the shader's alpha rule against real
  extracted manifests. At 66,049 samples per sprite, three floor samples go from
  268–284 fully transparent samples to zero, with zero changed interior samples.
  A west wall goes from 1,779 to zero; a north wall from 3,918 to 78 (remaining
  endpoints are not claimed fixed). Reports: `.local/reports/floor-edge-alpha-check.json`,
  `north-wall-edge-alpha-check.json`, and `west-wall-edge-alpha-check.json`.
  These are CPU source-alpha checks, not GPU visual/performance acceptance.
- Prop inspection established two incorrect shared conversions: the previous
  manual rotation was `Rz*Ry*Rx`, whereas installed `TileGeometryUtils` uses
  JOML `Rx*Ry*Rz`; and raw authored vertical units were treated as world units.
  Native 2.44949-unit storeys map to our 3-unit storeys, a `sqrt(1.5)` Y factor.
  Source projection now uses 78.38367 pixels per authored Y unit, rather than 64.
  A regression invokes the installed projection setup as the oracle. Polygon
  points now start on XY because their explicit rotation already encodes the
  plane; the former plane mapping applied orientation twice.
- The canonical adapter shares the corrected rotation/polygon convention,
  records its raw authored coordinate space, and documents the corrected
  projection. Existing immutable compiled assets remain historical and need
  fresh validation; their old silhouette fits do not establish correct scale.

Tests: 89 Java tests pass; `PYTHONPATH=canonical .local/canonical-venv/bin/python
-m pytest canonical/tests -q` passes 26. An initial Python run imported the old
non-editable installed package and failed the new regressions; running checkout
source exposed one old polygon fixture relying on the wrong double-orientation
behavior. The fixture now specifies the native plane rotation explicitly.
Commands also include `assets extract-sprite` for `walls_interior_house_02_96`
and `_97`, the three `tools/check_surface_edges.py --kind ...` runs, and one
batched isolated reload for these surface/coordinate changes. Source screenshots
are `.local/captures/wall-seams-before.png`, `floor-seams-before.png`, and
`grill-misprojection-before.png`. Live seam/prop improvement remains unaccepted
until the new artifact is inspected; this is not complete asset reconstruction.

Next smallest check: inspect the loaded surface batch on adjoining grass/floors,
both wall orientations and an existing opening, then the rotated grill/fixture.
Keep standing zombie appearance intact and test a prone/crawling actor before
claiming general actor correctness. Only then move to the requested lighting/
sky/distance work; actor and interaction correctness remain independent gates.

**Loaded surface result:** JAR `20a00e9`, PID 27987, reached the real world and
compiled the new shader without renderer failure. `.local/captures/surface-repair-live.png`
shows adjoining ground and exterior panels with substantially fewer open joins,
but remaining roof/prop fragments and incomplete surfaces are still obvious.
This is an assistant-observed improvement, not blanket owner acceptance.
`.local/reports/live-20a00e9-surface-repair.txt` retains native textured actor
draws and completed world callbacks. Active samples report approximately 60
completed callbacks/second with 5–6 ms snapshot age; these are not measured
game FPS, GPU timings or a matched performance baseline. Commit `19a18d0` was
pushed and remote-verified.

**Next source repair, not yet loaded:** further native inspection found cylinders
are Z-axis primitives centred at +/- height/2 (`CylinderUtils.intersect`), not
Y-up primitives running from zero to height. Both live and canonical paths now
match that convention and select whichever cap faces the source after rotation.
The regression calls the real native intersection routine and checks the emitted
upright cylinder vertices. Its first bounds assertion mistakenly tested the
chunk's conservative culling bounds (which include a full storey), then was
corrected to inspect actual vertices. The generic entity fallback also no longer
turns every untyped `moving` object—including blood/giblet physics effects—into
a human-sized blue box. Actor/vehicle safety silhouettes remain. This does not
claim a finished native blood/particle presentation.

The actual grill was compiled with corrected source coordinates into two
project-local immutable outputs using `PYTHONPATH=canonical ... -m pzcanonical.cli
from-pz`: `.local/canonical-grill-native-coordinates/` and, after cylinder repair,
`.local/canonical-grill-native-cylinder/`. The latter source silhouette IoU is
0.890403 with 228 triangles; 4,072 of 11,995 surface texels are observed and 7,923
are harmonic prior-only. It is an offline diagnostic, not a live asset replacement
or neural completion. The body-texture and room checkpoint remains intact;
cylinder/particle source changes await the next batched reload.

**Owner feedback and follow-up, 2026-09-17:** the owner explicitly
accepted improved grass/floor/exterior-wall continuity, but reported door gaps,
roof-edge strips, intersecting props, missing cabinet sides and black floor
squares under furniture. These remain failures, not accepted completed assets.
The blue cubes during attacks match the generic-moving fallback removed above.

`tools/inspect_live_tiles.py --seconds 4 --radius 4` received 169 chunks from the
existing client. At (7463,5862,0) and (7464,5864,0), the snapshot contains a real
`floors_exterior_street_01_17` object with flag 2048 (solidfloor), while square
flags are 4 (roof, no cached solidfloor). Installed `IsoGridSquare.isSolidFloor`
merely reads its lazy collision cache; `TreatAsSolidFloor` populates it. The
builder now accepts actual floor-object evidence without mutating that cache,
and still preserves `stairsBelow` openings. A regression starts with a false
square cache and a real floor flag. Later repeat inspections while simulation
was paused received zero chunks; their report is not usable scene evidence.

The seam shader's wall candidates now reject pixels outside the source crop,
instead of clamping its topmost pixel indefinitely up a full-height panel. The
CPU alpha-check reference matches this correction. Capture now includes native
`DoorWallN/W`, `WindowN/W` and `cutN/W` orientation flags: collision-free door
frames otherwise had no edge, causing the mesh builder to emit nothing. This
does not yet establish correct door animation, full assembly or prop placement.

The batched source builds with 91 Java tests passing. Native cylinder tests and
floor-cache regression are offline evidence; the running PID 27987 still uses
the prior surface build until explicitly staged/reloaded. No accepted lighting,
sky, render-distance expansion or full first-person body work is claimed.

**Loaded floor/opening batch:** commit `b484ae4` was pushed and remote-verified.
`stop-isolated`, `stage-isolated`, `launch-app-isolated` replaced only the tracked
isolated instance with PID 28679 at 22:13:37 UTC, JAR `9a6c54aa` above. Console
before reload is `.local/reports/live-20a00e9-before-floor-repair.txt`. The new
read-only tile inspection received 195 chunks and saved nearby data to
`.local/reports/live-9a6c54-tile-floor-evidence.json`; actual floor objects with
false square caches are preserved there. `industry_01_10` now has west-edge
flag 64 instead of zero. Owner screenshots show the previously black occupied
floor is present (`occupied-floor-before.png` / `occupied-floor-after.png` in
`.local/captures/`), but still report shelf/window/bench flicker. This is not
surface or interaction acceptance. Existing game startup reported invalid room
metaIDs; no renderer exception was observed in this run. Callback samples were
~60 Hz, state age 4–5 ms during active simulation, not a gameplay/GPU benchmark.

**Loaded batch, not yet live-accepted:** shared precision/layer repair
uses direction-only camera rotation (no `eye + unitDirection` quantization at
large map coordinates), chunk-local GPU vertices and chunk-relative camera
matrices. Native actor cameras retain world coordinates and the same projection.
All source surfaces now carry their immutable source-object order, with a
depth-only separation bounded to 0–8 mm in eye distance, rather than physically
offsetting only fallback walls. It does not grow to metres with render distance.
The world pass explicitly sets/restores depth range, depth function, polygon
offset enable and alpha-test enable. This targets coplanar-layer instability;
it does not repair objects genuinely intersecting other geometry or prove every
flicker source eliminated. Regression checks compare identical nearby/distant
scenes and ensure rebasing preserves texture, normal and layer attributes.

`BoxSideCompletion` uses PZ's actual `Facing` metadata to select side faces of
authored boxes and copy the opposite observed side's UVs. It never maps the front
to the back and never adds another copy of an already observed face. Without
orientation evidence it abstains, except for the inspected four-view tool-cabinet
family `location_business_machinery_01_32..35` (Tiles2x100; local source manifest
`.local/assets/pz-42.20/toolchest-source/`). This is deterministic side completion,
not complete asset reconstruction: backs, bottoms, occluded source regions and
other kinds of missing geometry still need treatment. `appearanceFacing` is
in-process immutable appearance metadata; protocol 5 intentionally remains wire
compatible and does not export this new field yet. Tests cover four cabinet
orientations, native facing routing, unchanged unrelated assets and donor UVs.

The batch passes **96 Java tests, zero failures/errors**, and 26 canonical
tests. Commit `d816d6095cb531bf3f22a2316d750df549cbb92e` was pushed and
remote-verified. One `stop-isolated`, `stage-isolated`, `launch-app-isolated`
sequence loaded the changed JAR; no additional instance was launched.
The preceding console is `.local/reports/live-9a6c54-before-layer-repair.txt`.
The new console is `.local/reports/live-c359265-layer-repair.txt`: at simulation
frame 9458 it reports 11,700 completed render callbacks, sampled callback rate
60 Hz, 169 cached meshes / 62 visible, state age 5 ms. Later pause/focus loss
correctly increased state age while callbacks continued. These are callback
measurements, **not measured game FPS, GPU completion or visual acceptance**.
The console has vanilla startup Lua/asset/room metadata warnings but no observed
PZFPS renderer exception or shader compilation failure.

UI verification was incomplete: the computer-use service failed to initialize;
initial captures showed the startup Survival Guide. AppleScript and immediate
CGEvent down/up did not visibly dismiss it. `tools/click_isolated_game.swift`
is a bounded fallback requiring an exact foreground PID and coordinates inside
its current window; it now holds a click for 120 ms to cross an input poll.
The subsequent attempt correctly refused because Terminal was foreground.
By then the existing game had progressed to active gameplay, as its console
records; do not attribute guide dismissal to the unverified click helper.
Direct window capture later failed with `could not create image from window`
while the game window was offscreen. No extra restart or forced focus change
was used. The last accepted visual checkpoint remains the owner's improved
standing zombies and continuous floors; window/shelf/bench flicker is pending
moving-view acceptance. Before images are preserved as
`.local/captures/layer-window-before.png` and `layer-bench-before.png`.

**Lighting diagnosis, not a lighting fix:** current world meshes bake captured
square RGB, clamp it to >=0.42, and do not invalidate on light changes. Therefore
the owner's blockwise outdoor brightness is not accepted physical lighting.
Installed `LightingJNI.JNILighting` separately reads visibility, `lightInfo`,
`darkMulti`, `targetDarkMulti`, raw `lightLevel` and vertex-light values; replacing
one with another or forcing gameplay visibility is not justified. Lighting needs
a separately refreshed presentation input, without isometric seen-state masking
and without rebuilding geometry for every light change. The developers describe
the historical view-arc lighting in [Knox Event: 30 Years On](https://projectzomboid.com/blog/news/2023/07/knox-event-30-years-on/)
and physical B42 light propagation in [Upstairs Downstairs](https://projectzomboid.com/blog/news/2022/09/upstairs-downstairs/).
No fullbright workaround or gameplay visibility mutation was applied.

## Truthful acceptance state

### Closed wooden crates (source and offline/GPU-tested batch)

The previous goal turn was progress: independent lighting transport was
implemented, GPU-tested, loaded and measured in the real isolated client. This
continuation returns to the owner's visible missing-face problem rather than
treating lighting transport as completed presentation.

Installed B42 `carpentry_01_16` and `carpentry_01_19` are complete, single authored
boxes with min `(-.5,0,-.5)`, max `(.5,.8,.5)` and no local transform. Source
Tiles2x17 was extracted and visually inspected; manifests and original atlas
remain under `.local/assets/pz-42.20/crate-source/`, atlas SHA-256
`c21ffdfc5a000d26a0b8b4a6288d93b51d30e4c66cccd855aff9accdb43cb9f6`.
The source sprite widths are 110 and 113 pixels respectively, versus the
authored box's 128-pixel projected width. Missing geometry and raster-trimmed
alpha were both contributing to open-looking crates.

`BoxSideCompletion.closedCrate` and `WorldMeshBuilder.addCrateBottom` now emit
the missing opposite X/Z panels and bottom, exactly six faces / 36 vertices,
without expanding geometry or duplicating observed faces. Bottom wood is an
explicit completion prior sampled from a vertical panel, not a copied metal lid
or recovered unseen surface. The whole-crate shader route fits the projection
to the stored sprite bounds and extends edge colour within four source pixels.
Only this inspected family gets closed-solid alpha treatment; stacked fragment
sprites, shelves, chairs, windows and the drawer-bearing tool cabinet do not.

`tools/check_crate_surfaces.py` unwraps the six faces as an offline CPU reference.
Run with the project-local canonical venv and the two manifests, using
`--output .local/reports/crate-surfaces`. Inspected images and counts are there
and in `.local/reports/crate-surface-check.json`: each face has 36,864 samples;
198–3,110 samples per face use edge extension, and none require the centre-colour
fallback. The previews retain source painted shading and some skew/distortion;
they are not photorealistic materials or live appearance acceptance.

The expanded actual-shader GPU probe preserves an ordinary transparent hole
(background red 51) while repairing the same sample only in closed-crate mode
(lit surface red 128). Existing dynamic-light tests still pass. Evidence:
`.local/reports/crate-shader-gpu-probe.txt`. Java regression checks six unique
face normals, unchanged extents, bottom donor selection and family exclusions.
No light, collision, interaction, save or object identity is invented by this
completion rule. A live crate orbit remains the next acceptance check.

### Independent lighting transport (loaded; source/GPU and live execution tested)

`ChunkLighting`, `WorldCapture.lighting`, `BridgeRuntime.captureLighting` and
the world shader now refresh illumination separately from geometry. On the
verified game thread, the native `lighting[playerIndex].lightInfo()` getter
refreshes PZ's lazy cache before the bridge copies raw square RGB. No visibility,
darkMulti, collision or action state is overridden. Capture visits up to four
chunks per update and checks a 2 ms time budget between chunks (not a hard
preemption guarantee for one slow chunk). Latest immutable values coalesce in
a bounded 4096-chunk store; unload removes them.

Geometry retains material colours plus an explicit source-square lighting index.
GPU lighting uses a separate 8x512 RGBA grid per chunk (16 KiB, B42 levels
-32..31); only changed grids upload, and only visible chunks need GPU resources.
Sampling is source-square-owned, avoiding accidental sampling of the upper floor
at wall/ceiling vertices. Texture unit 1 and unpack state are restored after the
pass. The wire-protocol diagnostic frontend is unchanged; this is the live
in-process renderer's lighting path.

All **99 Java tests pass**, including light/visibility-independent geometry,
immutable copies, changed versus unchanged content, negative elevations,
source-square assignment, coalescing, removal and bounded storage.
`tools/check_world_shader.cpp` compiles the actual Java-embedded GLSL in an
accelerated offscreen macOS context and reads rendered pixels back. Commands:

```sh
clang++ -std=c++17 -Wno-deprecated-declarations -framework OpenGL tools/check_world_shader.cpp -o .local/build/check_world_shader
.local/build/check_world_shader bridge/src/main/java/dev/pzfps/bridge/InProcessWorldRenderer.java
```

Result on Apple M4 Max, `2.1 Metal - 89.4`: shader compile/link passed; material
pixel brightness changed 251 -> 126 after only a light-grid update; textured
surface pixel became 128. One geometry upload, two light uploads in this fixed
test. Evidence: `.local/reports/dynamic-lighting-gpu-probe.txt`. The first probe
reported an incomplete unused source sampler; binding a valid white source
texture removed that warning and both rendering branches passed without GL
errors. This synthetic GPU test is not a concurrent gameplay benchmark.

The prior 0.42 exposure floor is deliberately retained in the shader for this
transport change. Raw RGB remains unmodified in the snapshot. This does **not**
yet establish physically correct lighting, eliminate all view-arc effects,
smooth square boundaries or add a sky. Live upload counts and visible maximum
light age are logged independently; actual light changes in gameplay still need
acceptance. No new live acceptance is inferred from these tests.

**Live execution:** source commit `8e404aede5381792ca5f2cf0bd1574b2f9285ba8`
was pushed and remote-verified. The tracked isolated PID 29349 was stopped at
22:49:42 UTC; `stage-isolated` and `launch-app-isolated` loaded JAR `08b955fa`
in PID 30326 at 22:49:45. No second game instance or installed game modification.
The 120 ms native click at logical `(1425,905)` **successfully dismissed** the
Survival Guide, confirmed by the before/after captures and active simulation.
This supersedes the unverified click result from the previous batch.

Console `.local/reports/live-08b955f-dynamic-lighting.txt` records 6,000 completed
render callbacks, sampled callback rate around 60 Hz and player snapshot age
5–6 ms. Meshes built stayed at 169 while lighting reached 61 GPU grids / 122
uploads. Subsequent unchanged grids caused no further uploads. Visible maximum
light age ranged about 0.45–0.70 seconds in these stationary samples; this delay
is a limitation, **not accepted torch/fast-light response**. No PZFPS renderer
exception or shader compile/link failure was found in this run.

Evidence: `.local/captures/dynamic-lighting-start.png` (guide),
`dynamic-lighting-after-guide.png` (actual world), and
`dynamic-lighting-live.mov` (stationary raw game window). The latter was captured
with `screencapture -v -V 6 -R 100,62,1600,1028`; `ffprobe -count_frames` decoded
352 frames at 3200x2056 over 5.866667 seconds. This is **recording throughput,
not game FPS**. `dynamic-lighting-clip-middle.png` is an inspected frame extracted
with ffmpeg. No enhanced/neural clip is implied. These images still expose
incomplete crate faces, shelf/source-projection defects and ceiling/material
limitations. This pass verifies live lighting transport, not correct physical
lighting, moving-view flicker acceptance or completed gameplay.

The isolated game was left **paused**, visually confirmed in
`.local/captures/dynamic-lighting-end-state.png`. A direct click at the pause
icon while FPS capture was active instead moved the camera; do not use the
dialog click helper in captured mode. An immediate AppleScript F2 did not
reliably pause; a foreground-PID-guarded native F2 down/150 ms/up did. The actual
profile `.local/pz-runtime/user-cache/Zomboid/Lua/keysB42.ini` maps Pause to
LWJGL key 60 (F2). No keybinding, system setting or gameplay rule was changed.

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
  that the previous whole-world flashing had stopped. The live `0555930`
  artifact also exposes safe reverse structural faces and topology-derived
  ceilings; its first completed sample reported `mirroredStructuralFaces=3955`
  and `completedInteriorCeilings=11834`. Source textures are projected onto
  indexed geometry where available; PZ's normal UI remains visible over the
  perspective world. Expected holes, wrong/incomplete object shapes, missing
  unseen surfaces, crude actor/world-item representations and material/
  projection errors remain visible.
- **Accepted live first-person gameplay:** **NO**. The owner rejected the last
  live build because lateral reversals still inherited PZ's third-person
  turn-to-travel delay, and Space could expose an isometric cursor while GLFW
  mouse-look remained captured. The newest source instead makes camera yaw own
  ordinary locomotion facing, selects B42's native no-aim left/right/backward
  strafe family, and redirects only normal locomotion's native root-motion
  magnitude along camera-relative WASD before PZ's collision path. Canned
  actions, climbing, vehicles, ragdolls and timed actions are excluded. The
  Space cursor request is suppressed only while capture is active. The owner
  then observed two additional live failures: the pointer could escape the
  window while the bridge's logical state still said captured, and the reused
  `crosshair00.png` appeared as a parenthesis rather than a complete reticle.
  The live `d9d4c69` build blocks a late low-level ungrab while focused,
  compares the real GLFW cursor mode on every mouse poll, repairs drift, and
  draws a symmetric four-tick reticle. The loaded process measured GLFW mode
  `212994` (hidden, but not captured) while gameplay capture was expected and
  repaired it; a screenshot confirms the complete four-way reticle at the
  optical centre. Physical edge confinement, Space/shove and deliberate
  F8/UI/focus release still require owner acceptance. Continuously tracked
  centre-view selection, aiming/combat and ordinary inventory/container play
  also remain unaccepted.
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
6. The live `d9d4c69` artifact passes 82 Java tests and is loaded in PID 25368.
   Its safe wall reverse faces and structural ceilings are visibly active; its
   native actor pass then failed during model snapshot preparation because
   `modelSlot.model.playerData` was null. The continuation above repairs that
   failure; complete visual actor acceptance remains outstanding. The pointer-capture hook transformed
   the exact installed descriptor and repaired a measured native-mode drift at
   frame 2. The last canonical suite run passes 45 Python tests.

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
  Every report deterministically ranks the eight most repeated unsupported
  sprite identities. Chunks containing only honest holes use their
  authoritative chunk volume for distance/frustum classification and remain in
  coverage without submitting a draw call. It now separately counts and ranks
  `topCollisionHoles` using B42's captured `solid`/`solidtrans` facts, so an
  invisible movement blocker can be prioritized without drawing fabricated
  collision geometry or changing PZ's authoritative collision.
  It also consumes persistent material batches. The first recipe is a
  continuous-world-coordinate interior plaster ceiling, which avoids restarting
  a texture pattern at chunk boundaries and remains separate from source sprite
  batches. The newest shader source removes the prior very high-frequency
  sinusoid that produced visible distance banding/moire; it retains only subtle
  broad variation and is still a provisional material, not recovered source
  appearance.
- `bridge/src/main/java/dev/pzfps/bridge/WorldMeshBuilder.java` — converts
  immutable chunk snapshots into source-textured indexed geometry, puts
  structural north/west faces on square boundaries instead of centered slabs,
  and retains conservative fallbacks for unsupported objects. Each built chunk
  carries explicit counts for source-textured versus flat floors, authoritative
  stair openings, indexed objects, structural fallbacks, native items,
  unsupported-object holes and safety-cap truncation so later completion work
  can be prioritized from real scene evidence. A solid-floor square with B42's
  `HasStairsBelow` flag deliberately omits the generic full-tile floor plane;
  stairs on the current level do not remove the supporting floor beneath them.
  Unsupported sprite identities and counts are retained immutably per chunk so
  repeated holes can be selected as a structured asset-completion backlog.
  A structural wall fallback with no authored geometry now emits an exact
  reverse-winding source-textured face. Back-face culling selects one winding
  per viewpoint, so the two sides do not compete in the depth buffer. Doors,
  windows and authored geometry are excluded. Interior ceilings are completed
  only when room topology plus an upper solid floor or `haveRoof` proves the
  boundary; stairs, stair tops and `HasStairsBelow` openings remain open. The
  renderer reports `mirroredStructuralFaces` and
  `completedInteriorCeilings` separately.
- `bridge/src/main/java/dev/pzfps/bridge/WorldCapture.java` and
  `WorldState.java` — capture geometry/state plus authoritative stair, stairs-
  below and stair-top flags. These flags preserve portal facts and now prevent
  a false floor across the opening. The capture itself does not fabricate
  geometry; the builder's separately measured ceiling rule consumes these
  facts.
  Tile-object snapshots now also retain B42's `solid`, `solidtrans` and
  `blocksight` properties. The first two classify collision-critical visible
  holes; they do not grant the renderer authority to alter collision. Each
  object also records B42's real `solidfloor` property so first-person context
  selection can intersect a thin floor plane instead of an entire three-metre
  tile volume.
  `WorldMeshBuilder` uses that property as the primary floor-texture identity
  and duplicate-suppression rule, with the historical `floors_` prefix only as
  compatibility for older snapshots. This preserves real nonstandard/modded
  floor identities instead of classifying them as unsupported 3D objects.
- `bridge/src/main/java/dev/pzfps/bridge/FirstPersonInput.java` — uses GLFW
  relative mouse deltas without macOS cursor warping, reads PZ's actual physical
  key bindings simultaneously, and returns the inverse of B42's isometric input
  transform so camera-space WASD remains in world space. `Toggle Inventory`
  releases capture on the same mouse-input poll instead of waiting for the Lua
  UI visibility change.
  Its deferred request now redirects the direction—not the magnitude—of B42's
  normal-locomotion root motion, after which PZ still calls its own
  `moveUnmodded` collision path. Off-axis input opts into the installed B42
  no-aim strafe blend (`DeltaX`/`DeltaY`); camera yaw is reasserted before and
  after the player update so A-to-D reversal does not require a 180-degree body
  turn. The adapter is disabled for canned/timed actions, climbing, vehicles
  and ragdolls. Cursor capture no longer trusts the LWJGL compatibility
  wrapper's cached flag. Input publishes requested ownership; the hooked
  display-thread cursor method compares and reads back GLFW's actual mode.
- `bridge/src/main/java/dev/pzfps/bridge/patches/DisplayCursorPatch.java` —
  replaces the installed private `Display.updateMouseCursor()` after FPS
  initialization, preventing its direct GLFW write and cursor warp from
  overwriting the chosen capture mode. It retains normal menu initialization
  before first-person input starts.
- `bridge/src/main/java/dev/pzfps/bridge/NativeCharacterPresentation.java` —
  removes isometric alpha fading from native retained character snapshots and
  their copied shader properties, without mutating the character's alpha state.
- `bridge/src/main/java/dev/pzfps/bridge/PerspectiveViewRay.java` — defines the
  single normalized centre-view contract used by the renderer camera,
  interaction ray, PZ aim-vector override and native ballistics adapter. It
  removes the prior independent yaw/pitch reconstructions, including the
  renderer's old pitch clamp that could make its optical centre disagree with
  the action ray near a steep look angle.
- `bridge/src/main/java/dev/pzfps/bridge/ReticleTracker.java` — probes the same
  ray at a bounded 20 Hz, re-resolves a snapshot hit to the live PZ object and
  publishes only presentation state to the Lua UI. Interactable objects and
  dropped items are distinguished from ordinary world surfaces. After B42's
  native ballistics query, its accepted camera-target count can supersede that
  geometric state. This tracker never authorizes, executes or damages a target.
- `bridge/src/main/java/dev/pzfps/bridge/WorldCapture.java` publishes the
  mouse-owned perspective yaw as camera/reticle direction independently of the
  movement vector. `BridgeRuntime` now keeps the ordinary local locomotion body
  and action facing on that yaw while A/D/S select translation relative to it.
  This supersedes the rejected turn-the-body-toward-every-WASD-vector behavior
  and is source-built but not live-accepted.
- `bridge/src/main/java/dev/pzfps/bridge/CursorCaptureState.java` — distinguishes
  gameplay capture, deliberate F8 release and temporary UI ownership. Modal or
  explicitly force-cursor UI releases mouse-look and requires two consecutive
  clear input updates before automatic recapture; manual release never
  auto-recaptures. `FirstPersonInput` deliberately inspects the force-cursor
  property itself because B42's aggregate helper also treats incidental UI
  hover as force-cursor state.
- `bridge/src/main/java/dev/pzfps/bridge/patches/CursorVisibilityPatch.java` —
  intercepts the installed `Mouse.setCursorVisible(boolean)` boundary. B42's
  Melee/Space aim-state transition may request a visible isometric cursor after
  `Mouse.update()` even though GLFW remains grabbed; only a `true` request made
  while FPS capture is active is rejected. F8 and cursor-owning UI change the
  capture state first, so their visibility requests remain intact.
- `bridge/src/main/java/dev/pzfps/bridge/patches/PointerGrabPatch.java` — covers
  the lower `org.lwjglx.input.Mouse.setGrabbed(boolean)` boundary. A late
  ungrab request is rejected only while FPS gameplay owns an active/focused
  window; deliberate F8/UI release and focus loss remain allowed.
- `bridge/src/main/java/dev/pzfps/bridge/patches/LocomotionPatch.java` — hooks
  the exact installed `IsoGameCharacter.isStrafing()` and protected
  `getDeferredMovement(Vector2, boolean)` descriptors. It activates B42's
  existing directional strafe presentation for local off-axis FPS input and
  redirects native normal-locomotion root motion without setting world
  position or bypassing PZ collision/action authority.
- `bridge/src/main/java/dev/pzfps/bridge/MovementDiagnostics.java` — compares
  requested FPS world direction with authoritative post-`IsoPlayer.update()`
  displacement over bounded 180-input-update windows. It reports directional
  alignment, opposed displacement and stationary updates separately; the last
  category explicitly includes collision/action constraints and is not called
  renderer or gameplay FPS. It now samples PZ's collision-owned `nextX/nextY`
  boundary against the previous update's deferred request; the old same-update
  `getX/getY` report incorrectly called every moving update stationary.
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
  key-binding system. Its context wrapper first invokes the installed B42
  manager's documented hidden test pass, then feeds the first actionable exact
  target to the normal menu builder. B42 expands that target to every object on
  its square before vanilla and mod providers construct their options. The
  visible result is positioned at the centre of the first-person viewport.
  Its post-UI callback assembles a symmetric four-tick reticle from the
  installed `media/white.png` at the viewport centre and hides it whenever the
  real cursor is visible. The earlier `crosshair00.png` is an animation fragment
  and appeared as a literal parenthesis when drawn alone. Tick gap, length and
  opacity now report `none`, ordinary world, identity-resolved interactable, or
  B42-native combat target states from the shared view ray; no proprietary
  texture is copied into the project and the UI state is not action validity.
- `WorldState.WorldItem` and `WorldCapture.worldItem(...)` — preserve a dropped
  item's real ID/type, static/world model identities, world texture, absolute
  placement, rotations, scale and extended-placement state. The mesh builder
  now refuses to treat its generated item sprite as map-tile geometry.
- `bridge/src/main/java/dev/pzfps/bridge/NativeWorldItemPass.java` and
  `FirstPersonModelCamera.java` — retain captured dropped items within the
  loaded horizontal neighborhood, rank them nearest-first, re-resolve each by
  square/index/item ID on PZ's game thread, and queue
  PZ's own `ItemModelRenderer` after the replacement world. This delegates
  state-specific static/world model choice, installed mesh/texture loading,
  attachments, tint and scale to the game instead of re-parsing FBX/X or
  drawing a sprite proxy. The camera adapter maps model space into the same
  perspective view and scopes `PerformanceSettings.fboRenderChunk=false` to
  each synchronous item draw so PZ's isometric `targetDepth` offset cannot
  corrupt perspective depth. The final callback tests a conservative model
  bound against the exact 3D frustum installed by that frame's perspective
  world draw; it no longer uses a flat yaw cone or same-floor assumption.
  Horizontal range remains separate from elevation so a steep upper-floor view
  can retain loaded ground-level items. Queued, completed and frustum-culled
  callbacks are logged separately. The prior engine value is restored in
  `finally`. This is source-built and unit-tested, not yet live-accepted.
- `bridge/src/main/java/dev/pzfps/bridge/NativeActorPass.java` and
  `FirstPersonCharacterCamera.java` — select nearby nonlocal characters,
  activate their native model slots through B42's own `setSceneCulled(false)`
  lifecycle seam, and snapshot them through B42's own
  `ModelSlotRenderData`, and queue them after the replacement world so they use
  its perspective depth. This preserves the client's already-evaluated
  animation matrices, clothing, attachments, held models, seated transforms,
  ambient state and model lifecycle rather than advancing a second animator.
  Native-queued entity IDs suppress only their corresponding diagnostic boxes;
  missing/unusable model slots retain the box fallback. The local character is
  deliberately excluded to prevent head/neck/shoulder clipping until a
  first-person body treatment is implemented. Isometric chunk `targetDepth` is
  disabled only around the synchronous native draw and restored in `finally`.
  The replacement draw skips `IsoWorld.sceneCullZombies/Animals`, so relying on
  a pre-existing active slot left every actor as a red box. The pass now owns
  only the bounded set it activated, releases that ownership when an actor
  leaves range, and caps nearest-first activation at 256. The producer retains
  actors across headings within its horizontal range; the
  render callback performs final culling against the same 3D camera frustum as
  the world, including pitch and elevation. Queued, completed and frustum-
  culled callbacks are logged separately and are not called game FPS. This path
  is source-built and unit-tested, not yet live-accepted.
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
  keep the old box fallback. As with actors/items, final selection now uses the
  frame's exact 3D perspective frustum instead of a flat yaw cone, while render
  preparation, completed and frustum-culled callback counts remain separate.
  This is source-built and unit-tested, not live-accepted; a locally occupied
  vehicle also needs a dedicated interior/near-camera visual check.
- `bridge/src/main/java/dev/pzfps/bridge/InteractionTarget.java` — retains the
  narrow door/window/container selector used by ordinary Interact, and now also
  returns bounded near-to-far context candidates for ordinary and mod-defined
  objects. Both use the short 3D ray from real eye height and pitch. Door/window
  volumes use their actual north/west edge, floor objects are thin planes, and
  dropped items use their exact absolute world position instead of occupying a
  whole tile. The selector has no same-floor shortcut: elevation must pass the
  same 3D reach test. Results are re-resolved by square, index, Java type,
  object type, sprite and item ID against the live object on PZ's game thread.
  B42's own `LosUtil.lineClear` rejects ordinary wall occlusion; a closed door
  or window admits only itself and cannot expose an object behind it.
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
  It asks PZ's own non-visible test mode whether each geometric candidate has
  options and opens only the nearest target PZ accepts. It releases mouse
  capture only after PZ reports a non-empty visible menu; every option and
  resulting action remains owned by the game's existing code.
- `bridge/src/main/java/dev/pzfps/bridge/WireProtocol.java`,
  `renderer/scripts/bridge_client.gd`, and `src/pzfps/runtime.py` — protocol
  version 5, including eye height/actor pose, stair-state flags, world-item
  identity/placement, collision/vision facts and the backward-compatible floor
  object flag; Godot is retained only as an
  offline/protocol consumer. Its updated parser completed a Godot 4.7.2
  headless editor parse with no reported script errors. A protocol-5 synthetic
  end-to-end run then decoded player/entity/chunk data and built the installed-
  registry fixture with 114 vertices, two surfaces and three primitives before
  reporting `PZFPS_SYNTHETIC_TEST_OK`.
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
javap -classpath "$PZ_JAR" zombie.iso.SpriteDetails.IsoFlagType
sed -n '1,150p' \
  '/Users/machi/Library/Application Support/Steam/steamapps/common/ProjectZomboid/Project Zomboid.app/Contents/Java/media/lua/client/Context/ISContextManager.lua'
sed -n '1,157p' \
  '/Users/machi/Library/Application Support/Steam/steamapps/common/ProjectZomboid/Project Zomboid.app/Contents/Java/media/lua/client/Context/ISMenuContextWorld.lua'
sed -n '122,280p' \
  '/Users/machi/Library/Application Support/Steam/steamapps/common/ProjectZomboid/Project Zomboid.app/Contents/Java/media/lua/client/ISUI/ISWorldObjectContextMenu.lua'
java -jar .local/toolchains/downloads/cfr-0.152.jar "$PZ_JAR" \
  --outputdir <temporary-directory> --jarfilter CombatManager
nm -gU "$PZ_BULLET" | rg -i 'Ballistics|AimReticle'
otool -arch arm64 -tvV "$PZ_BULLET"

jshell --execution local --class-path "$PZ_JAR"
# In JShell: LuaCompiler.loadis(Files.newBufferedReader(script),
#     script.toString(), J2SEPlatform.getInstance().newTable())

.local/toolchains/Godot-4.7.2.app/Contents/MacOS/Godot \
  --headless --editor --path renderer --quit \
  --log-file ../.local/godot-parse.log

python3 tools/synthetic_bridge.py --port 24873
PZFPS_BRIDGE_PORT=24873 PZFPS_TEST_EXIT_ON_CHUNK=1 \
  .local/toolchains/Godot-4.7.2.app/Contents/MacOS/Godot \
  --headless --path renderer \
  --log-file ../.local/godot-protocol-v5.log
```

Targeted installed-class inspection used `javap -c -p` on `IsoPlayer`,
`IsoGameCharacter`, `IsoWorld`, `UIManager`, `Mouse`,
`IsoWorldInventoryObject`, `InventoryItem`,
`ItemModelRenderer`, `WorldItemModelDrawer`, `IModelCamera`, `ModelCamera`,
`CharacterModelCamera`, `VehicleModelCamera`, `ModelCameraRenderData`,
`ModelSlotRenderData`, `ModelInstance`, `ModelManager`, `BaseVehicle`,
`TextureDraw.drawModel`, `SpriteRenderer.drawModel`, `Model`, `Shader`,
`CharacterInputComponent`, `IsoPlayer.doContext()`, `ContextualAction`,
`IsoGridSquare.HasStairsBelow()`,
`ISContextManager`, `ISMenuContextWorld`, `ISWorldObjectContextMenu`,
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

The latest locomotion pass also read the installed `IsoPlayer.updateInternal2`,
`updateMovementFromInput`, `IsoGameCharacter.isStrafing` and
`getDeferredMovement` implementations plus the installed player action-group
transitions and `AnimSets/player/strafe/*`. That evidence established that B42
already supplies no-aim left/right/backward/diagonal clips driven by
`DeltaX`/`DeltaY`, while normal locomotion rotates root motion with actor
facing. The inspected files were not modified.

The latest context pass also used installed B42 Lua read-only to verify that
`ISMenuContextWorld.createMenu(..., test=true)` is the controller-oriented
non-visible action-discovery path and that one selected object expands to every
object on its square. No installed script was modified.

The same installed-game Lua parser was rerun after replacing the incomplete
reticle image with four code-drawn ticks and returned `PZFPS_LUA_PARSE_OK`.

Most recent test results:

- Python/pytest: 45 passed, 0 failed (49 deprecation warnings).
- Java/Gradle: 83 passed, 0 failed across `ChunkLifecycleTest`,
  `CursorCaptureStateTest`, `DirectPatchInstallerTest`, `FirstPersonInputTest`,
  `FirstPersonCharacterCameraTest`, `FirstPersonModelCameraTest`,
  `InputStateTest`, `InteractionTargetTest`, `MovementDiagnosticsTest`,
  `NativeActorPassTest`, `NativeFirstPersonHandsPassTest`,
  `NativeVehiclePassTest`, `NativeWorldItemPassTest`,
  `PerspectiveBallisticsTest`, `PerspectiveInteractTest`,
  `PerspectiveViewRayTest`, `PerspectiveVisibilityTest`, `ReticleTrackerTest`,
  `RepresentationBacklogTest`,
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
- Current live-loaded staged bridge JAR SHA-256:
  `604785a5b2d043d045a15ccadf0a035886a6830eb617aa8f6ede6729fd0e75d4`.
- Last live screenshots:
  `.local/captures/pz-d9d4c69-live.png` and
  `.local/captures/pz-d9d4c69-reticle-live.png`. The latter visibly records the
  four-way reticle while capture is active.
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
4. Center-view interactions and combat must resolve a stable snapshot reference
   back to the exact live PZ object on the game thread. The ordinary Interact
   seam remains deliberately narrow for doors/windows. F7 now proposes bounded
   near-to-far hits for all captured world objects, including precise dropped
   items and thin floor planes, and calls B42's hidden context test until PZ
   accepts one; it does not encode its own appliance/furniture/mod action list.
   The 3D selector and identity re-resolution are unit-tested, while visible
   menu opening and an executed real option still require live acceptance.
   Direct position or state mutation is not an acceptable substitute.
   The last live F7/context request reached this seam but terminated on
   `IllegalAccessError` because inlined `IsoPlayer` advice referenced the
   package-private `PerspectiveInteract` type. `PerspectiveInteract` and the
   similarly exposed ballistics entry points are now public, with reflection
   regression tests; this repair is built but not live-tested.
5. Nonlocal native characters use B42's evaluated model render data. Skipping
   `IsoWorld.render()` initially skipped model-slot activation, then native light
   preparation, leaving the pass disabled. Bounded model activation plus native
   `updateLights()` now reaches ready-model callbacks. Native isometric alpha
   and copied shader opacity also needed correction; see the current repair
   above and its artifact-specific evidence.
   It still needs a disposable-session check
   of standing, walking, crawling, held equipment, occlusion and at least one
   seated actor; an exception disables only this pass and restores diagnostic
   boxes on the next frame. The local body remains intentionally absent.
6. The native neural overlay is blocked by the host/toolchain/model prerequisites
   above. No other renderer/model rewrite has been substituted for it.
7. Visible holes and unsupported backs/ceilings are now honestly exposed. The
   owner has accepted this as the starting point for structured hole filling.
   The current visible backlog is led by `roofs_04_37`, `roofs_04_35`,
   `roofs_04_36`, indoor-light sprites, `fixtures_counters_01_3` and the
   `location_business_office_generic_01_40-47` family. This is measured scene
   coverage, not proof that each identity needs the same representation rule.
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
12. An initial read-only `javap` extraction loop for the stair methods failed
    before inspection because zsh interpreted the method parentheses as a glob
    pattern. Exact `rg` line selection followed by `sed` inspected the installed
    methods successfully; no game or project file was changed by either command.
13. No standalone `luac` executable is installed, so the updated key-binding/UI
    script could not use `luac -p`. The installed game's own
    `LuaCompiler.loadis(...)` then parsed the complete script successfully from
    JShell using a project path and a fresh Kahlua table. Its `OnPostUIDraw`,
    `Mouse.isCursorVisible`, `getTexture` and `UIManager.DrawTexture` calls were
    also checked read-only against installed B42 Lua and Java signatures; live
    loading remains the required behavioral validation.
14. The first unsupported-backlog bounds test assumed a ten-square chunk and
    failed (`expected 20`, `actual 16`). Installed B42 defines
    `IsoChunkMap.CHUNK_SIZE_IN_SQUARES` as eight; the test now derives all
    expected bounds from that authoritative constant. The corrected 51-test
    build passed, and no failed artifact was staged or loaded.
15. Native world items, nonlocal actors and vehicles were still admitted by a
    producer-side two-dimensional yaw cone. That approximation ignored camera
    pitch and elevation and was unsuitable for upper-floor sightlines. Their
    producer pass now retains the horizontally nearby authoritative candidates;
    the native render callback uses the exact frustum established by the same
    perspective world frame. A pure camera test from floor 12 verifies that a
    steep downward view retains ground-level content and rejects a same-height
    object behind the camera. This is a 53-test source checkpoint, not a live
    visual acceptance result.
16. Unsupported appearance holes did not distinguish harmless missing decor
    from objects whose real PZ properties block movement. Protocol version 5
    now preserves `solid`, `solidtrans` and `blocksight`; completed visible
    frames count and rank collision-critical unsupported identities separately
    as `topCollisionHoles`. No collision was changed and no fake collider was
    drawn. The 55-test Java build, Godot 4.7.2 headless parser and protocol-5
    synthetic renderer integration completed; the ranking still requires a live
    collision/visual correlation check.
17. F7 initially considered only doors, windows and containers, which excluded
    much of normal PZ play (dropped items, appliances, switches, curtains,
    furniture and mod-defined actions). Installed B42 inspection found its
    controller-oriented hidden context-menu test and automatic same-square
    object expansion. The bridge now ray-orders broad geometric candidates and
    lets that game-owned test reject non-actionable hits. Floor and dropped-item
    bounds prevent the former full-tile proxy from stealing a horizontal ray.
    The 59-test build, game-native Lua parse and Godot parser pass succeeded;
    the renderer also consumes the new floor identity instead of relying on a
    sprite-name convention. This source checkpoint has not been staged or
    live-accepted.
18. The centre reticle was previously only a fixed image while rendering,
    interaction and ballistics reconstructed its ray independently. That could
    make the displayed centre disagree with steep-pitch selection. One
    normalized `PerspectiveViewRay` now supplies all four consumers, and a
    bounded tracker reports identity-resolved world/interactable hits plus
    B42-native combat-target counts to the UI. The 70-test build and installed
    Lua parser pass succeeded; the changing reticle state and target agreement
    still require live acceptance.
19. The first shared-ray compilation retained one old `LEVEL_HEIGHT` reference
    in `InteractionTarget` and failed before producing a JAR. It was replaced by
    the shared constant; the subsequent clean build passed all 70 tests. No
    failed artifact was staged or loaded.
20. The installed project-local key profile binds Space to `Melee` and leaves
    `PanCamera` unbound. Live logs showed no capture-state transition when Space
    exposed the cursor, proving this was not F8/UI release: B42 requested
    `Mouse.setCursorVisible(true)` after the bridge's mouse-update hook while
    GLFW remained grabbed. The source-built filter now rejects only that
    contradictory visible request during capture; it does not consume Space or
    replace PZ's shove/attack path. Live shove and cursor behavior remain to be
    accepted.
21. The owner rejected the camera/body split loaded by the last process:
    holding A then D still required the third-person locomotion body to turn
    through 180 degrees. Installed B42 code showed why: non-strafing movement
    sets facing from `playerMoveDir`, then native root motion follows that
    facing. The replacement source keeps camera yaw as body/action facing, uses
    B42's existing no-aim directional strafe blend for A/D/S, and preserves the
    native root-motion length while changing its direction to the deferred FPS
    request. Tests cover opposite/diagonal mapping, one-frame request handoff,
    exact installed descriptors and magnitude preservation. This is not yet a
    live gameplay acceptance result.
22. A first ceiling/material edit was only partially applied and failed Java
    compilation because its topology helpers and extended batch signatures were
    absent. Those methods were completed before any checkpoint. Four fixture
    assertions then failed because older test rooms had `haveRoof=true`; the
    fixtures were corrected to isolate their intended behavior, while dedicated
    tests now cover upper-floor ceilings, roofed top-storey ceilings and stair
    openings. The final clean build passes all 79 tests; no failed artifact was
    staged or loaded.
23. In the live `0555930` process, the bridge's logical cursor state remained
    `GAMEPLAY_CAPTURED` while the owner observed the native pointer escaping the
    PZ window. The existing code cached only its last requested state, so it
    could not detect a later Cocoa/PZ/GLFW mode change. The newest source
    intercepts the exact installed `org.lwjglx.input.Mouse.setGrabbed(boolean)`
    descriptor and independently polls `glfwGetInputMode(..., GLFW_CURSOR)`.
    Unit tests cover active-capture ungrab filtering, focus loss and actual-mode
    mismatch detection. The loaded build then measured native mode `212994`
    while capture was expected and logged a successful repair. Owner acceptance
    of physical edge confinement remains outstanding.
24. The live centre reference looked like `(` because `crosshair00.png` was
    drawn as though it were a complete static reticle. The source script now
    uses four independently positioned rectangles from installed `white.png`;
    the installed Lua compiler parses the result. Live capture
    `.local/captures/pz-d9d4c69-reticle-live.png` confirms four visible symmetric
    ticks at the viewport centre. Target-state transitions and gameplay
    agreement are not yet accepted.

## Next smallest experiment

No PZ process is running. The next experiment is narrowly V-12: establish a
reliable capturable isolated window, load a disposable position containing the
91-roof-instance audited house (or an equivalent deterministic roof fixture),
and record slow exterior camera sweeps from both sides. Compare exact roof
identity/triangle counters against the scene audit and reject any missing side,
bridged coplanar island, inverted patch, roof-wall card or camera-dependent
disappearance. A load/callback log without a visible frame is not evidence and
does not justify another general-purpose reopen.

Only after that roof checkpoint is accepted or rejected should a separate live
batch target V-03/V-04 (shell cracks and interior leakage). Keep V-05/V-09
windows and doors, V-07 closed furniture, V-08 wall fixtures, V-11 fences,
V-14 sky, V-15 lighting and V-16 local body explicitly open; they were not
fixed by the roof compiler. The older gameplay checks below remain regression
backlog, not claims about this checkpoint.

1. Stage one batched build and live-test simultaneous W+A/W+D, A/D,
   Shift+W/A/D and backward movement while keeping mouse view fixed. Confirm
   that ordinary locomotion retains camera-facing body/action yaw and reverses
   lateral translation without a 180-degree turn delay. Confirm PZ still owns
   collision, bump/vault behavior, stamina, run/sprint authorization and actual
   position. Use the corrected previous-request versus `nextX/nextY` diagnostic;
   its direction alignment is evidence about movement, not gameplay FPS.
   Press Space while captured and confirm the normal shove executes without a
   visible/free cursor. Move repeatedly to all window edges and confirm the
   system pointer cannot escape. Then verify F8, inventory and context menus
   still expose the cursor normally and that focus loss does not trap it.
2. Aim at each of two neighboring doors/windows in turn and verify that the
   normal `Interact` key executes only the identity-matched reticle object's
   PZ-generated contextual action. Then aim at a locked/non-actionable target
   and verify that a different nearby isometric action does not fire. PZ's own
   `doContext()` still owns action construction, validation and execution.
3. Press F7 in turn on a centre-view container, appliance/light switch or
   curtain, dropped item, and floor while looking down. Verify the hidden test
   skips a nearer decorative object with no options, B42's own non-empty menu
   opens only for an actionable hit, one normal option executes, and capture
   returns only after the menu clears. Verify that the reticle changes for an
   ordinary surface, an identity-resolved interactable and a B42-accepted combat
   target, is visibly four-way rather than `(`, remains at optical centre while
   looking steeply up/down, hides while the cursor owns the menu, and returns
   after recapture. The source path is built but not live-tested.
4. Inspect one real dropped item from several angles and verify the new native
   item pass selects PZ's installed model/texture, placement and scale, shares
   the perspective depth buffer, and leaves unresolved identities as honest
   holes. The offline model index remains independent reproducibility evidence;
   the live path intentionally uses PZ's state-aware renderer rather than
   duplicating its asset-selection rules.
   Repeat from an upper-floor downward view and confirm the item is neither
   rejected by a same-floor rule nor visible outside the true camera frustum;
   preserve the completed/frustum-culled callback report.
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
8. Revisit the downward stairwell and verify that the former solid floor plane
   is now an actual opening, that upward stairs retain their supporting floor,
   and that the indexed stair geometry remains traversable and depth-occluded.
9. Inspect both sides of several ordinary structural walls. Confirm missing
   backs now show mirrored source appearance without flicker or duplicate
   same-side fragments, while doors/windows and authored meshes remain
   unchanged. Inspect stacked and top-storey rooms for completed ceilings and
   verify that stairwell/roof-access openings remain traversable and visually
   open. Record live `mirroredStructuralFaces` and
   `completedInteriorCeilings`; counts alone are not acceptance.
10. Continue the architectural hole pass from the measured real scene: classify
   the roof, switch, counter and office-furniture families and inspect the same
   rule across multiple rooms. Confirm visually that a ranked collision hole
   corresponds to the invisible blocker before replacing its presentation. Do
   not infer geometry solely from the collision flag.

Irregular assets that remain after deterministic family compilation are then
the candidates for identity-stable constrained completion and multi-view
inspection. Neural generation is not the default for known planes, openings or
usable native meshes.
