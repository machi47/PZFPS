from __future__ import annotations

import argparse
import json
import os
import shutil
import signal
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

from . import __version__
from .assets import compile_geometry
from .asset_coverage import write_coverage, write_scene_coverage
from .model_assets import compile_model_index
from .item_definitions import compile_item_definitions
from .map_usage import index_map_header_usage
from .texture_packs import extract_sprite_page, index_texture_pack
from .tile_definitions import compile_tile_definitions
from .depth_surfaces import compile_planar_roof_surfaces
from .prop_surfaces import compile_planar_prop_surfaces
from .common import LOCAL, ROOT, command, load_config, now_utc, parse_version_prefix, sha256_file, timestamp_id, write_json
from .deployment import cleanup as deployment_cleanup
from .deployment import record_before, record_installed
from .doctor import inspect, model_identity, write_report
from .evidence import CHECKPOINTS, add_evidence, add_metric, create_run, finalize_run, resolve_run
from .runtime import launch as launch_isolated
from .runtime import launch_app as launch_app_isolated
from .runtime import process_state as isolated_process_state
from .runtime import pz_processes
from .runtime import stage as stage_isolated
from .runtime import stop as stop_isolated


UPSTREAM = LOCAL / "upstream" / "dlss5-macos-overlay"


class UserError(RuntimeError):
    pass


def _print_json(value: Any) -> None:
    print(json.dumps(value, indent=2, sort_keys=True))


def _supported_host(config: dict[str, Any]) -> tuple[bool, str, str]:
    report = inspect()
    found_macos = report["machine"]["macos"] or "0"
    found_swift = report["toolchain"]["effective_swift_version"] or "0"
    required_macos = int(config["overlay"]["minimum_macos_major"])
    required_swift = parse_version_prefix(config["overlay"]["effective_minimum_swift"])
    supported = (
        parse_version_prefix(found_macos) >= (required_macos,)
        and parse_version_prefix(found_swift) >= required_swift
    )
    return supported, found_macos, found_swift


def _assert_upstream() -> str:
    config = load_config()
    if not UPSTREAM.is_dir():
        raise UserError("overlay checkout is absent; run: bin/pzfps upstream fetch")
    revision = command(["git", "rev-parse", "HEAD"], cwd=UPSTREAM)
    actual = revision.stdout.strip()
    expected = config["overlay"]["commit"]
    if revision.returncode or actual != expected:
        raise UserError(f"overlay revision mismatch: expected {expected}, found {actual or 'unknown'}")
    status = command(["git", "status", "--porcelain"], cwd=UPSTREAM)
    if status.returncode or status.stdout.strip():
        raise UserError("pinned overlay checkout has local changes; refusing to use it")
    return actual


def _workspace_env() -> dict[str, str]:
    environment = os.environ.copy()
    environment["PATH"] = f"{ROOT / 'tools' / 'local-bin'}:{environment.get('PATH', '')}"
    environment["PIP_CACHE_DIR"] = str(LOCAL / "cache" / "pip")
    environment["TMPDIR"] = str(LOCAL / "tmp")
    environment["DLSS_BUILD_JOBS"] = environment.get("DLSS_BUILD_JOBS", "2")
    for path in (Path(environment["PIP_CACHE_DIR"]), Path(environment["TMPDIR"])):
        path.mkdir(parents=True, exist_ok=True)
    return environment


def _run_logged(argv: list[str], *, cwd: Path, label: str, env: dict[str, str] | None = None) -> tuple[int, Path]:
    log = LOCAL / "logs" / f"{label}-{timestamp_id()}.log"
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open("w", encoding="utf-8") as handle:
        handle.write(f"recorded_at={now_utc()}\n")
        handle.write(f"cwd={cwd}\n")
        handle.write("command=" + " ".join(argv) + "\n\n")
        handle.flush()
        process = subprocess.run(argv, cwd=cwd, env=env, stdout=handle, stderr=subprocess.STDOUT, check=False)
    return process.returncode, log


def command_doctor(args: argparse.Namespace) -> int:
    report = inspect()
    if args.write:
        path = write_report(report)
        report["report_path"] = str(path)
    _print_json(report)
    return 0


def command_upstream_fetch(_: argparse.Namespace) -> int:
    config = load_config()["overlay"]
    destination = UPSTREAM
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        _assert_upstream()
        print(f"Pinned overlay already present: {destination}")
        return 0
    result = subprocess.run(
        ["git", "clone", "--filter=blob:none", "--no-tags", config["repository"], str(destination)],
        cwd=ROOT,
        check=False,
    )
    if result.returncode:
        raise UserError("overlay clone failed")
    checkout = subprocess.run(["git", "checkout", "--detach", config["commit"]], cwd=destination, check=False)
    if checkout.returncode:
        raise UserError("overlay checkout failed")
    _assert_upstream()
    print(f"Pinned overlay: {destination} @ {config['commit']}")
    return 0


