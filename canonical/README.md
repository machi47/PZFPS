# Canonical scene compilation

## Run

From the repository root, keep the environment and generated artifacts local:

```sh
python3 -m venv .local/canonical-venv
.local/canonical-venv/bin/python -m pip install './canonical[test]'
.local/canonical-venv/bin/python -m pytest canonical/tests -q
.local/canonical-venv/bin/pzcanonical fixture --output .local/canonical-proof
.local/canonical-venv/bin/pzcanonical compile .local/asset-job.json --store .local/canonical
```

The fixture is original procedural test data, not a PZ asset and not a gameplay demonstration. It creates two source views, a persistent compiled asset, its evidence masks, and a complete offline orbit. Output includes `asset.glb`, `basecolor.png`, `observed.png`, `generated.png`, `disagreement.png`, `surface.npz`, and `report.json`. A repeated input reuses the immutable compiled asset.

## Real source input

A job JSON contains `schema_version: 1`, a stable `prototype`, an exact `source_revision`, authoritative `bounds: [[minX,minY,minZ],[maxX,maxY,maxZ]]`, `geometry`, `views`, and optional material/completion settings. Coordinates are explicitly right-handed Y-up canonical object coordinates. The source adapter must convert actual PZ units/orientation; no heuristic pretends that all source sprites share one camera anchor.

`geometry` accepts an existing `file` (GLB/OBJ etc. through trimesh), explicit `vertices`/`faces`, a list of explicit `primitives` with `kind: box`, `bounds` and optional affine `transform`, or `method: visual_hull` with `resolution`. The visual hull intersects known silhouettes inside the given hard bounds; it does not recover unobserved concavities. Supplied complete meshes are preserved rather than replaced by generated proxies. Planar-chart UV generation is provided; highly tessellated curved assets may need a larger atlas or a separate production unwrap.

Each view contains `name`, exactly one `image` or `sprite_manifest`, and a 3x4 affine `matrix`. Matrix output is pixel-center X, pixel-center Y, and depth (smaller is nearer). Optional `depth` refers to an NPY array in those same depth units. `sprite_manifest` accepts the existing local compiler's `schema_version=1`, `page_path`, `page_sha256`, and crop/original-size `region` format. The image is isolated in its original canvas before sampling, so adjacent packed sprites cannot leak into the asset. A source sprite's original canvas size does not by itself determine its projection anchor.

Paths are relative to the job or sprite manifest, except explicitly absolute source paths. Input pixels/depth/mesh and algorithm/material settings determine the artifact key, not their filesystem locations or the current player camera. The `from-pz` adapter directly consumes the now-published schema-1 `tile-geometry.json` and existing sprite extraction manifests; see below. A custom mesh/job remains supported.

`atlas_size`, `padding`, `depth_tolerance`, `roughness`, `metallic`, and one integer `material_groups` entry per face are optional. Use groups to prevent unrelated materials from borrowing each other's completion colors. Source RGB may contain baked lighting: this pipeline does **not** claim to recover physical reflectance from painted sprites. Scalar PBR settings are explicit appearance choices, not inferred facts.

## Implemented algorithm

1. Preserve canonical geometry or intersect calibrated silhouettes within hard bounds.
2. Construct a deterministic shared surface atlas with independent padded charts.
3. Reproject each surface sample into every source. Reject backfaces, out-of-image/crop samples, transparent pixels, and depth-inconsistent or occluded samples.
4. Fuse accepted evidence in linear color with angle/confidence weights. Store source disagreement rather than concealing it as a quality guarantee.
5. Solve unsupported color on a surface-neighbor graph with fixed observed values and optional material boundaries. This harmonic baseline fills low-frequency appearance; it does not invent convincing high-frequency hidden details.
6. Optionally render missing views, use a local inpainting model, restore all protected image pixels, and backproject only still-unknown surfaces into the same atlas. Geometry, silhouette, and already accepted observations remain fixed.
7. Export a GLB and evidence masks atomically; reuse the accepted artifact for every viewpoint. Per-instance SQLite assignments survive reload and require compare-and-swap acceptance to adopt a replacement.

