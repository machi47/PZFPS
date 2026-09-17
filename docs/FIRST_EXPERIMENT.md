# First experiment: a reversible live visual comparison

## Goal

The owner can launch a disposable PZ session, use a compatible first-person view if available, toggle neural enhancement, interact, and judge whether the result is visually worthwhile and responsive. Record the evidence needed to repeat that judgment. Do not substitute a video export for live playability.

## 1. Establish facts before changes

Implement a local inspection/doctor operation. Record the project's absolute root, Git status, macOS version, chip, installed memory, available storage, architecture of relevant processes, full toolchain availability, Steam library paths, PZ build/branch and candidate mod versions.

The native overlay's documented source-build prerequisites include macOS 26+, full Xcode/Swift 6.2+, the Metal toolchain, CMake, Ninja and Python. It requires a compatible user-supplied model source; the repository does not include weights. Validate its present build instructions and model checksums instead of guessing compatibility. [S2]

A missing OS/toolchain/model prerequisite is a concrete gate. Do not silently upgrade the Mac, change global toolchain selection, download an arbitrary DLL or begin rebuilding the model as a workaround. Complete other independent safe setup and state exactly what is missing.

Discover the real game location rather than assuming the default Steam library. Record whether the game runtime is native or translated; that does not require a separate native renderer process to share the same architecture.

## 2. Reuse and lock sources

Inspect and pin an exact revision of the overlay under `.local/upstream/`. Keep our patches, launch adapters and dependency metadata in tracked project source. Record upstream and model licenses separately. Audit scripts before running them, redirect project-controlled caches/builds into the workspace where supported, and record unavoidable external writes.

The cited B42 first-person raycaster is a proof-of-concept and its author describes rendering limits. Verify subscription/source availability, dependencies, the installed PZ build and actual Mac behavior; it is not known-compatible just because it uses Lua. The WhyNot_3D author has also described automatic tile-to-mesh work, but availability of a finished reusable release has not been established here. [S11, S12]

Do not wait indefinitely for an unavailable mod release. An isometric capture is a useful separately labeled fallback appearance probe. It does not fulfill first-person gameplay.

## 3. Build only the first useful harness

The local implementation should provide project-root entrypoints for inspection, approved setup, launch, stopping inference, evidence capture/reporting and reversible deployment cleanup. Choose actual names during implementation; this document does not pretend they already exist.

Reuse upstream build/test/launch scripts. Add only the project-path handling and repeatability they lack. Run source tests independently of model-dependent tests. A prepared app or bypass mode without weights does not count as successful neural inference.

Limit initial screen capture to the selected game window and avoid capturing unrelated personal content. Use normal macOS permission prompts. Keep input in PZ. Provide an obvious compute-stop action and a clear way back to the unmodified game view.

## 4. A small reproducible scene

Use a new disposable single-player save. Select an ordinary room with a door, window, several objects, nearby exterior and a controlled encounter. Repeat a short route: look around slowly, turn quickly, approach a wall, open/close a door, cross its threshold, inspect the same furniture from another side, open inventory, and observe movement/attack feedback with an enemy.

The scene should expose disocclusion, transparency, thin objects, UI, temporal drift and latency. Begin without an asset overhaul so we can attribute changes to the actual tested components.

## 5. Three baselines

Compare the game alone, the game with first-person view, and that same view with neural processing. Keep game resolution, scene, graphics settings and route comparable. Include warmup and steady-state periods separately.

Start with the overlay's Natural profile and 512-pixel processing scale, then compare a lower scale and a higher scale only when measured resources permit. Use windowed/borderless mode first. A requested capture rate is not a completed inference rate. Hiding this overlay can leave processing running; use its stop control for a compute-off baseline. [S3]

Do not use a single averaged FPS figure to conceal uneven delivery. Save game frame-time data when available, completed enhanced-frame timing, dropped frames, processing settings, memory pressure and a clear latency-measurement method. When input-to-photon measurement is unavailable, label internal stage timing as only partial latency.

## 6. Acceptance and decision

The technical floor is that enhancement actually runs, the game remains controllable, source and enhanced evidence are preserved, no save is damaged, and stopping the experiment returns to the ordinary game.

For provisional live-play acceptance, target at least 30 completed enhanced frames per second during the defined route, or a validated current-frame fallback that remains responsive when enhancement runs more slowly. This is a chosen project target, not a forecast. Record frame-time tails and the owner's perceived responsiveness; an average above the target is not sufficient by itself.

Reject any run in which important geometry, enemies or interaction cues disappear or invent misleading states. Record appearance quality and game-state fidelity separately. An attractive offline result can pass the appearance probe while failing the live-play gate.

The published overlay benchmark used an M4 Max with 128 GB and a synthetic pattern processed at 512×288. It reports 33.36 processed FPS excluding capture/display and without concurrent PZ. This is context for choosing the experiment, not a prediction for this machine. [S5]

When live processing is too slow, try the existing lower-cost settings before changing architecture. Persistent generated assets without runtime inference become a separate option only after the owner chooses that next scope. When model access is blocked, a source/bypass test is still useful but must be labeled non-neural; do not present it as the requested effect.

## 7. First report

Write a short tracked summary referencing local raw evidence. Include actual upstream commits and game/mod versions, machine details, tested settings, what worked, observed visual failures, performance definitions, current blockers and the next smallest experiment.

Label the checkpoint precisely: NOT RUN, SOURCE CHECKED, BYPASS WORKING, OFFLINE APPEARANCE TESTED, LIVE OVERLAY WORKING, or LIVE FIRST-PERSON ACCEPTED. Do not skip labels merely because one component works independently.

## 8. Gate for the next slice

Only proceed to a state bridge once there is a concrete reason from the evidence—for example, camera changes make textures drift, image-only processing corrupts doors, or persistent geometry offers more benefit than further overlay tuning.

The next bridge test is read-only: one room, a door-state change, one moving entity, snapshot/delta ordering, unload/removal and reconnect. The renderer is initially a spectator. Bidirectional authoritative actions are a separate milestone with explicit interaction tests.