def command_upstream_verify(args: argparse.Namespace) -> int:
    revision = _assert_upstream()
    config = load_config()
    supported, found_macos, found_swift = _supported_host(config)
    if not supported and not args.force_unsupported_host:
        raise UserError(
            f"upstream requires macOS {config['overlay']['minimum_macos_major']}+ and Swift "
            f"{config['overlay']['effective_minimum_swift']}+; found macOS {found_macos}, Swift {found_swift}. "
            "Use --force-unsupported-host only for a diagnostic attempt."
        )
    returncode, log = _run_logged(
        ["bash", "scripts/verify-source.sh"],
        cwd=UPSTREAM,
        label="upstream-verify-source",
        env=_workspace_env(),
    )
    state = {
        "recorded_at": now_utc(),
        "revision": revision,
        "command": "bash scripts/verify-source.sh",
        "returncode": returncode,
        "host_macos": found_macos,
        "host_swift": found_swift,
        "host_supported": supported,
        "model_required": False,
        "log": str(log),
        "checkpoint_eligible": returncode == 0,
    }
    write_json(LOCAL / "state" / "upstream-verification.json", state)
    _print_json(state)
    return returncode


def command_model_inspect(args: argparse.Namespace) -> int:
    path = args.path.expanduser()
    if not path.is_file():
        raise UserError(f"model source does not exist: {path}")
    _print_json(model_identity(path))
    return 0


def command_model_prepare(args: argparse.Namespace) -> int:
    _assert_upstream()
    source = args.path.expanduser().resolve()
    if not source.is_file():
        raise UserError(f"model source does not exist: {source}")
    identity = model_identity(source)
    if not identity["matches_validated_source"] and not args.allow_unverified_model:
        raise UserError("model checksum is not the validated source; inspect it or pass --allow-unverified-model explicitly")
    staged = LOCAL / "models" / "sources" / identity["sha256"] / source.name
    staged.parent.mkdir(parents=True, exist_ok=True)
    if not staged.exists():
        shutil.copy2(source, staged)
    elif sha256_file(staged) != identity["sha256"]:
        raise UserError("staged model source checksum changed")
    returncode, log = _run_logged(
        ["bash", "scripts/prepare-model.sh", str(staged)],
        cwd=UPSTREAM,
        label="prepare-model",
        env=_workspace_env(),
    )
    identity.update({"staged_path": str(staged), "prepared_at": now_utc(), "returncode": returncode, "log": str(log)})
    write_json(LOCAL / "models" / "provenance" / f"{identity['sha256']}.json", identity)
    _print_json(identity)
    return returncode


def command_overlay_build(_: argparse.Namespace) -> int:
    revision = _assert_upstream()
    config = load_config()
    supported, found_macos, found_swift = _supported_host(config)
    if not supported:
        raise UserError(
            "cannot build supported app: upstream requires macOS 26+ and Swift 6.3+; "
            f"found macOS {found_macos}, Swift {found_swift}"
        )
    model = UPSTREAM / "Models" / "NR.dlss"
    if not (model / "manifest.json").is_file() or not (model / "weights.safetensors").is_file():
        raise UserError("prepared NR.dlss model is absent; run model prepare first")
    returncode, log = _run_logged(
        ["bash", "scripts/build-app.sh"], cwd=UPSTREAM, label="build-overlay", env=_workspace_env()
    )
    state = {"recorded_at": now_utc(), "revision": revision, "returncode": returncode, "log": str(log)}
    write_json(LOCAL / "state" / "overlay-build.json", state)
    _print_json(state)
    return returncode


def command_overlay_launch(args: argparse.Namespace) -> int:
    _assert_upstream()
    run = resolve_run(args.run)
    app = UPSTREAM / "dist" / "DLSS_5_APPLE_SILICON.app"
    executable = app / "Contents" / "MacOS" / "DLSS_5_APPLE_SILICON"
    if not executable.is_file():
        raise UserError("built overlay app is absent; run overlay build")
    log_path = run / "logs" / "overlay-process.log"
    log_handle = log_path.open("ab")
    process = subprocess.Popen(
        [str(executable)],
        cwd=UPSTREAM,
        stdout=log_handle,
        stderr=subprocess.STDOUT,
        start_new_session=True,
    )
    state = {
        "started_at": now_utc(),
        "pid": process.pid,
        "executable": str(executable.resolve()),
        "run_id": args.run,
        "log": str(log_path),
    }
    write_json(LOCAL / "state" / "overlay-process.json", state)
    _print_json(state)
    return 0


