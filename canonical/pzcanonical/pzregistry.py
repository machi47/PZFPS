"""Adapter for src/pzfps/assets.py schema 1, inspected at PZFPS 009c258.

Primitive rotations match WorldMeshBuilder.transform: Rx, then Ry, then Rz,
then source translation. Square-center placement (+.5, level*3, +.5) belongs
in the live instance transform and is deliberately NOT baked into the asset.
"""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np
from scipy.signal import fftconvolve
from scipy.spatial.transform import Rotation
import shapely
from shapely.geometry import Polygon
import trimesh

from .geometry import Camera, Mesh, finite, rasterize
from .pipeline import isolated_sprite, read_json
from .store import canonical_json


def registry_mesh(registry: dict, sprite: str, *, polygon_thickness: float | None = None, cylinder_segments: int = 24) -> Mesh:
    if registry.get("schema_version") != 1 or not registry.get("source_sha256"):
        raise ValueError("expected the verified schema-1 PZ geometry index and its source hash")
    if not 8 <= cylinder_segments <= 128:
        raise ValueError("cylinder tessellation must be in [8,128]")
    if polygon_thickness is not None and (not np.isfinite(polygon_thickness) or polygon_thickness <= 0):
        raise ValueError("polygon visual thickness must be positive")
    record = registry.get("tiles", {}).get(sprite)
    if not record or not record.get("geometry"):
        raise ValueError(f"no source geometry for sprite {sprite!r}; no generic proxy is silently substituted")
    meshes = []
    for source in record["geometry"]:
        kind = source["kind"]
        if kind == "box":
            geometry = Mesh.box(np.array([source["min"], source["max"]]))
        elif kind == "cylinder":
            bottom, top, height = (float(source[k]) for k in ("radius1", "radius2", "height"))
            if not np.isfinite([bottom, top, height]).all() or min(bottom, top) < 0 or max(bottom, top) <= 0 or height <= 0:
                raise ValueError("invalid source cylinder")
            profile = np.array([[0, 0], [bottom, 0], [top, height], [0, height]])
            shape = trimesh.creation.revolve(profile, sections=cylinder_segments)
            # trimesh revolves around Z; source cylinders have vertical Y.
            mapping = np.array([[1, 0, 0], [0, 0, 1], [0, -1, 0]])
            geometry = Mesh(shape.vertices @ mapping.T, shape.faces)
        elif kind == "polygon":
            if polygon_thickness is None:
                raise ValueError("planar source needs an explicit polygon_thickness appearance choice; the game does not supply a back surface")
            polygon = Polygon(finite(source["points"], (None, 2), "source polygon"))
            if not polygon.is_valid or polygon.area <= 1e-12:
                raise ValueError("source polygon is invalid")
            triangles = shapely.constrained_delaunay_triangles(polygon)
            corners = np.array([np.asarray(t.exterior.coords)[:3] for t in triangles.geoms]).reshape(-1, 2)
            if not len(corners):
                raise ValueError("polygon triangulation produced no faces")
            vertices, indices = np.unique(corners, axis=0, return_inverse=True)
            faces = indices.reshape(-1, 3)
            # Extrusion expects positive 2D orientation.
            a, b, c = vertices[faces[:, 0]], vertices[faces[:, 1]], vertices[faces[:, 2]]
            area2 = (b[:, 0] - a[:, 0]) * (c[:, 1] - a[:, 1]) - (b[:, 1] - a[:, 1]) * (c[:, 0] - a[:, 0])
            faces[area2 < 0] = faces[area2 < 0, ::-1]
            shape = trimesh.creation.extrude_triangulation(vertices, faces, polygon_thickness)
            shape.vertices[:, 2] -= polygon_thickness / 2
            matrices = {"XY": np.eye(3), "XZ": np.array([[1, 0, 0], [0, 0, -1], [0, 1, 0]]), "YZ": np.array([[0, 0, 1], [1, 0, 0], [0, 1, 0]])}
            if source["plane"] not in matrices:
                raise ValueError("unknown source polygon plane")
            geometry = Mesh(shape.vertices @ matrices[source["plane"]].T, shape.faces)
        else:
            raise ValueError(f"unsupported source primitive {kind!r}; source geometry is not dropped")
        translation = finite(source["translate"], (3,), "source primitive translation")
        angles = finite(source["rotate_degrees"], (3,), "source primitive rotation")
        matrix = np.eye(4)
        # scipy lowercase xyz is extrinsic: Rz @ Ry @ Rx, matching Java code.
        matrix[:3, :3] = Rotation.from_euler("xyz", angles, degrees=True).as_matrix()
        matrix[:3, 3] = translation
        meshes.append(geometry.transformed(matrix))
    offsets = np.cumsum([0] + [len(mesh.vertices) for mesh in meshes[:-1]])
    return Mesh(np.concatenate([m.vertices for m in meshes]), np.concatenate([m.faces + offset for m, offset in zip(meshes, offsets)]))


