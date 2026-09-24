from __future__ import annotations

import json
import os
import plistlib
import re
import shutil
import signal
import subprocess
import time
from pathlib import Path
from typing import Any

from .common import LOCAL, ROOT, now_utc, sha256_file, timestamp_id, write_json


class RuntimeError(ValueError):
    pass


RUNTIME_ROOT = LOCAL / "pz-runtime"
PZ_CACHE_PARENT = RUNTIME_ROOT / "user-cache"
PZ_CACHE = PZ_CACHE_PARENT / "Zomboid"
MODS = PZ_CACHE / "mods"
ZB_CONFIG = RUNTIME_ROOT / "zombie-buddy"
STATE = LOCAL / "state" / "isolated-game.json"
LAUNCH_APP = RUNTIME_ROOT / "PZFPS Isolated.app"
ASSET_REGISTRY = LOCAL / "assets" / "pz-42.20" / "tile-geometry.json"
SUPPLEMENTAL_ASSET_REGISTRY = LOCAL / "assets" / "pz-42.20" / "roof-depth-surfaces.json"
PROP_SURFACE_REGISTRY = LOCAL / "assets" / "pz-42.20" / "prop-depth-surfaces.json"


def stage(install: Path) -> dict[str, Any]:
    """Build the disposable, project-local PZ mod/cache tree."""
    java_root = _java_root(install)
    game_jar = java_root / "projectzomboid.jar"
    zombie_buddy_source = LOCAL / "upstream" / "ZombieBuddy"
    zombie_buddy_jar = zombie_buddy_source / "java" / "build" / "jdk26" / "libs" / "ZombieBuddy.jar"
    bridge_jar = ROOT / "bridge" / "build" / "libs" / "PZFPSBridge-0.1.0.jar"
    for required in (
        game_jar,
        zombie_buddy_jar,
        bridge_jar,
        ASSET_REGISTRY,
        SUPPLEMENTAL_ASSET_REGISTRY,
        PROP_SURFACE_REGISTRY,
    ):
        if not required.is_file():
            raise RuntimeError(f"required runtime artifact is absent: {required}")

    zombie_buddy_mod = MODS / "ZombieBuddy"
    bridge_mod = MODS / "PZFPSBridge"
    for target in (zombie_buddy_mod, bridge_mod):
        _require_project_local(target)
        if target.exists():
            shutil.rmtree(target)

    (zombie_buddy_mod / "libs").mkdir(parents=True)
    shutil.copytree(zombie_buddy_source / "42", zombie_buddy_mod / "42")
    shutil.copytree(zombie_buddy_source / "common", zombie_buddy_mod / "common")
    shutil.copy2(zombie_buddy_jar, zombie_buddy_mod / "libs" / "ZombieBuddy.jar")

    shutil.copytree(ROOT / "bridge" / "mod" / "42", bridge_mod / "42")
    bridge_destination = bridge_mod / "42" / "media" / "java" / "PZFPSBridge.jar"
    bridge_destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(bridge_jar, bridge_destination)

    mods_file = MODS / "default.txt"
    mods_file.write_text(
        "VERSION = 1,\n"
        "mods\n{\n"
        "    mod = ZombieBuddy,\n"
        "    mod = PZFPSBridge,\n"
        "}\n"
        "maps\n{\n}\n",
        encoding="utf-8",
    )
    # B42 deliberately clears default.txt once per release unless this marker exists.
    (MODS / "reset-mods-42_00.txt").write_text(
        "Project-local disposable profile initialized by PZFPS.\n",
        encoding="utf-8",
    )
    (PZ_CACHE / "pzfps-autotest.txt").write_text(
        "Enabled only in the project-local disposable profile.\n", encoding="utf-8"
    )
    _configure_windowed_profile()

    bridge_hash = sha256_file(bridge_destination)
    _build_launch_app(install, zombie_buddy_mod / "libs" / "ZombieBuddy.jar")
    write_json(
        ZB_CONFIG / "mod_approvals.json",
        {
            "formatVersion": 2,
            "mods": [
                {
                    "id": "PZFPSBridge",
                    "jar_hash": bridge_hash,
                    "decision": True,
                    "time": now_utc(),
                }
            ],
        },
    )
    manifest = {
        "schema_version": 1,
        "staged_at": now_utc(),
        "install": str(install),
        "game_jar_sha256": sha256_file(game_jar),
        "cache_parent": str(PZ_CACHE_PARENT),
        "cache": str(PZ_CACHE),
        "mods_file": str(mods_file),
        "zombie_buddy": {
            "source_commit": _git_revision(zombie_buddy_source),
            "source_jar": str(zombie_buddy_jar),
            "staged_jar": str(zombie_buddy_mod / "libs" / "ZombieBuddy.jar"),
            "sha256": sha256_file(zombie_buddy_mod / "libs" / "ZombieBuddy.jar"),
            "config": str(ZB_CONFIG),
            "policy": "deny-new",
        },
        "bridge": {
            "source_jar": str(bridge_jar),
            "staged_jar": str(bridge_destination),
            "sha256": bridge_hash,
        },
        "renderer": {
            "kind": "single-window OpenGL world replacement",
            "asset_registry": str(ASSET_REGISTRY),
            "asset_registry_sha256": sha256_file(ASSET_REGISTRY),
            "supplemental_asset_registry": str(SUPPLEMENTAL_ASSET_REGISTRY),
            "supplemental_asset_registry_sha256": sha256_file(SUPPLEMENTAL_ASSET_REGISTRY),
            "prop_surface_registry": str(PROP_SURFACE_REGISTRY),
            "prop_surface_registry_sha256": sha256_file(PROP_SURFACE_REGISTRY),
        },
        "launch_app": str(LAUNCH_APP),
    }
    write_json(RUNTIME_ROOT / "staging-manifest.json", manifest)
    return manifest


