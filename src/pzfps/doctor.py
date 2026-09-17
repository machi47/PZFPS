from __future__ import annotations

import os
import platform
import re
import shutil
import subprocess
from pathlib import Path
from typing import Any

from .common import LOCAL, ROOT, command, first_line, load_config, now_utc, sha256_file


STEAM_DEFAULT = Path.home() / "Library" / "Application Support" / "Steam"


def _quoted_pairs(text: str) -> list[tuple[str, str]]:
    return re.findall(r'^\s*"([^"]+)"\s+"([^"]*)"', text, flags=re.MULTILINE)


def steam_libraries(steam_root: Path = STEAM_DEFAULT) -> list[Path]:
    candidates: list[Path] = []
    vdf = steam_root / "steamapps" / "libraryfolders.vdf"
    if vdf.is_file():
        for key, value in _quoted_pairs(vdf.read_text(encoding="utf-8", errors="replace")):
            if key == "path":
                candidates.append(Path(value.replace("\\\\", "\\")))
    if steam_root not in candidates:
        candidates.insert(0, steam_root)
    unique: list[Path] = []
    for candidate in candidates:
        if candidate not in unique:
            unique.append(candidate)
    return unique


def _manifest(path: Path) -> dict[str, str]:
    if not path.is_file():
        return {}
    return dict(_quoted_pairs(path.read_text(encoding="utf-8", errors="replace")))


def _game_version(jar: Path) -> dict[str, str | None]:
    result: dict[str, str | None] = {"version": None, "embedded_revision": None}
    if not jar.is_file() or shutil.which("javap") is None:
        return result
    try:
        inspected = command(
            ["javap", "-classpath", str(jar), "-c", "-p", "zombie.core.Core"],
            timeout=30,
        )
    except (OSError, subprocess.TimeoutExpired):
        return result
    text = inspected.stdout
    static = re.search(
        r"static \{\};.*?bipush\s+(\d+).*?bipush\s+(\d+).*?GameVersion.*?putstatic.*?gameVersion",
        text,
        flags=re.DOTALL,
    )
    if static:
        result["version"] = f"{static.group(1)}.{static.group(2)}"
    revisions = re.findall(r"// String ([0-9a-f]{10,40})", text)
    if revisions:
        result["embedded_revision"] = revisions[0]
    return result


def _tool(command_name: str, version_args: list[str]) -> dict[str, Any]:
    path = shutil.which(command_name)
    return {
        "available": path is not None,
        "path": path,
        "version": first_line([command_name, *version_args]) if path else None,
    }


def _overlay_state(config: dict[str, Any]) -> dict[str, Any]:
    checkout = LOCAL / "upstream" / "dlss5-macos-overlay"
    state: dict[str, Any] = {
        "path": str(checkout),
        "present": checkout.is_dir(),
        "expected_commit": config["overlay"]["commit"],
        "actual_commit": None,
        "clean": None,
        "model_present": False,
        "app_present": False,
    }
    if checkout.is_dir():
        revision = command(["git", "rev-parse", "HEAD"], cwd=checkout)
        status = command(["git", "status", "--porcelain"], cwd=checkout)
        state["actual_commit"] = revision.stdout.strip() if revision.returncode == 0 else None
        state["clean"] = status.returncode == 0 and not status.stdout.strip()
        model = checkout / "Models" / "NR.dlss"
        state["model_present"] = (
            (model / "manifest.json").is_file()
            and (model / "weights.safetensors").is_file()
        )
        state["app_present"] = (
            checkout / "dist" / "DLSS_5_APPLE_SILICON.app"
        ).is_dir()
    verification = LOCAL / "state" / "upstream-verification.json"
    state["verification_record"] = str(verification) if verification.is_file() else None
    return state


