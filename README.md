# PZFPS / PZ Neural View

Keep the actual Project Zomboid client authoritative while replacing its visual representation with a persistent, coherent first-person presentation. This is not a second simulation, a desktop screenshot overlay, or a commitment to a particular rendering engine.

## Implemented source

- `bridge/`: the local session's Java client hooks, live state/pose capture, input bridge, geometry registry, and in-process rendering implementation, published in `009c258` and preserved here.
- `renderer/`: the existing experimental external frontend, also preserved rather than discarded or declared the winning backend.
- `src/pzfps/`: the existing local tooling and source asset indexers. Historical overlay tooling remains optional; it is not a prerequisite for the renderer or compiler.
- `canonical/`: the directly implemented geometry/material completion pipeline, immutable asset store, normalized live scene contract, and evaluated-pose skinning reference. See [executable instructions](canonical/README.md).

The canonical compiler uses known geometry, calibrated source images/sprite manifests and optional depth, rejects unsupported backface/occluded evidence, and writes one persistent GLB with shared surface appearance. Optional local neural inpainting completes novel views only into still-unknown texels; source observations are locked. No Godot dependency or screen capture is needed by the compiler.

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
the replacement perspective depth. That path is built and unit-tested but has
not yet been accepted in a live session.

Nonlocal characters have a parallel native path: the bridge snapshots B42's
active `ModelSlot` into the same `ModelSlotRenderData` used by the game, then
draws that evaluated pose, clothing, attachments and held-item state through a
perspective `ModelCamera` after the replacement world's depth pass. It does not
advance a second animation clock. The local character is intentionally omitted
until a first-person body/arms treatment can avoid head, neck and shoulder
clipping. Unavailable native models retain the explicit diagnostic-box fallback.
This path is source-built and unit-tested but not yet live-accepted.

Live FPS controls currently reserve two editable entries under the `[PZFPS]`
key-binding section: F8 releases/recaptures mouse look, and F7 asks PZ to open
its own world context menu for the identity-checked three-dimensional reticle
target. The cursor is released only when PZ actually creates a non-empty menu.
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
