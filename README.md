# PZ Neural View

Prepared 17 September 2026. **Status: ACTIVE DIAGNOSTIC. A one-process PZ integration, authoritative snapshot bridge, B42 asset index and guarded in-process perspective world replacement are implemented. Accepted live first-person gameplay and neural enhancement are not yet demonstrated.**

## Purpose

Keep Project Zomboid authoritative for the game, while replacing its visual presentation with a persistent, photorealistic, first-person interpretation. Use generative methods to supply missing appearance—not to invent gameplay state. Start with a playable visual experiment rather than a new engine.

The intended machine is the owner's Apple Silicon MacBook. The project workspace is independent of the Steam installation. The discovered facts and current blockers are recorded in `docs/STATUS.md`.

## Read in this order

1. `AGENTS.md` — repository operating rules and scope boundaries.
2. `docs/PREMISE.md` — the full concept, architectural decisions and longer-term path.
3. `docs/FIRST_EXPERIMENT.md` — the first implementation objective and acceptance gates.
4. `docs/SOURCES.md` — sources, what they establish, and what they do not.
5. `docs/STATUS.md` — current evidence, blockers and next experiment.
6. `docs/RUNBOOK.md` — executable first-experiment procedure.

## Current implementation decision

The released first-person Workshop candidate was inspected and rejected: it is
a Lua/UI raycaster with documented visual and performance limits. The live path
therefore replaces only PZ's world draw inside the existing PZ frame, then lets
PZ draw its normal text and UI. There is no second visible Godot window or
second world renderer. PZ remains authoritative for gameplay.

The concrete neural overlay candidate remains pinned for research comparison,
but its macOS/toolchain and model prerequisites are currently unavailable. It
is not the foundation of the live renderer. Treat it and MLX-DLSS as
experimental third-party software, not official NVIDIA support. See S1–S5.

This is a reversible feasibility probe. A positive result justifies deeper state integration. A poor result is useful evidence, not a reason to conceal latency or replace the deliverable with a rendered still.

## What is included here

The tracked `bin/pzfps` harness provides read-only inspection, pinned upstream setup and source verification, model identity/provenance handling, overlay build/launch/stop, an explicit Steam launch, evidence capture organization, measurement recording, checkpoint validation and conflict-safe deployment bookkeeping. The native overlay itself stays pinned under `.local/upstream/`; it is not reimplemented here.

No model weights, NVIDIA DLL, game assets, first-person mod files, built overlay app, capture, live benchmark or accepted gameplay result is included.

## Local entrypoint

```sh
bin/pzfps doctor --write
bin/pzfps upstream fetch
bin/pzfps upstream verify
```

See `docs/RUNBOOK.md` before supplying a model, subscribing to the Workshop item, launching the game, or making any external deployment.

## Workspace contract

All project-controlled source, configurations, dependencies, models, builds, captures and reports belong inside the chosen project directory. Large/local material belongs under `.local/` and is excluded from Git. Existing Steam game data is read in place, not copied into the repository. OS-managed settings, Steam/Workshop files, game saves and explicitly approved mod-deployment locations are documented exceptions; this contract does not pretend those applications can never write elsewhere.

## Scope boundary

The active slice is the smallest section of the intended renderer: one PZ
window, authoritative live chunks and actors, perspective structural geometry,
and the normal PZ UI. It is not permission for mass asset conversion, a second
simulation, model training, or an unmeasured engine rewrite. See
`docs/STATUS.md` for the exact acceptance state.
