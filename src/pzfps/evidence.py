from __future__ import annotations

import csv
import json
import re
import shutil
from pathlib import Path
from typing import Any

from .common import LOCAL, now_utc, sha256_file, timestamp_id, write_json


BASELINES = {"game-only", "first-person", "neural"}
KINDS = {"source", "enhanced", "log", "screenshot"}
CHECKPOINTS = {
    "NOT RUN",
    "SOURCE CHECKED",
    "BYPASS WORKING",
    "OFFLINE APPEARANCE TESTED",
    "LIVE OVERLAY WORKING",
    "LIVE FIRST-PERSON ACCEPTED",
}
METRIC_COLUMNS = [
    "timestamp_utc",
    "baseline",
    "phase",
    "game_fps",
    "capture_fps",
    "completed_neural_fps",
    "display_hz",
    "processing_ms_p50",
    "processing_ms_p95",
    "dropped_frames",
    "input_to_photon_ms",
    "state_age_ms",
    "memory_pressure",
    "fallback_current_frame_validated",
    "gameplay_controllable",
    "important_state_preserved",
    "method",
    "notes",
]


def _safe_label(value: str) -> str:
    label = re.sub(r"[^a-zA-Z0-9_-]+", "-", value.strip()).strip("-").lower()
    return label[:48] or "experiment"


def runs_root() -> Path:
    return LOCAL / "runs"


def resolve_run(run_id: str) -> Path:
    candidate = (runs_root() / run_id).resolve()
    if candidate.parent != runs_root().resolve() or not candidate.is_dir():
        raise ValueError(f"unknown run: {run_id}")
    return candidate


def create_run(label: str, doctor_report: dict[str, Any]) -> Path:
    run_id = f"{timestamp_id()}-{_safe_label(label)}"
    root = runs_root() / run_id
    for baseline in sorted(BASELINES):
        for kind in ("source", "enhanced", "screenshots"):
            (root / "captures" / baseline / kind).mkdir(parents=True, exist_ok=False)
    (root / "logs").mkdir(parents=True)
    (root / "metrics").mkdir(parents=True)
    with (root / "metrics" / "frames.csv").open("w", newline="", encoding="utf-8") as handle:
        csv.writer(handle).writerow(METRIC_COLUMNS)
    manifest = {
        "schema_version": 1,
        "run_id": run_id,
        "created_at": now_utc(),
        "checkpoint": "NOT RUN",
        "settings": {
            "route": [
                "slow look-around",
                "quick turn",
                "approach wall",
                "open and close door",
                "cross threshold",
                "view same furniture from another side",
                "open inventory",
                "observe movement and attack feedback with controlled enemy",
            ]
        },
        "doctor": doctor_report,
        "evidence": [],
    }
    write_json(root / "manifest.json", manifest)
    (root / "observations.md").write_text(
        "# Observations\n\n"
        "Record geometry hallucination, temporal drift, enemies, UI readability, "
        "combat feedback, responsiveness, and the latency method.\n",
        encoding="utf-8",
    )
    return root


def add_evidence(run_id: str, baseline: str, kind: str, source: Path) -> Path:
    if baseline not in BASELINES:
        raise ValueError(f"invalid baseline: {baseline}")
    if kind not in KINDS:
        raise ValueError(f"invalid evidence kind: {kind}")
    if not source.is_file():
        raise ValueError(f"evidence file does not exist: {source}")
    run = resolve_run(run_id)
    folder = "screenshots" if kind == "screenshot" else kind
    if kind == "log":
        destination_dir = run / "logs"
    else:
        destination_dir = run / "captures" / baseline / folder
    destination = destination_dir / source.name
    if destination.exists():
        raise ValueError(f"refusing to overwrite evidence: {destination}")
    shutil.copy2(source, destination)
    manifest_path = run / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["evidence"].append(
        {
            "added_at": now_utc(),
            "baseline": baseline,
            "kind": kind,
            "path": str(destination.relative_to(run)),
            "sha256": sha256_file(destination),
            "size_bytes": destination.stat().st_size,
        }
    )
    write_json(manifest_path, manifest)
    return destination


def add_metric(run_id: str, values: dict[str, Any]) -> Path:
    baseline = str(values.get("baseline", ""))
    if baseline not in BASELINES:
        raise ValueError(f"invalid baseline: {baseline}")
    if values.get("phase") not in {"warmup", "steady"}:
        raise ValueError("phase must be warmup or steady")
    values = {column: values.get(column, "") for column in METRIC_COLUMNS}
    values["timestamp_utc"] = values["timestamp_utc"] or now_utc()
    metrics = resolve_run(run_id) / "metrics" / "frames.csv"
    with metrics.open("a", newline="", encoding="utf-8") as handle:
        csv.DictWriter(handle, fieldnames=METRIC_COLUMNS).writerow(values)
    return metrics


def finalize_run(run_id: str, checkpoint: str, owner_accepted: bool) -> dict[str, Any]:
    if checkpoint not in CHECKPOINTS:
        raise ValueError(f"invalid checkpoint: {checkpoint}")
    run = resolve_run(run_id)
    manifest_path = run / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if checkpoint == "LIVE FIRST-PERSON ACCEPTED" and not owner_accepted:
        raise ValueError("owner acceptance is required for LIVE FIRST-PERSON ACCEPTED")
    if checkpoint == "SOURCE CHECKED":
        verification = LOCAL / "state" / "upstream-verification.json"
        if not verification.is_file() or json.loads(verification.read_text(encoding="utf-8")).get("returncode") != 0:
            raise ValueError("a successful upstream source-verification record is required")
    if checkpoint in {"OFFLINE APPEARANCE TESTED", "LIVE OVERLAY WORKING", "LIVE FIRST-PERSON ACCEPTED"}:
        kinds = {entry["kind"] for entry in manifest["evidence"]}
        if not {"source", "enhanced"}.issubset(kinds):
            raise ValueError("source and enhanced evidence are both required")
    if checkpoint in {"LIVE OVERLAY WORKING", "LIVE FIRST-PERSON ACCEPTED"}:
        metrics = run / "metrics" / "frames.csv"
        with metrics.open(encoding="utf-8") as handle:
            rows = list(csv.DictReader(handle))
        if not rows:
            raise ValueError("at least one measured metrics row is required")
        neural_rows = [
            row for row in rows
            if row["baseline"] == "neural" and row["phase"] == "steady" and row["completed_neural_fps"]
        ]
        if not neural_rows:
            raise ValueError("a steady neural row with measured completed_neural_fps is required")
        if checkpoint == "LIVE FIRST-PERSON ACCEPTED":
            responsive = any(
                float(row["completed_neural_fps"]) >= 30.0
                or row["fallback_current_frame_validated"].lower() in {"true", "yes", "1"}
                for row in neural_rows
            )
            fidelity = any(
                row["gameplay_controllable"].lower() in {"true", "yes", "1"}
                and row["important_state_preserved"].lower() in {"true", "yes", "1"}
                for row in neural_rows
            )
            if not responsive:
                raise ValueError("acceptance needs >=30 completed neural FPS or a validated current-frame fallback")
            if not fidelity:
                raise ValueError("acceptance needs controllability and important-state preservation recorded")
    manifest["checkpoint"] = checkpoint
    manifest["finalized_at"] = now_utc()
    manifest["owner_accepted"] = bool(owner_accepted)
    write_json(manifest_path, manifest)
    return manifest