def command_overlay_stop(_: argparse.Namespace) -> int:
    state_path = LOCAL / "state" / "overlay-process.json"
    if not state_path.is_file():
        raise UserError("no project-launched overlay process is recorded")
    state = json.loads(state_path.read_text(encoding="utf-8"))
    pid = int(state["pid"])
    executable = Path(state["executable"]).resolve()
    ps = command(["ps", "-p", str(pid), "-o", "comm="])
    running = ps.returncode == 0 and ps.stdout.strip()
    if not running:
        print("Recorded overlay is no longer running.")
        return 0
    if Path(ps.stdout.strip()).resolve() != executable:
        raise UserError("recorded PID now belongs to another executable; refusing to signal it")
    os.kill(pid, signal.SIGTERM)
    for _ in range(50):
        if command(["kill", "-0", str(pid)]).returncode != 0:
            state["stopped_at"] = now_utc()
            write_json(state_path, state)
            print("Overlay process stopped; ordinary game view is unaffected.")
            return 0
        time.sleep(0.1)
    raise UserError("overlay did not exit after SIGTERM; use Option-Command-0, then quit the app")


def command_game_launch(args: argparse.Namespace) -> int:
    run = resolve_run(args.run)
    if not args.acknowledge_steam_writes:
        raise UserError("game launch requires --acknowledge-steam-writes")
    report = inspect()
    install = report["game"]["install_path"]
    if not report["game"]["installed"] or not install:
        raise UserError("Project Zomboid is not installed")
    marker = {
        "requested_at": now_utc(),
        "run_id": args.run,
        "steam_uri": "steam://run/108600",
        "warning": "Create and use a new disposable single-player save; do not load an existing save.",
    }
    write_json(run / "logs" / "game-launch.json", marker)
    launched = subprocess.run(["open", "steam://run/108600"], check=False)
    if launched.returncode:
        raise UserError("Steam launch request failed")
    _print_json(marker)
    return 0


def _installed_game_path() -> Path:
    report = inspect()
    install = report["game"]["install_path"]
    if not report["game"]["installed"] or not install:
        raise UserError("Project Zomboid is not installed")
    return Path(install)


def command_game_stage_isolated(_: argparse.Namespace) -> int:
    _print_json(stage_isolated(_installed_game_path()))
    return 0


def command_game_launch_isolated(_: argparse.Namespace) -> int:
    _print_json(launch_isolated(_installed_game_path()))
    return 0


def command_game_launch_app_isolated(_: argparse.Namespace) -> int:
    _print_json(launch_app_isolated(_installed_game_path()))
    return 0


def command_game_status_isolated(_: argparse.Namespace) -> int:
    state = isolated_process_state()
    state["all_pz_processes"] = pz_processes()
    _print_json(state)
    return 0


def command_game_stop_isolated(_: argparse.Namespace) -> int:
    _print_json(stop_isolated())
    return 0


def command_assets_index_geometry(args: argparse.Namespace) -> int:
    report = inspect()
    install = report["game"]["install_path"]
    if not report["game"]["installed"] or not install:
        raise UserError("Project Zomboid is not installed")
    source = (
        Path(install)
        / "Project Zomboid.app"
        / "Contents"
        / "Java"
        / "media"
        / "tileGeometry.txt"
    )
    if not source.is_file():
        raise UserError(f"installed B42 tile geometry is absent: {source}")
    output = args.output or (
        LOCAL
        / "assets"
        / f"pz-{report['game']['version']}"
        / "tile-geometry.json"
    )
    document = compile_geometry(source, output, game_version=report["game"]["version"])
    _print_json(
        {
            key: document[key]
            for key in (
                "schema_version",
                "game_version",
                "source",
                "source_sha256",
                "tile_count",
                "geometry_counts",
            )
        }
        | {"output": str(output)}
    )
    return 0


def command_assets_index_definitions(args: argparse.Namespace) -> int:
    report = inspect()
    install = report["game"]["install_path"]
    if not report["game"]["installed"] or not install:
        raise UserError("Project Zomboid is not installed")
    source = (
        Path(install)
        / "Project Zomboid.app"
        / "Contents"
        / "Java"
        / "media"
        / "newtiledefinitions.tiles.txt"
    )
    if not source.is_file():
        raise UserError(f"installed text tile definitions are absent: {source}")
    output = args.output or (
        LOCAL / "assets" / f"pz-{report['game']['version']}" / "tile-definitions.json"
    )
    document = compile_tile_definitions(source, output, game_version=report["game"]["version"])
    _print_json({
        "schema_version": document["schema_version"],
        "game_version": document["game_version"],
        "source": document["source"],
        "source_sha256": document["source_sha256"],
        "tile_count": document["tile_count"],
        "output": str(output),
    })
    return 0


