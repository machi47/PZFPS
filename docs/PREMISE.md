# Premise and technical direction

## 1. The project in one paragraph

PZ Neural View uses Project Zomboid as the authoritative simulation and replaces
its world presentation without replacing the game. Known game state constrains
room layout, object placement, identity, damage and interactions. Procedural
geometry, reusable assets and generative completion fill missing visual detail;
generated results persist rather than changing whenever the camera moves. A
single live renderer presents that world, followed by PZ's normal text and UI,
with optional neural appearance enhancement when it fits the machine's latency
and memory budget.

## 2. Connection to the room-video reconstruction project

The transferable idea is an evidence-constrained scene representation: building → rooms → structural elements → objects → articulated parts, with materials and behavior kept separate. A scene should not become an inseparable visual blob.

The difference is that PZ provides a source of semantic truth. We should query known floor, room and object relationships instead of reconstructing them from rendered pixels whenever the installed build exposes those facts. Its published grid-square API documents objects, rooms and buildings, although exact callable surfaces must be tested against the installed build. [S8]

For a cupboard, the source game can constrain footprint, placement and gameplay identity. The visual system can choose plausible thickness, back panel, material wear and small details. Generate those once, validate the footprint and retain the result. Opening the cupboard must not select a different cupboard; walking behind it must not restart an independent image generation.

The low-detail source is an artistic opportunity, not proof that accurate reconstruction is trivial. Fewer visual constraints also mean more ambiguity. Use state and procedural rules to reduce that ambiguity before involving a learned generator.

## 3. Evidence-driven slices, not one giant prerequisite chain

### Completed selection work: existing view and neural overlay

The released Workshop first-person candidate was inspected and rejected because
it is a Lua/UI raycaster with documented rendering and performance limits. It
cannot serve as the base view for the intended system. The pinned native Mac
overlay also remains blocked on this host by its operating-system, Swift and
model prerequisites. These results are recorded evidence, not permission to
describe an isometric filter as first person.

The concrete overlay exists as an experimental source project using MLX/Metal. Its architecture already includes screen capture, bounded frame handling, temporal processing and composition. Reuse it rather than rebuilding those pieces. [S1, S4]

If no compatible first-person mod runs on this installation, an isometric overlay test can still assess appearance, but must be labeled isometric. It is not a completed first-person milestone.

### Active slice: state-constrained presentation

Expose a small live neighborhood and render it with known geometry and
persistent assets. A renderer need not wait for a complete export of the map.
Prove one room, one working door, one moving entity, normal UI composition and
one authoritative interaction first.

Persistent generated materials and assets can support the renderer without a
heavy model on every displayed frame. The generative system should compile
identity-stable appearance; it must not regenerate the room every frame.

## 4. One visible renderer and one authoritative client

The current integration replaces `IsoWorld.render()` inside PZ's existing frame
and backbuffer. PZ still runs its simulation, evaluates animation and draws its
normal text and UI afterward. This avoids concurrently presenting the original
isometric world and a second visible renderer.

ZombieBuddy documents Java-agent hooks and a macOS installation route. That makes it a bridge candidate, not proof that every desired renderer and input operation is exposed. Prefer ordinary mod APIs where sufficient; add Java hooks only for specific measured gaps. [S7]

The Godot frontend remains useful as an offline/protocol diagnostic, not the
live presentation. A later native renderer or shared-memory data plane is a
measured optimization option; it must retain the one-window composition order
and cannot create a second gameplay authority.

An external observer that displays a moving PZ character is a spectator. It becomes playable only when camera control, movement, aiming, interactions, combat, inventory/UI and recovery paths work correctly together. Keep these acceptance labels distinct.

## 5. Intended layers

### Game adapter

Read only the verified state necessary for the current slice. Discover capabilities at startup and include game build and adapter version in the handshake. Do not imply that unloaded chunks or every third-party mod's custom state are available.

When using Java hooks, copy data on a verified game thread into immutable messages, then perform encoding and I/O elsewhere. Start with a simple local transport and a small schema rather than a high-performance protocol framework. Bind a network transport to loopback with an ephemeral per-session token; impose message-size and queue limits.

### Authoritative scene state

Maintain snapshots plus ordered deltas: chunk arrivals/removals, entity identities, transforms, relevant object state and game time. A dropped visual frame is harmless; a dropped door-removal delta is not. Include session ID, sequence/base sequence and a resnapshot path. Unknown data must remain explicitly unknown.

Do not use an object's transient position in a Java/Lua collection as persistent identity. Start with stable source identifiers when available; otherwise define adapter-owned identities with lifecycle tracking and document their limits. Persistent appearance assignments across save/load need a separately validated identity scheme.

### Appearance registry and generation

