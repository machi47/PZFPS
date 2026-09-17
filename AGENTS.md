# Repository instructions

## Objective and current phase

Build a reversible Apple Silicon experiment for first-person, generative visual enhancement of Project Zomboid. Preserve the larger state-constrained renderer vision in `docs/PREMISE.md`. The first implementation target is `docs/FIRST_EXPERIMENT.md`, not a renderer rewrite.

This initial repository is a brief only. Do not report any mod, launcher, benchmark, game integration or model as implemented or tested until evidence exists. Read and maintain `docs/STATUS.md`.

## Working method

Prefer existing packages and the cited upstream overlay over reimplementing capture, temporal inference, Metal composition or permissions handling. Inspect current source and license, pin its actual commit, record model identity separately, and retain attribution. Repository availability does not establish rights to weights or game assets.

Implement executable vertical slices rather than empty interfaces across many subsystems. Finish each slice with its relevant tests, local instructions and evidence. Do not replace a blocked prerequisite with an unsolicited engine or model rewrite. Continue other safe, useful work when a prerequisite is blocked.

Use asynchronous bounded queues for I/O and expensive work. Never move live game objects to worker threads or mutate them there. Prefer immutable snapshots copied on a verified game thread. Use dependency injection where it supports testing. Include meaningful logging, type checks, explicit errors and cancellation. Never silently substitute a CPU inference path and describe its speed as GPU performance.

## Workspace and change safety

The current Git root is the project root; discover it, do not assume a hard-coded home directory. Store all project-controlled artifacts beneath it. Use `.local/` for dependency checkouts, environments, caches, weights, builds, captures, raw reports and local configuration. Keep tracked source and small reproducibility metadata separate from local bulk data.

Detect actual Steam libraries, game paths and user-data locations. Initially read them only. Do not alter existing saves, game binaries, unrelated mods, Steam launch options, system toolchain selection, OS versions, security settings or global Codex configuration without specific approval for that change. A game launch and ordinary OS/Steam writes remain documented exceptions to project-local storage.

Use a fresh disposable test save. Before an approved external deployment, record destination, existing content hash or absence, ownership, backup, installed hash and rollback action under `.local/`. Rollback may restore only this project's unchanged deployed artifact; detect conflicts instead of overwriting later user edits. Do not delete or rewrite third-party material simply because it is near an installation path.

Inspect dependency sizes and available disk before downloads/builds. Do not commit weights, extracted DLLs, proprietary game files, private saves, credentials, large generated assets or captures. No public publishing or remote pushes without the owner's request. Never use a public or multiplayer server as a test target.

## Measurement and truthfulness

Separate game FPS, capture rate, completed neural frames, display refresh, latency, state age and offline export throughput. A synthetic benchmark is not a concurrent gameplay benchmark. An overlay hidden while inference continues is not the compute-off baseline.

Test moving viewpoints and actual interactions, not only a selected still. Do not conceal geometry hallucination, temporal drift, altered enemies, unreadable UI or delayed combat feedback. Record unavailable metrics as unavailable and explain the measurement method. Do not manufacture values from requested frame-rate settings.

Preserve raw source clips independently of enhanced clips. Evidence must record game/mod versions, upstream revisions, model checksum, hardware, settings and timestamps. Distinguish the latest diagnostic experiment from the latest accepted checkpoint.

## Architecture invariants

PZ owns collisions, action validity, inventory, doors, damage, enemies, time and saves. An external frontend is initially a spectator, not automatically a playable game. Input must eventually request validated game actions; do not fake playability through direct position changes or a second contradictory simulation.

Generate appearance persistently per asset or scene identity where possible. Never require a generative model to invent a new room each frame. Keep known topology and state authoritative. Doors, zombies and interactive silhouettes need conservative treatment and a correct non-neural fallback.

Do not claim that supplying depth/IDs to a wrapper conditions a model that was not designed to consume them. Validate supported model inputs. External masks, confidence tests and reprojection are distinct from learned conditioning.

## End-of-pass handoff

Update `docs/STATUS.md` with implemented paths, commands actually run, evidence locations, failures, last accepted checkpoint and the next smallest experiment. Return a runnable local test when available, or the exact missing prerequisite and the useful work completed. Do not end with a broad generic roadmap in place of the requested implementation slice.