def command_assets_compile_roof_surfaces(args: argparse.Namespace) -> int:
    report = inspect()
    install = report["game"]["install_path"]
    if not report["game"]["installed"] or not install:
        raise UserError("Project Zomboid is not installed")
    version = report["game"]["version"]
    media = Path(install) / "Project Zomboid.app" / "Contents" / "Java" / "media"
    definitions = args.definitions or LOCAL / "assets" / f"pz-{version}" / "tile-definitions.json"
    textures = args.textures or LOCAL / "assets" / f"pz-{version}" / "Tiles2x-texture-index.json"
    map_usage = args.map_usage or LOCAL / "assets" / f"pz-{version}" / "map-header-usage.json"
    assignments = media / "tileDepthTextureAssignments.txt"
    depthmaps = media / "depthmaps"
    seams = media / "seams.txt"
    output = args.output or LOCAL / "assets" / f"pz-{version}" / "roof-depth-surfaces.json"
    for label, path in (("tile-definition index", definitions), ("texture index", textures),
                        ("map-header usage", map_usage),
                        ("depth assignments", assignments),
                        ("depth-map directory", depthmaps),
                        ("roof seam topology", seams)):
        if not path.exists():
            raise UserError(f"{label} is absent: {path}")
    document = compile_planar_roof_surfaces(
        definitions, assignments, depthmaps, output,
        game_version=version, textures_path=textures, map_usage_path=map_usage,
        seams_path=seams)
    _print_json({
        "schema_version": document["schema_version"],
        "game_version": document["game_version"],
        "method": document["method"],
        "tile_count": document["tile_count"],
        "triangle_count": document["triangle_count"],
        "contextual_tile_count": document["contextual_tile_count"],
        "contextual_triangle_count": document["contextual_triangle_count"],
        "source_equivalent_alias_count": document["source_equivalent_alias_count"],
        "rejected": document["rejected"],
        "skipped": document["skipped"],
        "output": str(output),
    })
    return 0


def command_assets_compile_prop_surfaces(args: argparse.Namespace) -> int:
    report = inspect()
    install = report["game"]["install_path"]
    if not report["game"]["installed"] or not install:
        raise UserError("Project Zomboid is not installed")
    version = report["game"]["version"]
    media = Path(install) / "Project Zomboid.app" / "Contents" / "Java" / "media"
    root = LOCAL / "assets" / f"pz-{version}"
    definitions = args.definitions or root / "tile-definitions.json"
    textures = args.textures or root / "Tiles2x-texture-index.json"
    map_usage = args.map_usage or root / "map-header-usage.json"
    assignments = media / "tileDepthTextureAssignments.txt"
    depthmaps = media / "depthmaps"
    output = args.output or root / "prop-depth-surfaces.json"
    for label, path in (
        ("tile-definition index", definitions),
        ("texture index", textures),
        ("map-header usage", map_usage),
        ("depth assignments", assignments),
        ("depth-map directory", depthmaps),
    ):
        if not path.exists():
            raise UserError(f"{label} is absent: {path}")
    document = compile_planar_prop_surfaces(
        definitions,
        assignments,
        depthmaps,
        textures,
        map_usage,
        output,
        game_version=version,
        map_referenced_only=args.map_referenced_only,
    )
    _print_json({
        "schema_version": document["schema_version"],
        "game_version": document["game_version"],
        "method": document["method"],
        "scope": document["scope"],
        "candidate_count": document["candidate_count"],
        "tile_count": document["tile_count"],
        "triangle_count": document["triangle_count"],
        "rejected": document["rejected"],
        "output": str(output),
    })
    return 0


def command_assets_index_textures(args: argparse.Namespace) -> int:
    report = inspect()
    install = report["game"]["install_path"]
    if not report["game"]["installed"] or not install:
        raise UserError("Project Zomboid is not installed")
    source = (
        Path(install)
        / "Project Zomboid.app"
        / "Contents"
        / "Java"
        / "media"
        / "texturepacks"
        / args.pack
    )
    if not source.is_file():
        raise UserError(f"installed texture pack is absent: {source}")
    output = args.output or (
        LOCAL
        / "assets"
        / f"pz-{report['game']['version']}"
        / f"{source.stem}-texture-index.json"
    )
    document = index_texture_pack(source, output, game_version=report["game"]["version"])
    _print_json(
        {
            key: document[key]
            for key in (
                "schema_version",
                "game_version",
                "source",
                "source_sha256",
                "source_size",
                "page_count",
                "texture_count",
            )
        }
        | {"output": str(output)}
    )
    return 0


