# PZFPS / PZ Neural View

Keep the actual Project Zomboid client authoritative while replacing its visual representation with a persistent, coherent first-person presentation. This is not a second simulation, a desktop screenshot overlay, or a commitment to a particular rendering engine.

## Implemented source

- `bridge/`: the local session's Java client hooks, live state/pose capture, input bridge, geometry registry, and in-process rendering implementation, published in `009c258` and preserved here.
- `renderer/`: the existing experimental external frontend, also preserved rather than discarded or declared the winning backend.
- `src/pzfps/`: the existing local tooling and source asset indexers. Historical overlay tooling remains optional; it is not a prerequisite for the renderer or compiler.
- `canonical/`: the directly implemented geometry/material completion pipeline, immutable asset store, normalized live scene contract, and evaluated-pose skinning reference. See [executable instructions](canonical/README.md).

The canonical compiler uses known geometry, calibrated source images/sprite manifests and optional depth, rejects unsupported backface/occluded evidence, and writes one persistent GLB with shared surface appearance. Optional local neural inpainting completes novel views only into still-unknown texels; source observations are locked. No Godot dependency or screen capture is needed by the compiler.

The live renderer now reports scene coverage from completed visible frames:
source-textured floors, unavoidable flat floor fallbacks, authoritative stair
openings, indexed object geometry, structural fallbacks, native world items,
unsupported objects, collision-critical unsupported objects and any chunk
truncated by the safety vertex cap. A square
with B42's `HasStairsBelow` topology no longer receives a generic solid floor
plane across the stairwell. These are counts of representation paths—not
inferred visual quality or game FPS—and make the remaining holes a measurable
asset backlog instead of an anecdotal one.

Every unsupported object also retains its sprite identity per chunk. At each
completed-frame report, the renderer aggregates only the chunks inside the
current distance/frustum volume and logs the eight most repeated unsupported
sprites. Coverage-only chunks with no drawable triangles remain in this
backlog; they are not incorrectly discarded as off-screen empty geometry.
The report separately ranks `topCollisionHoles` from B42's authoritative
`solid`/`solidtrans` object flags. This identifies invisible blockers without
drawing an invented box or changing the game's collision.

The installed corpus is also joined to the owner's V-01..V-17 visual issue
ledger. `bin/pzfps assets audit-coverage` writes an exact per-identity
`visual_issue_scope_ids` field and total/map-referenced counts under
`summary.visual_issue_identity_scopes`; `assets audit-scene` carries those IDs
onto the actual object instances in a retained runtime snapshot. Scope
membership is a work queue, not an assertion that every identity visibly
reproduces the example defect or has passed live acceptance.

Roof recovery is likewise corpus-wide rather than house-specific. The current
installed audit has 4,331 unconditional source-backed roof identities and
80 additional contextual identities. Contextual faces are never emitted from a
depth assignment alone: the registry records exact neighbor relations parsed
from PZ's installed `seams.txt`, and the renderer requires the named canonical
roof neighbor in an immutable snapshot neighborhood. Available east/south
neighboring chunks participate without duplicating their geometry; an unloaded
or absent neighbor fails closed.
This path is tested and staged but remains pending its first moving-view live
acceptance; it must not be read as proof that the remaining roofs look correct.

`pzcanonical from-pz` directly reads the actual B42 geometry registry and sprite extraction manifest formats, including primitive rotations, tapered cylinders and concave polygons. It fits the source image anchor by silhouette overlap and refuses bad matches rather than warping known geometry. This connects the new compiler to the published asset indexers without a manual schema rewrite.

**Validation:** the canonical CPU suite has 25 passing tests and a complete offline orbit. See [the measured result](evidence/canonical-validation.json). Its original calibration fixture is not a PZ asset. Learned inference has not been executed with model weights in the Chat environment. The existing game's runtime diagnostics are in `docs/STATUS.md`; no CPU fixture establishes accepted gameplay, multiplayer correctness, photorealistic quality or Mac GPU performance.

## Run the new compiler

```sh
python3 -m venv .local/canonical-venv
.local/canonical-venv/bin/python -m pip install './canonical[test]'
.local/canonical-venv/bin/python -m pytest canonical/tests -q
.local/canonical-venv/bin/pzcanonical fixture --output .local/canonical-proof
```

Source, build tools, weights, caches, extracted assets and captures stay inside this workspace; bulk belongs under `.local/`. Game assets, weights and proprietary/decompiled sources are not included in Git. Existing saves and unrelated mods remain untouched.

Index the installed game's static/world-item models without copying them into
the repository:

```sh
bin/pzfps assets index-models
```

The generated `.local/assets/pz-<version>/model-index.json` resolves script
model identities to exact installed mesh/texture paths and preserves the
declared scale and `attachment world` transform. The bridge now also contains a
live consumer for dropped items: it identity-checks each captured item against
the authoritative square, then asks PZ's own `ItemModelRenderer` to select and
draw the installed mesh, texture, attachments, tint and item-state variants in
the replacement perspective depth. Candidate items are ranked nearest-first
without a same-floor restriction; the render callback then uses the exact 3D
frustum from the replacement-world camera. Actors and vehicles use that same
final frustum rather than the previous flat yaw cone, so a steep view from an
upper floor can retain known ground-level content without submitting every
loaded floor. Those paths are built and unit-tested but have not yet been
accepted in a live session.