def launch(install: Path) -> dict[str, Any]:
    manifest_path = RUNTIME_ROOT / "staging-manifest.json"
    if not manifest_path.is_file():
        raise RuntimeError("isolated runtime is not staged; run: bin/pzfps game stage-isolated")
    running_processes = pz_processes()
    if running_processes:
        details = "; ".join(f"pid={item['pid']} {item['command']}" for item in running_processes)
        raise RuntimeError(f"refusing to open another PZ client while one exists: {details}")

    java_root = _java_root(install)
    java = install / "Project Zomboid.app" / "Contents" / "PlugIns" / "jre-aarch64" / "Contents" / "Home" / "bin" / "java"
    agent = MODS / "ZombieBuddy" / "libs" / "ZombieBuddy.jar"
    bridge = MODS / "PZFPSBridge" / "42" / "media" / "java" / "PZFPSBridge.jar"
    for required in (java, java_root / "projectzomboid.jar", agent, bridge):
        if not required.is_file():
            raise RuntimeError(f"launch prerequisite is absent: {required}")

    log = LOCAL / "logs" / f"pz-isolated-{timestamp_id()}.log"
    log.parent.mkdir(parents=True, exist_ok=True)
    temporary = LOCAL / "tmp" / "pz"
    temporary.mkdir(parents=True, exist_ok=True)
    argv = [
        str(java),
        "-Djava.awt.headless=true",
        "--enable-native-access=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
        "-XstartOnFirstThread",
        "-Dzomboid.steam=1",
        "-Dzomboid.znetlog=1",
        f"-Ddeployment.user.cachedir={PZ_CACHE_PARENT}",
        f"-Djava.library.path={java_root}",
        "-Dpzfps.bind=127.0.0.1",
        "-Dpzfps.port=24872",
        "-Dpzfps.chunkRadius=6",
        "-Dpzfps.renderDistanceChunks=24",
        "-Dpzfps.worldHz=4",
        "-Dpzfps.entityHz=30",
        "-Dpzfps.renderer.enabled=true",
        "-Dpzfps.autoContinue=true",
        f"-Dpzfps.assetRegistry={ASSET_REGISTRY}",
        f"-Dpzfps.supplementalAssetRegistry={SUPPLEMENTAL_ASSET_REGISTRY}",
        f"-Dpzfps.propSurfaceRegistry={PROP_SURFACE_REGISTRY}",
        f"-Dpzfps.bridgeJar={bridge}",
        "-Xmx3072m",
        "-XX:+UseZGC",
        "-XX:-OmitStackTraceInFastThrow",
        _agent_option(agent, bridge),
        "-cp",
        "projectzomboid.jar",
        "zombie.gameStates.MainScreenState",
    ]
    environment = os.environ.copy()
    environment["TMPDIR"] = str(temporary)
    environment["DYLD_LIBRARY_PATH"] = str(java_root)
    handle = log.open("ab", buffering=0)
    process = subprocess.Popen(
        argv,
        cwd=java_root,
        env=environment,
        stdout=handle,
        stderr=subprocess.STDOUT,
        start_new_session=True,
    )
    state = {
        "schema_version": 1,
        "started_at": now_utc(),
        "pid": process.pid,
        "process_group": process.pid,
        "executable": str(java.resolve()),
        "cwd": str(java_root),
        "cache": str(PZ_CACHE),
        "log": str(log),
        "argv": argv,
    }
    write_json(STATE, state)
    return state


