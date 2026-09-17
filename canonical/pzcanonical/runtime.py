"""Authoritative presentation state and evaluated skeletal skinning.

No gameplay simulation, movement prediction, independent animation selection,
or claims of a ready-to-load PZ hook. This consumes normalized adapter output.
"""
from __future__ import annotations

import copy
from dataclasses import dataclass
import threading
from typing import Any

import numpy as np

from .geometry import finite


class NeedsSnapshot(RuntimeError):
    pass


def transform(value: object) -> np.ndarray:
    matrix = finite(value, (4, 4), "presentation transform")
    if not np.allclose(matrix[3], [0, 0, 0, 1]) or abs(np.linalg.det(matrix[:3, :3])) < 1e-12:
        raise ValueError("presentation transform must be invertible affine")
    return matrix


def _node(value: dict[str, Any]) -> dict[str, Any]:
    if not isinstance(value, dict) or not isinstance(value.get("id"), str) or not value["id"]:
        raise ValueError("node must have a stable source lifecycle ID")
    if not isinstance(value.get("prototype"), str) or not value["prototype"]:
        raise ValueError("node must identify its source prototype")
    transform(value["transform"])
    if value.get("kind") not in ("structure", "object", "door", "actor", "vehicle"):
        raise ValueError("unknown presentation node kind")
    result = copy.deepcopy(value)
    if "pose" in result:
        pose = finite(result["pose"], (None, 4, 4), "evaluated model-space bone matrices")
        if not np.allclose(pose[:, 3], [0, 0, 0, 1]):
            raise ValueError("pose contains nonaffine bone transforms")
    return result


@dataclass(frozen=True)
class Frame:
    session: str
    sequence: int
    nodes: dict[str, dict[str, Any]]
    received_at: float
    age_at_receipt: float

    def age(self, now: float) -> float:
        return self.age_at_receipt + max(0.0, now - self.received_at)


class SceneState:
    """Apply a full snapshot or ordered delta atomically. Gaps never delete doors.

    Single-writer usage is preferred; the lock also makes read/write snapshots
    thread-safe. Returned copies cannot mutate authoritative presentation data.
    """
    def __init__(self):
        self._lock = threading.RLock()
        self._frame: Frame | None = None

    def apply(self, packet: dict[str, Any], received_at: float) -> bool:
        if packet.get("schema_version") != 1 or packet.get("type") not in ("snapshot", "delta"):
            raise ValueError("unsupported normalized scene packet")
        sequence, session = packet.get("sequence"), packet.get("session")
        if type(sequence) is not int or sequence < 0 or not isinstance(session, str) or not session:
            raise ValueError("invalid sequence/session")
        age = float(packet.get("age_at_receipt", 0))
        if not np.isfinite([received_at, age]).all() or age < 0:
            raise ValueError("invalid monotonic receipt time or measured age")
        values = [_node(v) for v in packet.get("upsert", [])]
        ids = [v["id"] for v in values]
        removed = packet.get("remove", [])
        if len(set(ids)) != len(ids) or not isinstance(removed, list) or any(not isinstance(i, str) for i in removed) or set(ids).intersection(removed):
            raise ValueError("duplicate/contradictory node operations")
        with self._lock:
            current = self._frame
            if current is not None and current.session == session and sequence <= current.sequence:
                return False
            if packet["type"] == "delta":
                if current is None or current.session != session or packet.get("base_sequence") != current.sequence or sequence != current.sequence + 1:
                    raise NeedsSnapshot("session/base/sequence gap; request a fresh snapshot without mutating current state")
                next_nodes = copy.deepcopy(current.nodes)
            else:
                if removed:
                    raise ValueError("full snapshot cannot contain removals")
                next_nodes = {}
            for identity in removed:
                next_nodes.pop(identity, None)
            for value in values:
                old = next_nodes.get(value["id"])
                if old is not None and old["prototype"] != value["prototype"]:
                    raise ValueError("prototype changed without a new lifecycle ID")
                next_nodes[value["id"]] = value
            self._frame = Frame(session, sequence, next_nodes, received_at, age)
        return True

    def read(self) -> Frame | None:
        with self._lock:
            return copy.deepcopy(self._frame)


def matrices_from_wire(values: object, layout: str) -> np.ndarray:
    """Matrix order must be established in the actual PZ adapter, never guessed."""
    array = finite(values, (None, 16), "wire matrices")
    if layout not in ("row-major", "column-major"):
        raise ValueError("explicit row-major/column-major wire layout required")
    matrices = array.reshape(-1, 4, 4)
    return matrices if layout == "row-major" else matrices.transpose(0, 2, 1).copy()


def skin_vertices(vertices: object, joints: object, weights: object, evaluated_model_pose: object, inverse_bind: object, world: object) -> np.ndarray:
    """LBS reference for the client's evaluated pose, not a guessed animation.

    The native frontend should perform this same operation on the GPU. Inputs
    are conventional column-vector matrices normalized by the source adapter.
    """
    vertices = finite(vertices, (None, 3), "skin vertices")
    weights = finite(weights, (len(vertices), None), "skin weights")
    joints = np.asarray(joints)
    if joints.shape != weights.shape or not np.issubdtype(joints.dtype, np.integer):
        raise ValueError("skin joint/weight shape or index type mismatch")
    pose = finite(evaluated_model_pose, (None, 4, 4), "evaluated pose")
    binds = finite(inverse_bind, pose.shape, "inverse bind matrices")
    if np.any(weights < 0) or not np.allclose(weights.sum(axis=1), 1, atol=1e-5):
        raise ValueError("skin weights must be nonnegative and sum to one")
    if joints.size == 0 or joints.min() < 0 or joints.max() >= len(pose):
        raise ValueError("skin joint index outside evaluated pose")
    if not np.allclose(pose[:, 3], [0, 0, 0, 1]) or not np.allclose(binds[:, 3], [0, 0, 0, 1]):
        raise ValueError("skin matrices must be affine")
    palette = pose @ binds
    homogeneous = np.column_stack([vertices, np.ones(len(vertices))])
    deformed = np.einsum("nk,nkij,nj->ni", weights, palette[joints], homogeneous)
    return (deformed @ transform(world).T)[:, :3]