Nonlocal characters have a parallel native path: the bridge snapshots B42's
active `ModelSlot` into the same `ModelSlotRenderData` used by the game, then
draws that evaluated pose, clothing, attachments and held-item state through a
perspective `ModelCamera` after the replacement world's depth pass. It does not
advance a second animation clock. The local character is intentionally omitted
so its head, neck and torso cannot clip through the camera. A separate local
first-person pass selects only the evaluated primary/secondary hand-model roots
and their descendants, preserving PZ's attachment transforms and action timing
without drawing the body. Unavailable native models retain the explicit
diagnostic-box fallback. Both paths are source-built and unit-tested but not yet
live-accepted; the held models may still need a first-person pose adjustment
after the live placement and occlusion check.

Native actor preparation now preserves PZ's skin/outfit texture creation and
lighting setup before taking a pose snapshot. Unseated actor placement follows
PZ's bone-to-world coordinates, without the isometric camera's downward origin
offset. A standing zombie's full textured appearance is live-confirmed; crawler,
climbing and combat acceptance remain separate.

Source-textured world surfaces now have a two-sided fallback without duplicate
back meshes. Bounded edge sampling repairs raster-trimmed joins on solid floors
and ordinary wall panels; it does not globally fill prop/window transparency.
Authored tile geometry uses the installed rotation order and vertical-unit
conversion. This is still source reprojection, not complete reconstructed assets.

Vehicles now use that same evaluated-model boundary instead of remaining
uniform diagnostic boxes. The bridge validates each live vehicle `ModelSlot`,
retains B42's body/part/wheel transforms and damage/light textures, and renders
the snapshot through the perspective depth pass. A missing or inactive slot
keeps the explicit box fallback. This path is source-built and unit-tested; it
still needs a live parked/moving/damaged-vehicle check, and the locally occupied
vehicle needs a specific interior/near-camera acceptance pass.

Live FPS controls currently reserve two editable entries under the `[PZFPS]`
key-binding section: F8 releases/recaptures mouse look, and F7 asks PZ to open
its own world context menu for identity-checked three-dimensional reticle hits.
F7 walks near-to-far object hits and uses B42's hidden context-menu test pass,
so ordinary appliances, switches, curtains, furniture, floors, dropped items
and mod-defined objects do not require a hard-coded action list. The cursor is
released only when PZ itself reports and then creates a non-empty menu.
The same UI pass assembles a symmetric four-tick reticle from B42's installed
white texture and hides it whenever the real cursor is visible. Cursor intent
is published by game input and applied at the installed display thread's
`Display.updateMouseCursor()` boundary, where native GLFW mode is read back.
This replaces vanilla's competing hidden-cursor mode and keeps GLFW calls on
the event thread. Toggling inventory releases capture on that same input poll;
the visible PZ inventory/loot pages then retain cursor ownership for drag/drop.
Immediately before that handoff, B42's own square sightline traversal rejects a
target hidden by a wall; a closed door or window admits only itself, not a
container behind it. PZ still owns the menu options, reach/action checks and
resulting action. The ordinary Interact key now uses the same identity-checked
reticle seam for doors and windows: B42 still constructs and validates its own
contextual actions, but its private chooser prefers the action whose live
`IsoObject` is the reticle target. If no validated action belongs to that
target, a different nearby isometric action is not executed.
PZ context menus already request cursor ownership; the bridge also marks an
inventory/loot pair opened with PZ's normal `Toggle Inventory` action as cursor-
owning until it is hidden. Merely hovering a collapsed UI strip no longer
releases mouse look.

The installed B42 combat path bypasses its public aim getter and calls a private
isometric aim calculation directly. The bridge now hooks both routes and
reasserts FPS yaw/pitch after `setAngleFromAim()`, while leaving PZ's normal
attack authorization and `AttemptAttack` execution intact. This fixes actor
facing at the Java action boundary. A second exact hook now gives B42's native
Bullet target query the FPS muzzle direction and a camera quaternion aligned to
the center-view ray. PZ still owns collision, LOS, body-part selection, hit
chance, damage and network actions. The coordinate/quaternion path is built and
unit-tested against the installed descriptors and native ray convention, but it
has not yet passed a live aimed-firearm sequence, so ranged combat remains
explicitly unaccepted.

## Active direction

The objective is playable PZ with the real actors, evaluated animations, actions, UI and multiplayer state. Missing geometry/material information belongs in persistent scene assets, not independent image generations every frame. In-process hooks and an external renderer are integration choices, not different gameplay authorities. Neither backend is assumed fastest. Preserve useful implementations and change backend only for a demonstrated requirement.

The old `docs/FIRST_EXPERIMENT.md` overlay-first gate and original brief-only scope are superseded by `AGENTS.md` and this implementation. A missing Workshop mod, overlay model, operating-system update or completed screenshot comparison must not block work on the actual state-to-scene-to-play path.