def launch_app(install: Path) -> dict[str, Any]:
    """Launch the isolated profile as one identifiable macOS application."""
    manifest_path = RUNTIME_ROOT / "staging-manifest.json"
    if not manifest_path.is_file() or not LAUNCH_APP.is_dir():
        raise RuntimeError("isolated runtime app is not staged; run: bin/pzfps game stage-isolated")
    running_processes = pz_processes()
    if running_processes:
        details = "; ".join(f"pid={item['pid']} {item['command']}" for item in running_processes)
        raise RuntimeError(f"refusing to open another PZ client while one exists: {details}")
    executable = LAUNCH_APP / "Contents" / "MacOS" / "JavaAppLauncher"
    result = subprocess.run(["open", "-n", str(LAUNCH_APP)], check=False)
    if result.returncode:
        raise RuntimeError(f"isolated PZ app launch failed with exit {result.returncode}")
    process: dict[str, Any] | None = None
    for _ in range(100):
        time.sleep(0.1)
        process = next(
            (item for item in pz_processes() if str(executable) in item["command"]), None
        )
        if process is not None:
            break
    if process is None:
        raise RuntimeError("isolated PZ app did not produce an identifiable process")
    state = {
        "schema_version": 1,
        "kind": "packaged",
        "started_at": now_utc(),
        "pid": process["pid"],
        "process_group": None,
        "executable": str(executable.resolve()),
        "cwd": str(_java_root(install)),
        "cache": str(PZ_CACHE),
        "log": str(PZ_CACHE / "console.txt"),
        "argv": ["open", "-n", str(LAUNCH_APP)],
    }
    write_json(STATE, state)
    return state


def process_state() -> dict[str, Any]:
    if not STATE.is_file():
        return {"running": False, "reason": "no launch state"}
    state = json.loads(STATE.read_text(encoding="utf-8"))
    pid = int(state["pid"])
    try:
        os.kill(pid, 0)
    except (ProcessLookupError, PermissionError):
        if state.get("kind") == "packaged":
            # LaunchServices can keep a short-lived JavaAppLauncher while the real
            # application process is created several seconds later with a new PID.
            # Keep status and cleanup attached to that successor instead of reporting
            # a false stopped state while the isolated client is still rendering.
            successors = isolated_pz_processes()
            if successors:
                successor = max(successors, key=lambda item: int(item["pid"]))
                return {
                    **state,
                    "recorded_pid": pid,
                    "pid": int(successor["pid"]),
                    "running": True,
                }
        return {**state, "running": False}
    command = subprocess.run(
        ["ps", "-p", str(pid), "-o", "command="],
        capture_output=True,
        text=True,
        check=False,
    )
    command_line = command.stdout.strip()
    executable = str(Path(str(state["executable"])).resolve())
    expected_main = state.get("kind") != "packaged"
    running = command.returncode == 0 and command_line.startswith(executable)
    if expected_main:
        running = running and "zombie.gameStates.MainScreenState" in command_line
    return {**state, "running": bool(running)}


def pz_processes() -> list[dict[str, Any]]:
    """Inventory every PZ client, including packaged and project-launched forms."""
    result = subprocess.run(
        ["ps", "-axo", "pid=,command="], capture_output=True, text=True, check=False
    )
    processes: list[dict[str, Any]] = []
    for line in result.stdout.splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        pid_text, _, command_line = stripped.partition(" ")
        known_bundle = "ProjectZomboid/Project Zomboid.app/Contents/" in command_line
        isolated_bundle = "PZFPS Isolated.app/Contents/MacOS/JavaAppLauncher" in command_line
        if not known_bundle and not isolated_bundle:
            continue
        if "zombie.gameStates.MainScreenState" not in command_line and "JavaAppLauncher" not in command_line:
            continue
        processes.append({"pid": int(pid_text), "command": command_line})
    return processes


def isolated_pz_processes() -> list[dict[str, Any]]:
    """Return only clients that use the project-local disposable runtime."""
    cache_option = f"-Ddeployment.user.cachedir={PZ_CACHE_PARENT}"
    isolated_launcher = "PZFPS Isolated.app/Contents/MacOS/JavaAppLauncher"
    return [
        process
        for process in pz_processes()
        if cache_option in process["command"] or isolated_launcher in process["command"]
    ]


