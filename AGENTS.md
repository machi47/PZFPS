# Repository instructions

## Active objective

Implement playable, persistent, coherent first-person presentation of the actual Project Zomboid client. PZ remains authoritative for map/state, players, networking, animation evaluation, collision, combat, inventory, actions, time, saves and UI semantics. This is not an independent game simulation or screenshot filter.

The original brief-only scope and overlay-first prerequisites in historical documents are superseded. No Workshop subscription, overlay comparison, proprietary model DLL, operating-system upgrade or engine-choice essay is a gate for work on the actual renderer, live interface, or canonical asset compiler.

## Implement and deliver

Prefer existing packages and inspect actual APIs/source before integrating them. Write executable code, run the relevant tests and inspect produced output. Continue implementation and repair while independent work on the requested path remains; do not repeatedly replace implementation with instructions for another agent, handoff reports or an expanding readiness framework.

The owner has explicitly requested GitHub implementation and publication. Commit meaningful work and verify the remote. Fetch before publishing; preserve concurrent changes and existing history. Do not force-push or overwrite unpublished local work. `bridge/`, `renderer/`, `src/` and `canonical/` contain complementary work; do not discard one to conceal a failed integration.

Do not prescribe Godot, native Metal, or in-process execution as a proven winner. The representation/state contract is independent of the execution boundary. An external frontend can still use the real client; an in-process hook need not use the Lua UI renderer. Base architecture changes on inspected constraints and measured execution, not conversational reversals.

## Representation constraints

Known object identity, placement, orientation, footprint and true state are constraints. Group source fragments/orientation variants only when supported by source evidence. Use source models where available. Complete genuinely unknown geometry and appearance into a persistent asset; never regenerate object identity on a camera turn. Keep structures, movable objects, doors, characters and attachments separable so movement exposes an intact background.

Preserve evaluated client animation poses and source model/clothing/attachment identity. A line skeleton, capsule, disconnected viewer or synthetic scene is a diagnostic, not accepted first-person gameplay. Do not independently choose animations or declare combat outcomes in the frontend.

Per-frame neural processing is optional. Models only consume conditioning they actually support. Preserve current correct presentation when enhancement is late or unreliable; do not queue increasingly old frames. A dropped display frame is different from a missing authoritative state delta: recover state with an explicit snapshot.

## Engineering and workspace

Use bounded asynchronous work for I/O/conversion and immutable snapshots copied at verified game-thread boundaries. Never access or mutate live game objects on workers. Prefer dependency injection, useful logging, typed inputs, explicit validation and cancellation. Serialise model pipelines unless reentrancy is established.

The project root is independent of Steam. Project-controlled dependencies, environments, weights, builds, extracted assets, caches, captures and raw logs belong under root `.local/`. Do not commit proprietary assets, decompiled game code, DLLs, model weights, private saves, credentials or large generated data.

Preserve existing saves and unrelated mods. Use a disposable local test profile. External deployment must be reversible and must not overwrite later user edits. Do not alter game binaries, Steam launch options, system toolchain selection, OS/security settings or unrelated projects without the owner's specific approval. Do not use a public/multiplayer server as a test target; multiplayer validation requires an approved private setup.

## Evidence

Report actual implemented paths, commands, output, failures and remote commit. Distinguish CPU/unit/synthetic/offline results from live client and concurrent GPU results. Keep state age increasing when data stalls. Never infer achieved throughput from a requested frame rate.

Do not call projected single-view sprites completed assets, painted RGB recovered reflectance, or stable but incorrect geometry artifact-free. Learned completion is unvalidated until an actual model run is inspected. Update status with useful factual evidence without turning status-writing into the task. The product remains the live first-person game, not a fixture or benchmark wrapper.
