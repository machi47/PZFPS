# Evidence register

Public sources checked on 17 September 2026. URLs are discovery references, not dependency locks. The local implementation must record actual commits, versions, checksums, licenses and test results. This brief did not run PZ, the first-person mods or the overlay on the owner's Mac.

## S1 — Native Mac overlay

https://github.com/gtrg55/dlss5-macos-overlay

Establishes an experimental third-party Swift/MLX/Metal neural overlay project. Does not establish official NVIDIA endorsement, parity or successful PZ integration.

## S2 — Build and model requirements

https://github.com/gtrg55/dlss5-macos-overlay/blob/main/docs/BUILDING.md

Establishes the build prerequisites and a user-supplied model preparation step. Source licensing and model rights are separate. Inspect the current instructions before execution.

## S3 — Live game testing

https://github.com/gtrg55/dlss5-macos-overlay/blob/main/docs/TESTING_GAME.md

Describes recommended first settings, window capture and overlay controls. It is guidance, not a PZ compatibility report.

## S4 — Overlay architecture

https://github.com/gtrg55/dlss5-macos-overlay/blob/main/docs/ARCHITECTURE.md

Describes capture, bounded queues, processing and composition. These are reuse candidates; they do not demonstrate semantic game-state preservation.

## S5 — Author's validation and benchmark

https://github.com/gtrg55/dlss5-macos-overlay/blob/main/docs/VALIDATION.md

Author-reported synthetic and component tests, with documented hardware and exclusions. No independent verification or concurrent PZ result in this brief.

## S6 — DLSS 5 concept

https://www.nvidia.com/en-us/geforce/news/dlss5-breakthrough-in-visual-fidelity-for-games/

NVIDIA's description of neural visual enhancement. It does not promise that its integration or weights are freely portable to macOS.

## S7 — Java hook candidate

https://github.com/zed-0xff/ZombieBuddy

https://github.com/zed-0xff/ZombieBuddy/blob/master/doc/Installation.md

Documents a Java-agent modding path including macOS. Not a guarantee of full state export, every animation hook, a headless player or external gameplay control.

## S8 — PZ world API

https://projectzomboid.com/modding/zombie/iso/IsoGridSquare.html

Documents structural accessors. Must be checked against the installed version and actual Lua/Java exposure.

## S9 — Candidate external renderer

https://docs.godotengine.org/en/stable/engine_details/architecture/internal_rendering_architecture.html

https://docs.godotengine.org/en/stable/tutorials/rendering/renderers.html

Godot rendering documentation, including a native Metal path. This is a proposed implementation choice, not a measured PZ frontend.

## S10 — Generative splat reference

https://github.com/apple-aiml-research/ml-sharp

Single-image scene prediction reference. Prediction and the supplied renderer have different platform requirements; do not assume the whole package is a Mac game renderer.

## S11 — First-person raycaster

https://steamcommunity.com/sharedfiles/filedetails/?id=3786653645

https://steamcommunity.com/workshop/filedetails/discussion/3786653645/582806854239846207/

Author's experimental B42 view and discussion of Lua/UI rendering limits. Current availability and Mac compatibility require local confirmation.

## S12 — WhyNot_3D tile conversion report

https://www.reddit.com/r/projectzomboid/comments/1wb8hlu/project_zomboid_automatic_tiles_to_3d_mesh/

A development report by the author describing depth-map/primitive-based conversion. Not evidence that a finished, licensed, downloadable Mac implementation is available.

## S13 — Upstream MLX implementation

https://github.com/iamwavecut/MLX-DLSS

Experimental model implementation. Check model source/version and separate redistribution terms; do not infer rights from the code repository.

## S14 — Repository instructions for Codex

https://developers.openai.com/codex/guides/agents-md

Official instructions for repository-level AGENTS.md. Existing global/project overrides can affect the active instruction chain.

## S15 — PZ build reference

https://projectzomboid.com/blog/news/2026/07/build-42-stable-plans/

Official build information. The actually installed version and mod dependencies, not a remembered build number, determine this experiment's compatibility.

## Unsupported claims explicitly excluded

No verified combination of PZ + cited first-person mod + Mac overlay on the owner's hardware. No universal mod compatibility. No verified all-map exporter, complete gameplay action bridge or headless-client mode. No proof that a separate renderer is automatically faster. No guarantee that neural appearance preserves scene identity or reaches live-play performance. No claim that any upstream source has been exhaustively security-audited.