def command_assets_index_models(args: argparse.Namespace) -> int:
    report = inspect()
    install = report["game"]["install_path"]
    if not report["game"]["installed"] or not install:
        raise UserError("Project Zomboid is not installed")
    media = Path(install) / "Project Zomboid.app" / "Contents" / "Java" / "media"
    output = args.output or (
        LOCAL / "assets" / f"pz-{report['game']['version']}" / "model-index.json"
    )
    document = compile_model_index(
        media / "scripts",
        media / "models_X",
        media / "textures",
        output,
        game_version=report["game"]["version"],
    )
    _print_json(
        {
            key: document[key]
            for key in (
                "schema_version",
                "game_version",
                "source_count",
                "model_count",
                "mesh_file_count",
                "texture_file_count",
                "resolved_mesh_count",
                "unresolved_mesh_count",
                "resolved_texture_count",
                "unresolved_texture_count",
                "alias_count",
            )
        }
        | {"output": str(output)}
    )
    return 0


def command_assets_index_items(args: argparse.Namespace) -> int:
    report = inspect()
    install = report["game"]["install_path"]
    if not report["game"]["installed"] or not install:
        raise UserError("Project Zomboid is not installed")
    media = Path(install) / "Project Zomboid.app" / "Contents" / "Java" / "media"
    root = LOCAL / "assets" / f"pz-{report['game']['version']}"
    models = args.models or root / "model-index.json"
    output = args.output or root / "item-definitions.json"
    document = compile_item_definitions(
        media / "scripts", models, output, game_version=report["game"]["version"])
    _print_json({
        key: document[key]
        for key in (
            "schema_version",
            "game_version",
            "source_count",
            "definition_count",
            "item_count",
            "duplicate_identity_count",
            "items_with_world_model",
            "world_models_resolved",
            "world_models_unresolved",
            "item_types",
        )
    } | {"output": str(output)})
    return 0


def command_assets_index_map_usage(args: argparse.Namespace) -> int:
    report = inspect()
    install = report["game"]["install_path"]
    if not report["game"]["installed"] or not install:
        raise UserError("Project Zomboid is not installed")
    version = report["game"]["version"]
    root = LOCAL / "assets" / f"pz-{version}"
    textures = args.textures or root / "Tiles2x-texture-index.json"
    maps = args.maps or (
        Path(install) / "Project Zomboid.app" / "Contents" / "Java" / "media" / "maps"
    )
    output = args.output or root / "map-header-usage.json"
    for label, path in (("texture index", textures), ("map directory", maps)):
        if not path.exists():
            raise UserError(f"{label} is absent: {path}")
    document = index_map_header_usage(
        maps, textures, output, game_version=version)
    _print_json({
        key: document[key]
        for key in (
            "schema_version",
            "game_version",
            "source_header_count",
            "source_bytes",
            "referenced_identity_count",
        )
    } | {"output": str(output)})
    return 0


def command_assets_audit_coverage(args: argparse.Namespace) -> int:
    report = inspect()
    version = report["game"]["version"]
    root = LOCAL / "assets" / f"pz-{version}"
    geometry = args.geometry or root / "tile-geometry.json"
    textures = args.textures or root / "Tiles2x-texture-index.json"
    models = args.models or root / "model-index.json"
    definitions = args.definitions or root / "tile-definitions.json"
    depth_surfaces = args.depth_surfaces or root / "roof-depth-surfaces.json"
    prop_surfaces = args.prop_surfaces or root / "prop-depth-surfaces.json"
    items = args.items or root / "item-definitions.json"
    map_usage = args.map_usage or root / "map-header-usage.json"
    output = args.output or LOCAL / "reports" / f"asset-coverage-pz-{version}.json"
    for label, path in (("geometry", geometry), ("texture", textures), ("model", models),
                        ("tile-definition", definitions), ("roof depth-surface", depth_surfaces),
                        ("prop depth-surface", prop_surfaces),
                        ("item-definition", items), ("map-header usage", map_usage)):
        if not path.is_file():
            raise UserError(f"{label} index is absent: {path}")
    document = write_coverage(
        geometry,
        textures,
        models,
        output,
        definitions,
        depth_surfaces,
        items,
        map_usage,
        prop_surfaces,
    )
    _print_json(document["summary"] | {"output": str(output)})
    return 0