def stop() -> dict[str, Any]:
    state = process_state()
    targets = {process["pid"] for process in isolated_pz_processes()}
    if state.get("running"):
        targets.add(int(state["pid"]))
    if not targets:
        return state
    for pid in sorted(targets):
        try:
            os.kill(pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
    for _ in range(100):
        time.sleep(0.1)
        remaining = {
            process["pid"] for process in isolated_pz_processes() if process["pid"] in targets
        }
        if not remaining:
            state["running"] = False
            state["stopped_at"] = now_utc()
            state["stopped_pids"] = sorted(targets)
            write_json(STATE, state)
            return state
    raise RuntimeError(f"isolated PZ processes did not exit after SIGTERM: {sorted(remaining)}")


def _java_root(install: Path) -> Path:
    return install / "Project Zomboid.app" / "Contents" / "Java"


def _git_revision(repository: Path) -> str:
    result = subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=repository, capture_output=True, text=True, check=False
    )
    return result.stdout.strip() if result.returncode == 0 else "unknown"


def _build_launch_app(install: Path, agent: Path) -> None:
    """Create a small local bundle that references, but never modifies, the Steam install."""
    _require_project_local(LAUNCH_APP)
    original = install / "Project Zomboid.app" / "Contents"
    bridge = MODS / "PZFPSBridge" / "42" / "media" / "java" / "PZFPSBridge.jar"
    if LAUNCH_APP.exists():
        shutil.rmtree(LAUNCH_APP)
    contents = LAUNCH_APP / "Contents"
    macos = contents / "MacOS"
    resources = contents / "Resources"
    macos.mkdir(parents=True)
    resources.mkdir(parents=True)
    shutil.copy2(original / "MacOS" / "JavaAppLauncher", macos / "JavaAppLauncher")
    shutil.copy2(original / "MacOS" / "libsteam_api.dylib", macos / "libsteam_api.dylib")
    shutil.copy2(original / "Resources" / "Project Zomboid.icns", resources / "Project Zomboid.icns")
    shutil.copy2(original / "PkgInfo", contents / "PkgInfo")
    os.symlink(original / "Java", contents / "Java", target_is_directory=True)
    os.symlink(original / "PlugIns", contents / "PlugIns", target_is_directory=True)
    with (original / "Info.plist").open("rb") as handle:
        info = plistlib.load(handle)
    info["CFBundleDisplayName"] = "PZFPS Isolated"
    info["CFBundleName"] = "PZFPSIsolated"
    # Retain the installed bundle identity so automation attaches to this already-running
    # client instead of silently starting the separate Steam bundle.
    info["CFBundleIdentifier"] = "Project Zomboid"
    info["LSMultipleInstancesProhibited"] = True
    options = list(info.get("JVMOptions", []))
    options.extend(
        [
            f"-Ddeployment.user.cachedir={PZ_CACHE_PARENT}",
            f"-Dpzfps.bind=127.0.0.1",
            f"-Dpzfps.port=24872",
            f"-Dpzfps.chunkRadius=6",
            "-Dpzfps.renderDistanceChunks=24",
            f"-Dpzfps.worldHz=4",
            f"-Dpzfps.entityHz=30",
            "-Dpzfps.renderer.enabled=true",
            "-Dpzfps.autoContinue=true",
            f"-Dpzfps.assetRegistry={ASSET_REGISTRY}",
            f"-Dpzfps.supplementalAssetRegistry={SUPPLEMENTAL_ASSET_REGISTRY}",
            f"-Dpzfps.propSurfaceRegistry={PROP_SURFACE_REGISTRY}",
            f"-Dpzfps.bridgeJar={bridge}",
            _agent_option(agent, bridge),
        ]
    )
    info["JVMOptions"] = options
    # PZ validates model/media paths against the canonical working directory. A symlinked
    # app-relative working directory passes JVM startup but is rejected during asset loading.
    info["WorkingDirectory"] = str(original / "Java")
    with (contents / "Info.plist").open("wb") as handle:
        plistlib.dump(info, handle, sort_keys=True)


def _agent_option(agent: Path, bridge: Path) -> str:
    """Load the approved Java mod; it installs and verifies its exact runtime hooks."""
    return f"-javaagent:{agent}=verbosity=1,policy=deny-new,config_dir={ZB_CONFIG}"


def _configure_windowed_profile() -> None:
    """Prevent the disposable profile from switching the owner's desktop display mode."""
    options_path = PZ_CACHE / "options.ini"
    if not options_path.is_file():
        return
    text = options_path.read_text(encoding="utf-8")
    replacements = {
        "fullScreen": "false",
        "borderless": "false",
        "width": "1600",
        "height": "1000",
    }
    for key, value in replacements.items():
        pattern = re.compile(rf"(?m)^{re.escape(key)}=.*$")
        if pattern.search(text):
            text = pattern.sub(f"{key}={value}", text)
        else:
            text += f"\n{key}={value}\n"
    options_path.write_text(text, encoding="utf-8")


def _require_project_local(path: Path) -> None:
    if not path.resolve().is_relative_to(LOCAL.resolve()):
        raise RuntimeError(f"refusing to replace non-project staging path: {path}")
