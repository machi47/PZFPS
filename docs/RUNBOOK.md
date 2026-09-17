# First experiment runbook

This is the narrow, reversible path for the first comparison. It does not build
an external renderer or alter Project Zomboid files, saves, launch options, or
system settings.

## 1. Inspect and verify source

From the project root:

```sh
bin/pzfps doctor --write
bin/pzfps upstream fetch
bin/pzfps upstream verify
```

`doctor` reads Steam and the PZ installation but does not launch or modify them.
All reports and dependency state go under `.local/`. SwiftPM is routed through
`tools/local-bin/swift`, which keeps its cache, configuration, and security
directories under `.local/`; the upstream checkout and build directory are
also under `.local/`.

The pinned overlay requires macOS 26+. Its resolved MLX Swift dependency also
requires Swift 6.3 even though the overlay document says Swift 6.2+. On an older host, `upstream verify`
stops before executing. `--force-unsupported-host` permits only a diagnostic
source-check attempt; even a successful compile would not establish supported
overlay operation.

## 2. Satisfy the two external prerequisites

First, provide a lawfully obtained `nvngx_dlssnr.dll` version 310.8.0.0. Inspect
its identity before extraction:

```sh
bin/pzfps model inspect /path/to/nvngx_dlssnr.dll
bin/pzfps model prepare /path/to/nvngx_dlssnr.dll
```

The second command refuses a checksum different from the upstream-validated
source unless `--allow-unverified-model` is explicitly supplied. It copies the
input beneath `.local/models/sources/`, records provenance, and calls the
upstream preparation script. No NVIDIA code is executed.

The inspected Workshop item `3786653645` is rejected for this experiment. It is
a Lua/UI raycaster whose author documents significant performance and rendering
limits; do not subscribe to or install it. WhyNot_3D has no discovered release.
An acceptable released first-person implementation is therefore still missing.
Do not replace that prerequisite with an engine rewrite in this pass.

Do not change macOS, Xcode selection, Steam branch, launch options, or game
files as part of these commands. The current macOS 15.7.3 host cannot proceed
to the supported overlay build without an owner-approved OS change.

## 3. Build and prepare evidence

On a host that passes the macOS gate and has a prepared model:

```sh
bin/pzfps overlay build
RUN_ID=$(bin/pzfps run create --label house-route)
```

The run directory contains separate locations for each baseline and for source
versus enhanced media. It also contains an observation form and a CSV whose
columns keep game FPS, capture rate, completed neural FPS, display refresh,
latency, state age, and offline throughput distinct.

In PZ, create a new disposable single-player save with a unique experiment
name. Never reuse an existing save. Use an ordinary room with a door, window,
several objects, nearby exterior, and a controlled enemy encounter. Windowed or
borderless mode is the initial condition.

Launching is deliberately explicit because Steam and the game make ordinary
writes outside the project:

```sh
bin/pzfps game launch --run "$RUN_ID" --acknowledge-steam-writes
bin/pzfps overlay launch --run "$RUN_ID"
```

Select the PZ window in the overlay. Start with Natural, 512 px, requested
capture 30 FPS, intensity 1.0, motion awareness enabled, and Before/After 0.50.
The requested capture rate is a setting, not a result.

## 4. Run the comparable route

Only after an acceptable first-person implementation exists, repeat the same route for:

1. game alone, with the overlay process stopped;
2. installed first-person view, with the overlay process stopped;
3. that first-person view with neural processing active.

For each route include warmup and steady state. Look around slowly, turn
quickly, approach a wall, open and close a door, cross the threshold, view the
same furniture from another side, open inventory, and observe movement and
attack feedback with an enemy.

Use the overlay's Screenshot/Record Video controls. A split view is useful for
live judgment, but also save separate source and enhanced artifacts. A source
clip recorded while inference is active is visual source evidence, not the
compute-off baseline. Use the upstream stop control (`Option-Command-0`) or:

```sh
bin/pzfps overlay stop
```

Hiding the overlay with `Option-Command-1` does not stop processing.

Import preserved artifacts without overwriting prior evidence:

```sh
bin/pzfps run add-evidence --run "$RUN_ID" --baseline first-person --kind source --file /path/to/source.mov
bin/pzfps run add-evidence --run "$RUN_ID" --baseline neural --kind enhanced --file /path/to/enhanced.mov
```

Record measured values, leaving unavailable fields blank and explaining the
method. For example:

```sh
bin/pzfps run add-metric --run "$RUN_ID" --baseline neural --phase steady \
  --completed-neural-fps 31.2 --processing-ms-p50 30.1 --processing-ms-p95 42.8 \
  --method 'overlay completed-frame counter and exported diagnostic log'
```

Do not infer game FPS, latency, or completed frames from the requested 30 FPS.
Record visible failures in `observations.md`, including geometry hallucination,
temporal drift, changed enemies, unreadable UI, or delayed combat feedback.

## 5. Checkpoint labels

`run finalize` accepts only the labels in `docs/FIRST_EXPERIMENT.md`. Live labels
require source plus enhanced evidence and a steady measured neural row. “LIVE
FIRST-PERSON ACCEPTED” additionally requires explicit owner acceptance and
either 30 completed neural FPS or a recorded validated current-frame fallback,
plus controllability and important-state preservation.

Example only after those facts exist:

```sh
bin/pzfps run finalize --run "$RUN_ID" \
  --checkpoint 'LIVE FIRST-PERSON ACCEPTED' --owner-accepted
```

Until then, do not promote an offline result, a source check, or an isometric
fallback to a live first-person checkpoint.