def command_assets_audit_scene(args: argparse.Namespace) -> int:
    report = inspect()
    version = report["game"]["version"]
    coverage = args.coverage or LOCAL / "reports" / f"asset-coverage-pz-{version}.json"
    output = args.output or LOCAL / "reports" / f"scene-coverage-pz-{version}.json"
    for label, path in (("scene", args.scene), ("coverage", coverage)):
        if not path.is_file():
            raise UserError(f"{label} report is absent: {path}")
    document = write_scene_coverage(args.scene, coverage, output)
    _print_json(document["summary"] | {"output": str(output)})
    return 0


def command_assets_extract_sprite(args: argparse.Namespace) -> int:
    output = args.output or (LOCAL / "assets" / "extracted" / args.sprite)
    _print_json(extract_sprite_page(args.index, args.sprite, output))
    return 0


def command_run_create(args: argparse.Namespace) -> int:
    run = create_run(args.label, inspect())
    print(run.name)
    return 0


def command_run_add(args: argparse.Namespace) -> int:
    destination = add_evidence(args.run, args.baseline, args.kind, args.file.expanduser())
    print(destination)
    return 0


def command_run_add_metric(args: argparse.Namespace) -> int:
    values = {
        "baseline": args.baseline,
        "phase": args.phase,
        "game_fps": args.game_fps,
        "capture_fps": args.capture_fps,
        "completed_neural_fps": args.completed_neural_fps,
        "display_hz": args.display_hz,
        "processing_ms_p50": args.processing_ms_p50,
        "processing_ms_p95": args.processing_ms_p95,
        "dropped_frames": args.dropped_frames,
        "input_to_photon_ms": args.input_to_photon_ms,
        "state_age_ms": args.state_age_ms,
        "memory_pressure": args.memory_pressure,
        "fallback_current_frame_validated": args.fallback_current_frame_validated,
        "gameplay_controllable": args.gameplay_controllable,
        "important_state_preserved": args.important_state_preserved,
        "method": args.method,
        "notes": args.notes,
    }
    print(add_metric(args.run, values))
    return 0


def command_run_finalize(args: argparse.Namespace) -> int:
    manifest = finalize_run(args.run, args.checkpoint, args.owner_accepted)
    _print_json(manifest)
    return 0


def command_deployment_before(args: argparse.Namespace) -> int:
    if not args.approve_external_write:
        raise UserError("external deployment snapshot requires --approve-external-write")
    print(record_before(args.destination, args.owner))
    return 0


def command_deployment_installed(args: argparse.Namespace) -> int:
    _print_json(record_installed(args.manifest))
    return 0