def inspect() -> dict[str, Any]:
    config = load_config()
    macos = platform.mac_ver()[0] or first_line(["sw_vers", "-productVersion"])
    disk = shutil.disk_usage(ROOT)
    git_root_result = command(["git", "rev-parse", "--show-toplevel"], cwd=ROOT)
    git_root = git_root_result.stdout.strip() if git_root_result.returncode == 0 else None
    git_status = None
    if git_root:
        status = command(["git", "status", "--porcelain"], cwd=ROOT)
        git_status = status.stdout.splitlines() if status.returncode == 0 else None

    libraries = steam_libraries()
    app_id = config["game"]["steam_app_id"]
    workshop_id = config["game"]["first_person_mod"]["workshop_id"]
    game: dict[str, Any] = {
        "installed": False,
        "steam_library_paths": [str(item) for item in libraries],
        "manifest_path": None,
        "install_path": None,
        "steam_build_id": None,
        "branch": "default",
        "version": None,
        "embedded_revision": None,
        "launcher_architectures": None,
        "runtime_architecture": None,
        "running": False,
    }
    mod: dict[str, Any] = {
        "workshop_id": workshop_id,
        "mod_id": config["game"]["first_person_mod"]["mod_id"],
        "evaluation_status": config["game"]["first_person_mod"]["evaluation_status"],
        "rejection_reason": config["game"]["first_person_mod"]["rejection_reason"],
        "installed": False,
        "path": None,
        "metadata": None,
        "compatibility_tested": False,
    }
    for library in libraries:
        steamapps = library / "steamapps"
        manifest_path = steamapps / f"appmanifest_{app_id}.acf"
        manifest = _manifest(manifest_path)
        if manifest and not game["installed"]:
            install = steamapps / "common" / manifest.get("installdir", "ProjectZomboid")
            bundle = install / "Project Zomboid.app"
            java_root = bundle / "Contents" / "Java"
            version = _game_version(java_root / "projectzomboid.jar")
            launcher = bundle / "Contents" / "MacOS" / "JavaAppLauncher"
            runtime = bundle / "Contents" / "PlugIns" / "jre-aarch64" / "Contents" / "Home" / "bin" / "java"
            launcher_file = first_line(["file", str(launcher)]) if launcher.is_file() else None
            runtime_file = first_line(["file", str(runtime)]) if runtime.is_file() else None
            game.update(
                {
                    "installed": install.is_dir(),
                    "manifest_path": str(manifest_path),
                    "install_path": str(install),
                    "steam_build_id": manifest.get("buildid"),
                    "branch": manifest.get("betakey", "default"),
                    "version": version["version"],
                    "embedded_revision": version["embedded_revision"],
                    "launcher_architectures": launcher_file,
                    "runtime_architecture": runtime_file,
                }
            )
        candidate = steamapps / "workshop" / "content" / app_id / workshop_id
        if candidate.is_dir():
            mod["installed"] = True
            mod["path"] = str(candidate)
            mod_info = next(candidate.rglob("mod.info"), None)
            if mod_info:
                mod["metadata"] = dict(
                    line.split("=", 1)
                    for line in mod_info.read_text(encoding="utf-8", errors="replace").splitlines()
                    if "=" in line and not line.lstrip().startswith("#")
                )

    processes = command(["pgrep", "-ifl", "Project Zomboid|projectzomboid"], timeout=10)
    game["running"] = processes.returncode == 0 and bool(processes.stdout.strip())

    tools = {
        "git": _tool("git", ["--version"]),
        "xcodebuild": _tool("xcodebuild", ["-version"]),
        "swift": _tool("swift", ["--version"]),
        "clang": _tool("clang", ["--version"]),
        "cmake": _tool("cmake", ["--version"]),
        "ninja": _tool("ninja", ["--version"]),
        "python3": _tool("python3", ["--version"]),
    }
    xcode_select = first_line(["xcode-select", "-p"])
    metal = first_line(["xcrun", "--find", "metal"])
    metal_version = first_line(["xcrun", "metal", "--version"]) if metal else None
    overlay = _overlay_state(config)
    blockers: list[str] = []
    required_os = int(config["overlay"]["minimum_macos_major"])
    actual_os = int(macos.split(".")[0]) if macos and macos.split(".")[0].isdigit() else 0
    if git_root != str(ROOT):
        blockers.append("workspace is not a Git worktree rooted at the project")
    if actual_os < required_os:
        blockers.append(f"macOS {required_os}+ required by overlay; found {macos or 'unknown'}")
    swift_text = str(tools["swift"]["version"] or "")
    swift_match = re.search(r"Swift version (\d+(?:\.\d+)+)", swift_text)
    swift_version = swift_match.group(1) if swift_match else None
    effective_swift = config["overlay"]["effective_minimum_swift"]
    if not swift_version or tuple(int(part) for part in swift_version.split(".")) < tuple(
        int(part) for part in effective_swift.split(".")
    ):
        blockers.append(
            f"Swift {effective_swift}+ required by pinned MLX Swift package; found {swift_version or 'unknown'}"
        )
    if not game["installed"]:
        blockers.append("Project Zomboid is not installed in a discovered Steam library")
    elif not str(game["version"] or "").startswith("42."):
        blockers.append(f"installed Project Zomboid is not verified as Build 42 (found {game['version']})")
    if mod["evaluation_status"] == "rejected":
        blockers.append("no acceptable released first-person implementation is available; the Lua/UI raycaster candidate is rejected")
    elif not mod["installed"]:
        blockers.append(f"first-person Workshop item {workshop_id} is not installed")
    if overlay["actual_commit"] != overlay["expected_commit"]:
        blockers.append("pinned overlay checkout is absent or at the wrong revision")
    if not overlay["model_present"]:
        blockers.append("prepared NR.dlss model is absent")

    return {
        "schema_version": 1,
        "recorded_at": now_utc(),
        "project": {
            "root": str(ROOT),
            "git_root": git_root,
            "git_status": git_status,
        },
        "machine": {
            "macos": macos,
            "kernel": platform.platform(),
            "architecture": platform.machine(),
            "chip": first_line(["sysctl", "-n", "machdep.cpu.brand_string"]),
            "memory_bytes": int(first_line(["sysctl", "-n", "hw.memsize"]) or 0),
            "disk_total_bytes": disk.total,
            "disk_free_bytes": disk.free,
        },
        "toolchain": {
            **tools,
            "effective_swift_version": swift_version,
            "effective_minimum_swift": effective_swift,
            "xcode_select": xcode_select,
            "metal": {"available": metal is not None, "path": metal, "version": metal_version},
        },
        "game": game,
        "first_person_mod": mod,
        "overlay": overlay,
        "model": {
            "expected_source_filename": config["model"]["source_filename"],
            "expected_source_file_version": config["model"]["source_file_version"],
            "validated_source_sha256": config["model"]["validated_source_sha256"],
            "local_source_candidates": [],
        },
        "blockers": blockers,
        "readiness": {
            "offline_appearance": overlay["model_present"] and actual_os >= required_os,
            "live_overlay": overlay["app_present"] and actual_os >= required_os,
            "accepted_live_first_person": False,
        },
    }


def write_report(report: dict[str, Any]) -> Path:
    from .common import timestamp_id, write_json

    destination = LOCAL / "reports" / f"doctor-{timestamp_id()}.json"
    write_json(destination, report)
    return destination


def model_identity(path: Path) -> dict[str, Any]:
    config = load_config()
    digest = sha256_file(path)
    expected = config["model"]["validated_source_sha256"]
    return {
        "path": str(path.resolve()),
        "size_bytes": path.stat().st_size,
        "sha256": digest,
        "matches_validated_source": digest == expected,
        "expected_sha256": expected,
        "declared_expected_file_version": config["model"]["source_file_version"],
        "version_verified": digest == expected,
        "note": config["model"]["rights_note"],
    }
