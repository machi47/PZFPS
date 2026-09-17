from __future__ import annotations

import json
import shutil
from pathlib import Path
from typing import Any

from .common import LOCAL, ROOT, now_utc, sha256_tree, timestamp_id, write_json


def _validate_destination(destination: Path) -> Path:
    resolved = destination.expanduser().resolve()
    forbidden = {Path("/"), Path.home().resolve(), ROOT.resolve(), LOCAL.resolve()}
    if resolved in forbidden or len(resolved.parts) < 5:
        raise ValueError(f"deployment destination is too broad: {resolved}")
    return resolved


def record_before(destination: Path, owner: str) -> Path:
    resolved = _validate_destination(destination)
    record_id = f"deployment-{timestamp_id()}"
    record_dir = LOCAL / "deployments" / record_id
    record_dir.mkdir(parents=True, exist_ok=False)
    existed = resolved.exists()
    before_hash = sha256_tree(resolved) if existed else "absent"
    backup: str | None = None
    if existed:
        backup_path = record_dir / "backup"
        if resolved.is_dir():
            shutil.copytree(resolved, backup_path, symlinks=True)
        else:
            shutil.copy2(resolved, backup_path)
        backup = str(backup_path)
    record: dict[str, Any] = {
        "schema_version": 1,
        "recorded_at": now_utc(),
        "destination": str(resolved),
        "owner": owner,
        "existed_before": existed,
        "before_hash": before_hash,
        "backup": backup,
        "installed_hash": None,
        "rollback": (
            "unsubscribe through Steam; never delete Workshop content directly"
            if owner == "steam-workshop"
            else ("restore backup" if existed else "remove project-owned unchanged deployment")
        ),
    }
    manifest = record_dir / "manifest.json"
    write_json(manifest, record)
    return manifest


def record_installed(manifest_path: Path) -> dict[str, Any]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    destination = _validate_destination(Path(manifest["destination"]))
    if not destination.exists():
        raise ValueError("deployment destination is absent")
    manifest["installed_hash"] = sha256_tree(destination)
    manifest["installed_recorded_at"] = now_utc()
    write_json(manifest_path, manifest)
    return manifest


def cleanup(manifest_path: Path, *, confirmed: bool) -> str:
    if not confirmed:
        raise ValueError("cleanup requires --confirm")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("owner") != "project":
        raise ValueError("non-project deployment must be removed by its owner (for Workshop items, unsubscribe in Steam)")
    destination = _validate_destination(Path(manifest["destination"]))
    installed_hash = manifest.get("installed_hash")
    if not installed_hash:
        raise ValueError("installed hash was never recorded")
    current_hash = sha256_tree(destination)
    if current_hash != installed_hash:
        raise ValueError("deployment changed after installation; refusing to overwrite user edits")
    if manifest["existed_before"]:
        backup = Path(manifest["backup"])
        if not backup.exists():
            raise ValueError("backup is missing")
        if destination.is_dir():
            shutil.rmtree(destination)
            shutil.copytree(backup, destination, symlinks=True)
        else:
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(backup, destination)
        action = "restored unchanged pre-deployment backup"
    else:
        if destination.is_dir():
            shutil.rmtree(destination)
        elif destination.exists():
            destination.unlink()
        action = "removed unchanged project-owned deployment"
    manifest["cleaned_at"] = now_utc()
    manifest["cleanup_action"] = action
    write_json(manifest_path, manifest)
    return action