Use a content-addressed registry keyed by source asset/type, game/mod version, footprint, style specification, generator version and selected variant. Reuse prototypes; retain per-instance wear/material seeds and true state changes separately. Do not regenerate every occurrence of a repeated chair or wall.

Prefer parameterized room shells, doors and furniture from existing permitted assets for the first scene. Add generated materials or geometry only where they improve the test. The first room does not require a universal asset generator.

Validate generated candidates against dimensions, clearance, door swing, silhouette and required moving parts. Keep a simple correct substitute until a candidate is ready. Preserve original inputs and generator provenance. A fixed random seed by itself is not a guarantee of multiview or temporal consistency.

### Conventional real-time renderer

The active implementation queues renderer-owned GL work at PZ's verified world
draw boundary and restores state before PZ's UI pass. Geometry/asset compilation
occurs from immutable snapshots on a bounded worker queue; live PZ objects never
move to that worker. Godot 4 is retained only as an offline/protocol reference
for comparing mesh output. [S9]

Keep geometry authoritative around windows, doors, stairs and interacting entities. Use generated appearance as cached materials/meshes, not as collision authority. Keep simulation physics in PZ. Stream only the validated neighborhood and respect gameplay visibility rather than displaying hidden information accidentally.

### Optional neural finish

Render a correct base image first. Preserve depth, motion, normals and object masks when available. Use them for conservative temporal reprojection, disocclusion rejection, UI protection and fallback decisions.

A model can only consume conditioning channels its implementation actually supports. Adding object IDs to a sidecar does not cause a pretrained RGB model to understand them. Model-specific architectural changes or training are later research, not implicit features of the first integration.

Use lower-resolution inference and bounded latest-frame queues. Never accumulate a visually impressive but increasingly stale video stream. When an enhancement is late, invalid or unreliable, show the correct current base presentation. Combining an old enhanced frame with current motion requires validated reprojection; do not simply repeat it and call the latency fixed.

## 6. Where Gaussian splats fit

Gaussian splatting is a possible appearance representation, not the project's foundation. It does not by itself solve object semantics, hidden-side completion, relighting, articulation or valid game interactions.

Apple's SHARP is a relevant reference for predicting a splat scene from an image. Its repository supports MPS for prediction but documents CUDA-only support for its supplied video renderer; it is not a turnkey Mac runtime for this project. [S10]

Prefer mesh geometry for room boundaries, doors, zombies and objects whose silhouettes affect gameplay. Evaluate splats later for appropriate static visual content, with memory, depth, occlusion and compositing measured in the chosen renderer. Do not flatten an entire furnished room into a static representation that cannot express a moving chair or opening door.

## 7. What “DLSS-like” means here

NVIDIA's DLSS 5 description includes neural lighting/material synthesis grounded in color and motion inputs. That is closer to this idea than ordinary resolution enlargement. It is still distinct from generating a persistent, editable 3D asset collection. [S6]

The proposed project therefore separates persistent appearance creation from real-time neural presentation. A heavy generator may prepare/cache a material or object outside the frame deadline. Runtime geometry and lightweight appearance processing can then run independently. This is a design direction, not a claim that unmodified DLSS, FSR or the Mac overlay automatically performs all those stages.

## 8. Art direction and correctness

Aim for ordinary domestic spaces made physically believable: painted surfaces, imperfect roughness, dampness, convincing window light, restrained exposure, dark corners and coherent scale. Atmospheric instability may be an artistic choice; object identity and enemy visibility may not be accidental instability.

A window must not become a doorway. A zombie must not become a wall stain. A readable item count must not become generated texture. Movement and attack feedback matter more than a single dramatic frame.

The first visual slice is one occupied house interior and its immediate exterior. Do not make an entire converted map, multiplayer, all vehicles, a new animation library or a universal content tool prerequisite to assessing the idea.

## 9. Workspace and reproducibility

Tracked material: source, this brief, narrow configuration examples, small fixtures, upstream revision/license records and summarized results.

Local bulk material under `.local/`: dependency checkouts, build products, environments, model weights, generated assets, captures, raw logs, reports and deployment manifests. Use per-run directories. Keep original source captures separate from derived outputs. Record storage use; add cleanup only for explicitly regenerable project-owned data.

Deployment is an adapter at the boundary, not a reason to place the workspace in Steam. Discover real paths. Prefer a supported link or launcher reference when available; otherwise use a reversible, approved copy with hashes and rollback. Never modify an existing save for the experiment.

## 10. Outcome hierarchy

A useful first outcome is a reproducible live visual comparison. A second is a stable, state-backed one-room observer. A third is an externally presented playable room with authoritative interactions. Broader asset coverage and advanced neural conditioning follow demonstrated value, not the reverse.

Do not promise a completion time or infer success from an upstream screenshot. A failed visual/latency test should leave a reusable benchmark and clean rollback, not another abandoned engine framework.