def command_deployment_cleanup(args: argparse.Namespace) -> int:
    print(deployment_cleanup(args.manifest, confirmed=args.confirm))
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="pzfps", description="First-experiment harness for PZ Neural View")
    parser.add_argument("--version", action="version", version=__version__)
    commands = parser.add_subparsers(dest="command", required=True)

    doctor = commands.add_parser("doctor", help="inspect the environment without changing Steam, PZ, mods or saves")
    doctor.add_argument("--write", action="store_true", help="also store the JSON report under .local/reports")
    doctor.set_defaults(func=command_doctor)

    upstream = commands.add_parser("upstream")
    upstream_commands = upstream.add_subparsers(dest="upstream_command", required=True)
    fetch = upstream_commands.add_parser("fetch", help="clone and detach the pinned overlay under .local")
    fetch.set_defaults(func=command_upstream_fetch)
    verify = upstream_commands.add_parser("verify", help="run the upstream model-independent source checks")
    verify.add_argument("--force-unsupported-host", action="store_true")
    verify.set_defaults(func=command_upstream_verify)

    model = commands.add_parser("model")
    model_commands = model.add_subparsers(dest="model_command", required=True)
    model_inspect = model_commands.add_parser("inspect", help="hash a user-supplied model source")
    model_inspect.add_argument("path", type=Path)
    model_inspect.set_defaults(func=command_model_inspect)
    prepare = model_commands.add_parser("prepare", help="stage and prepare a model with the upstream script")
    prepare.add_argument("path", type=Path)
    prepare.add_argument("--allow-unverified-model", action="store_true")
    prepare.set_defaults(func=command_model_prepare)

    overlay = commands.add_parser("overlay")
    overlay_commands = overlay.add_subparsers(dest="overlay_command", required=True)
    build = overlay_commands.add_parser("build")
    build.set_defaults(func=command_overlay_build)
    launch = overlay_commands.add_parser("launch")
    launch.add_argument("--run", required=True)
    launch.set_defaults(func=command_overlay_launch)
    stop = overlay_commands.add_parser("stop")
    stop.set_defaults(func=command_overlay_stop)

    game = commands.add_parser("game")
    game_commands = game.add_subparsers(dest="game_command", required=True)
    game_launch = game_commands.add_parser("launch")
    game_launch.add_argument("--run", required=True)
    game_launch.add_argument("--acknowledge-steam-writes", action="store_true")
    game_launch.set_defaults(func=command_game_launch)
    game_stage_isolated = game_commands.add_parser(
        "stage-isolated",
        help="stage audited bridge mods and config entirely beneath .local",
    )
    game_stage_isolated.set_defaults(func=command_game_stage_isolated)
    game_launch_isolated = game_commands.add_parser(
        "launch-isolated",
        help="launch PZ directly with the project-local cache and audited bridge",
    )
    game_launch_isolated.set_defaults(func=command_game_launch_isolated)
    game_launch_app_isolated = game_commands.add_parser(
        "launch-app-isolated",
        help="launch exactly one identifiable local PZ app using the isolated profile",
    )
    game_launch_app_isolated.set_defaults(func=command_game_launch_app_isolated)
    game_status_isolated = game_commands.add_parser("status-isolated")
    game_status_isolated.set_defaults(func=command_game_status_isolated)
    game_stop_isolated = game_commands.add_parser("stop-isolated")
    game_stop_isolated.set_defaults(func=command_game_stop_isolated)

    assets = commands.add_parser("assets")
    asset_commands = assets.add_subparsers(dest="asset_command", required=True)
    geometry = asset_commands.add_parser(
        "index-geometry",
        help="compile installed B42 tile primitives into a project-local renderer registry",
    )
    geometry.add_argument("--output", type=Path)
    geometry.set_defaults(func=command_assets_index_geometry)
    definitions = asset_commands.add_parser(
        "index-definitions",
        help="index installed tile properties such as roof groups and attachment roles",
    )
    definitions.add_argument("--output", type=Path)
    definitions.set_defaults(func=command_assets_index_definitions)
    roof_surfaces = asset_commands.add_parser(
        "compile-roof-surfaces",
        help="derive strict planar roof faces from installed PZ depth textures",
    )
    roof_surfaces.add_argument("--definitions", type=Path)
    roof_surfaces.add_argument("--textures", type=Path)
    roof_surfaces.add_argument("--map-usage", type=Path)
    roof_surfaces.add_argument("--output", type=Path)
    roof_surfaces.set_defaults(func=command_assets_compile_roof_surfaces)
    prop_surfaces = asset_commands.add_parser(
        "compile-prop-surfaces",
        help="derive source-alpha-masked furniture and fixture surfaces from installed depth textures",
    )
    prop_surfaces.add_argument("--definitions", type=Path)
    prop_surfaces.add_argument("--textures", type=Path)
    prop_surfaces.add_argument("--map-usage", type=Path)
    prop_surfaces.add_argument("--output", type=Path)
    prop_surfaces.add_argument("--map-referenced-only", action="store_true")
    prop_surfaces.set_defaults(func=command_assets_compile_prop_surfaces)
    textures = asset_commands.add_parser(
        "index-textures",
        help="index installed PZ texture-pack metadata without extracting all game art",
    )
    textures.add_argument("--pack", default="Tiles2x.pack")
    textures.add_argument("--output", type=Path)
    textures.set_defaults(func=command_assets_index_textures)
    models = asset_commands.add_parser(
        "index-models",
        help="index installed PZ model identities, meshes, textures and world attachments",
    )
    models.add_argument("--output", type=Path)
    models.set_defaults(func=command_assets_index_models)
    items = asset_commands.add_parser(
        "index-items",
        help="index every installed PZ item script and its declared world model",
    )
    items.add_argument("--models", type=Path)
    items.add_argument("--output", type=Path)
    items.set_defaults(func=command_assets_index_items)
    map_usage = asset_commands.add_parser(
        "index-map-usage",
        help="index exact atlas identities named by installed binary map headers",
    )
    map_usage.add_argument("--textures", type=Path)
    map_usage.add_argument("--maps", type=Path)
    map_usage.add_argument("--output", type=Path)
    map_usage.set_defaults(func=command_assets_index_map_usage)
    coverage = asset_commands.add_parser(
        "audit-coverage",
        help="join indexed tiles, atlas sprites, models and item scripts with honest renderer coverage states",
    )
    coverage.add_argument("--geometry", type=Path)
    coverage.add_argument("--textures", type=Path)
    coverage.add_argument("--models", type=Path)
    coverage.add_argument("--definitions", type=Path)
    coverage.add_argument("--depth-surfaces", type=Path)
    coverage.add_argument("--prop-surfaces", type=Path)
    coverage.add_argument("--items", type=Path)
    coverage.add_argument("--map-usage", type=Path)
    coverage.add_argument("--output", type=Path)
    coverage.set_defaults(func=command_assets_audit_coverage)
    scene_coverage = asset_commands.add_parser(
        "audit-scene",
        help="join every object in a captured scene to the installed renderer coverage corpus",
    )
    scene_coverage.add_argument("--scene", type=Path, required=True)
    scene_coverage.add_argument("--coverage", type=Path)
    scene_coverage.add_argument("--output", type=Path)
    scene_coverage.set_defaults(func=command_assets_audit_scene)
    extract_sprite = asset_commands.add_parser(
        "extract-sprite",
        help="extract the one atlas page containing a named sprite",
    )
    extract_sprite.add_argument("sprite")
    extract_sprite.add_argument("--index", type=Path, required=True)
    extract_sprite.add_argument("--output", type=Path)
    extract_sprite.set_defaults(func=command_assets_extract_sprite)

    run = commands.add_parser("run")
    run_commands = run.add_subparsers(dest="run_command", required=True)
    run_create = run_commands.add_parser("create")
    run_create.add_argument("--label", default="first-experiment")
    run_create.set_defaults(func=command_run_create)
    run_add = run_commands.add_parser("add-evidence")
    run_add.add_argument("--run", required=True)
    run_add.add_argument("--baseline", choices=["game-only", "first-person", "neural"], required=True)
    run_add.add_argument("--kind", choices=["source", "enhanced", "log", "screenshot"], required=True)
    run_add.add_argument("--file", type=Path, required=True)
    run_add.set_defaults(func=command_run_add)
    run_metric = run_commands.add_parser("add-metric")
    run_metric.add_argument("--run", required=True)
    run_metric.add_argument("--baseline", choices=["game-only", "first-person", "neural"], required=True)
    run_metric.add_argument("--phase", choices=["warmup", "steady"], required=True)
    run_metric.add_argument("--game-fps", type=float, default="")
    run_metric.add_argument("--capture-fps", type=float, default="")
    run_metric.add_argument("--completed-neural-fps", type=float, default="")
    run_metric.add_argument("--display-hz", type=float, default="")
    run_metric.add_argument("--processing-ms-p50", type=float, default="")
    run_metric.add_argument("--processing-ms-p95", type=float, default="")
    run_metric.add_argument("--dropped-frames", type=int, default="")
    run_metric.add_argument("--input-to-photon-ms", type=float, default="")
    run_metric.add_argument("--state-age-ms", type=float, default="")
    run_metric.add_argument("--memory-pressure", default="")
    run_metric.add_argument("--fallback-current-frame-validated", action="store_true")
    run_metric.add_argument("--gameplay-controllable", action="store_true")
    run_metric.add_argument("--important-state-preserved", action="store_true")
    run_metric.add_argument("--method", required=True)
    run_metric.add_argument("--notes", default="")
    run_metric.set_defaults(func=command_run_add_metric)
    run_finalize = run_commands.add_parser("finalize")
    run_finalize.add_argument("--run", required=True)
    run_finalize.add_argument("--checkpoint", choices=sorted(CHECKPOINTS), required=True)
    run_finalize.add_argument("--owner-accepted", action="store_true")
    run_finalize.set_defaults(func=command_run_finalize)

    deployment = commands.add_parser("deployment")
    deployment_commands = deployment.add_subparsers(dest="deployment_command", required=True)
    before = deployment_commands.add_parser("record-before")
    before.add_argument("--destination", type=Path, required=True)
    before.add_argument("--owner", choices=["project", "steam-workshop"], required=True)
    before.add_argument("--approve-external-write", action="store_true")
    before.set_defaults(func=command_deployment_before)
    installed = deployment_commands.add_parser("record-installed")
    installed.add_argument("--manifest", type=Path, required=True)
    installed.set_defaults(func=command_deployment_installed)
    cleanup_parser = deployment_commands.add_parser("cleanup")
    cleanup_parser.add_argument("--manifest", type=Path, required=True)
    cleanup_parser.add_argument("--confirm", action="store_true")
    cleanup_parser.set_defaults(func=command_deployment_cleanup)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return int(args.func(args))
    except UserError as error:
        parser.error(str(error))
    except ValueError as error:
        parser.error(str(error))
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