def calibrate_anchor(mesh: Mesh, rgba: np.ndarray, horizontal: float, vertical: float, *, minimum_iou: float = .55) -> tuple[Camera, float]:
    """Fit the image anchor to measured silhouette overlap, not sprite dimensions.

    Axis/scales come from an explicit installed-projection profile. Only image
    translation is fitted; world dimensions and orientation are not warped to
    conceal a bad match. Low overlap rejects mismatched fragments/candidates.
    """
    if rgba.ndim != 3 or rgba.shape[2] != 4 or not 0 < minimum_iou <= 1:
        raise ValueError("invalid RGBA source or minimum silhouette IoU")
    height, width = rgba.shape[:2]
    target = rgba[..., 3] >= 250
    if not target.any():
        raise ValueError("source contains no opaque silhouette")
    base = Camera.pz_isometric(width, height, (0, 0), horizontal, vertical)
    projected = base.project(mesh.vertices)
    low = np.floor(projected[:, :2].min(axis=0)) - 2
    high = np.ceil(projected[:, :2].max(axis=0)) + 2
    dimensions = (high - low + 1).astype(int)
    if dimensions.max() > 2048:
        raise ValueError("calibration template exceeds 2048 pixels; verify source scales")
    matrix = base.matrix.copy()
    matrix[:2, 3] = -low
    template_camera = Camera(matrix, int(dimensions[0]), int(dimensions[1]))
    template = rasterize(mesh, template_camera).face >= 0
    intersection = np.rint(fftconvolve(target.astype(float), template[::-1, ::-1].astype(float), mode="full")).clip(min=0)
    denominator = target.sum() + template.sum() - intersection
    overlap = intersection / np.maximum(denominator, 1)
    row, column = np.unravel_index(np.argmax(overlap), overlap.shape)
    score = float(overlap[row, column])
    if score < minimum_iou:
        raise ValueError(f"source silhouette/geometry mismatch: best IoU {score:.4f} < {minimum_iou:.4f}; verify grouping, orientation and source scales")
    shift = np.array([column - template.shape[1] + 1, row - template.shape[0] + 1])
    matrix[:2, 3] += shift
    return Camera(matrix, width, height), score


def prepare_job(registry_path: Path, sprite_manifest: Path, output: Path, *, horizontal: float, vertical: float, minimum_iou: float = .55, polygon_thickness: float | None = None, atlas_size: int = 256) -> Path:
    """Read actual existing compiler outputs and create a directly runnable job."""
    registry, appearance = read_json(registry_path), read_json(sprite_manifest)
    if registry.get("game_version") != appearance.get("game_version"):
        raise ValueError("geometry and sprite manifests describe different game versions")
    sprite = str(appearance["sprite"])
    mesh = registry_mesh(registry, sprite, polygon_thickness=polygon_thickness)
    rgba = isolated_sprite(sprite_manifest)
    camera, iou = calibrate_anchor(mesh, rgba, horizontal, vertical, minimum_iou=minimum_iou)
    output = output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    path = output / "job.json"
    if path.exists():
        raise FileExistsError(f"refusing to replace existing source job {path}")
    job = {"schema_version": 1, "prototype": sprite, "source_revision": f"PZ-{registry['game_version']}:{registry['source_sha256']}", "bounds": mesh.bounds.tolist(), "geometry": {"vertices": mesh.vertices.tolist(), "faces": mesh.faces.tolist()}, "views": [{"name": sprite, "sprite_manifest": str(sprite_manifest.resolve()), "matrix": camera.matrix.tolist()}], "atlas_size": atlas_size, "padding": 4, "completion": {"mode": "harmonic"}, "calibration": {"method": "source-silhouette-translation-fit", "iou": iou, "horizontal_scale": horizontal, "vertical_scale": vertical, "source_geometry_registry_sha256": registry["source_sha256"], "primitive_transform_reference": "PZFPS 009c258 WorldMeshBuilder.transform", "polygon_visual_thickness": polygon_thickness, "instance_origin": "source square center; not included in mesh"}}
    path.write_bytes(canonical_json(job))
    return path