The graph solve spans UV seams, but this is not a general proof of seam-free texturing or a guarantee that a learned image has correct object semantics. Thin parts, highly curved assets, transparent surfaces, and source-camera calibration need representative content validation.

## Optional local model

Install `./canonical[neural]` in the same project-local environment and provide a complete local diffusers inpainting model directory with `--model`. Nothing downloads model weights implicitly. Set `completion.mode` to `diffusers`, supply `prompt`, `seed`, and `cameras` (each with `matrix`, `width`, `height`). The provider selects actual MPS/CUDA/CPU availability and serializes its pipeline; it is not assumed reentrant. `compile_many` is bounded/async, and assets for an identical key are built once across processes.

Neural completion is an implemented optional path, **not model-tested here**. CPU tests inject a deliberately mask-violating provider to verify that observed surfaces remain protected. This is our constrained shared-atlas implementation, not a claim to reproduce TexFusion, train TRELLIS, or run SHARP.

## Live client boundary

`runtime.SceneState.apply` accepts normalized packets with `schema_version=1`, `type` (`snapshot`/`delta`), `session`, `sequence`, `upsert`, and `remove`. Deltas also specify `base_sequence`. Missing/out-of-order deltas request a snapshot without altering the current scene. Each node has a stable lifecycle `id`, source `prototype`, `kind`, and row-major affine `transform`; actor `pose` contains the client's evaluated model-space matrices. New session state requires a full snapshot. A frame's age keeps increasing when packets stop.

`skin_vertices` implements `world * sum(weight * evaluatedBoneModel * inverseBind * vertex)`. This is the CPU reference for an eventual GPU frontend. Matrix ordering is explicit through `matrices_from_wire`, not inferred from field names. Original evaluated character/clothing/attachment transforms must come from the actual client adapter. Room structures and moving objects stay separate nodes. No engine, networking authority, combat rule, input mapping, or game lifecycle is replaced by this module.

## Research and dependency references

- TexFusion: https://research.nvidia.com/labs/toronto-ai/texfusion/ — shared multiview appearance domain; our bake is not its trained diffusion algorithm.
- Diffusers inpainting: https://huggingface.co/docs/diffusers/using-diffusers/inpaint
- Diffusers MPS: https://huggingface.co/docs/diffusers/optimization/mps
- trimesh: https://trimesh.org/ — existing mesh import/export and PBR GLB support.

The first GPU/gameplay integration has not run in this repository. The output contract is deliberately engine-independent; no performance conclusion about Godot or a native Metal frontend follows from these CPU tests.

## Direct integration with the published PZ asset index

The `from-pz` command reads the actual schema emitted by `src/pzfps/assets.py` at commit `009c258`, including boxes, tapered cylinders, planar polygons, translation, and XYZ source rotations. It fits the image anchor by FFT silhouette correlation, keeping the source geometry and explicit projection scales fixed. It rejects low-overlap mismatched source/geometry pairs rather than stretching geometry to hide the error. Concave polygons use constrained triangulation; their visual thickness must be supplied explicitly because source planar metadata does not define a hidden surface.

```sh
.local/canonical-venv/bin/pzcanonical from-pz \
  --registry .local/assets/pz-42.20/tile-geometry.json \
  --sprite-manifest .local/assets/pz-42.20/first-asset/furniture_bedding_01_0.json \
  --horizontal 64 --vertical 192 \
  --output .local/canonical-bed
```

Those paths/scales match the supplied local-session convention; they are inputs, not a claim that the example sprite has already passed calibration on the owner's installation. The output contains a runnable source job and its compiled GLB, with calibration overlap recorded. For a planar primitive, supply `--polygon-thickness` as an explicit visual completion choice. Multiple tiles belonging to one object still require verified grouping; a single tile fragment must not be called a complete multi-tile object.

The registry transform and image-fit tests use authored data in the real schema. They do not publish or reconstruct the owner's proprietary source art in Git. Version 0.1.1 also uses premultiplied-alpha linear-color sampling to prevent transparent sprite borders from darkening accepted surface colors, and closes each SQLite transaction's connection deterministically.
