# Locked upstream inputs

Checked 17 September 2026.

## Native overlay

- Repository: <https://github.com/gtrg55/dlss5-macos-overlay>
- Locked commit: `0d4caa1fd36d2581785efa80e6ade09983a18f05` (`release v0.2`)
- Local checkout: `.local/upstream/dlss5-macos-overlay`
- License: MIT; the checkout retains `LICENSE` and its third-party notices.
- Download-size check before cloning: GitHub reported approximately 12 MiB; the
  detached local checkout used 26 MiB.

The overlay vendors an unmodified MLX-DLSS snapshot at
`0ca2deab092fe6f3e331bf4f616271dbc64521d0` under `Vendor/MLX-DLSS` and retains
its Apache-2.0 `LICENSE` and `NOTICE`. `Package.resolved` pins MLX Swift 0.31.6,
Swift Argument Parser 1.8.2, and Swift Numerics 1.1.1 by exact revision.

The overlay documentation says Swift 6.2+, but the resolved MLX Swift 0.31.6
package declares `swift-tools-version: 6.3`. The effective source-build gate is
therefore Swift 6.3+, not 6.2. The diagnostic run on Xcode 26.2 / Swift 6.2.3
stopped at manifest evaluation before compilation or XCTest.

The inspected overlay already owns ScreenCaptureKit capture, a bounded current
frame plus one latest pending frame, temporal-history resets, MLX/Metal
inference, Metal composition, overlay feedback exclusion, recording, and the
global stop shortcut. The project harness intentionally does not reimplement
those systems.

## Model identity

No weights or NVIDIA runtime file are present in either source repository. The
upstream extraction path expects a user-supplied `nvngx_dlssnr.dll`. The vendored
MLX-DLSS documentation identifies version `310.8.0.0`. The overlay author's
validated source had SHA-256
`dcc0dc2414aedec4a8e084647070383be068554042587180c20c784d4772d36f`; its
logical extracted weights had SHA-256
`f9047d0c934f19c71e0d125a65ad27f0435a1698209e5c8f205e0097e98f6541`.

These checksums establish identity, not redistribution rights. The source-code
licenses do not grant rights to the DLL, weights, or game assets. `bin/pzfps
model prepare` stages a lawfully obtained user file under `.local/models/`,
records its checksum separately, and then invokes the upstream preparation
script. It does not download a DLL.

## Rejected first-person candidate

- Steam Workshop item: <https://steamcommunity.com/sharedfiles/filedetails/?id=3786653645>
- Workshop ID: `3786653645`
- Mod ID declared by the page: `FirstPersonView`
- Page version: `0.2`
- Declared target/tag: Build 42; Interface; WIP
- Steam API size on inspection: 288,436 bytes

The page describes a custom Lua raycaster using PZ UI rendering and explicitly
calls the project an early proof of concept. Its author reports a significant
performance cost and practical rendering limits. That architecture is not an
acceptable first-person source for this experiment, regardless of whether it
can technically load on PZ 42.20. The candidate is rejected and will not be
subscribed, installed, launched or used as an acceptance baseline. A Build 42
tag is not evidence of quality or compatibility.

WhyNot_3D remains a development report, not a locally available release. It is
therefore not an installable fallback in this pass. The exact first-person
prerequisite is unresolved; the project will not fill that gap with the rejected
raycaster or by starting a replacement-renderer project.
