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
declared scale and `attachment world` transform. This is input to a future live
model consumer; generating the index does not claim those meshes are rendered.

Live FPS controls currently reserve two editable entries under the `[PZFPS]`
key-binding section: F8 releases/recaptures mouse look, and F7 asks PZ to open
its own world context menu for the identity-checked centre-view target. The
cursor is released only when PZ actually creates a non-empty menu.

## Active direction

The objective is playable PZ with the real actors, evaluated animations, actions, UI and multiplayer state. Missing geometry/material information belongs in persistent scene assets, not independent image generations every frame. In-process hooks and an external renderer are integration choices, not different gameplay authorities. Neither backend is assumed fastest. Preserve useful implementations and change backend only for a demonstrated requirement.

The old `docs/FIRST_EXPERIMENT.md` overlay-first gate and original brief-only scope are superseded by `AGENTS.md` and this implementation. A missing Workshop mod, overlay model, operating-system update or completed screenshot comparison must not block work on the actual state-to-scene-to-play path.
