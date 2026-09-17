"""Calibrated geometry. Positions are right-handed, Y-up object coordinates.

An affine camera returns (pixel x, pixel y, depth); smaller depth is nearer.
Image coordinates refer to pixel centers, not normalized texture coordinates.
No PZ unit, crop anchor, or source orientation is silently inferred.
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

import numpy as np
from numpy.typing import NDArray
import trimesh
from skimage.measure import marching_cubes

FloatArray = NDArray[np.float64]


def finite(value: object, shape: tuple[int | None, ...], name: str) -> FloatArray:
    array = np.array(value, dtype=np.float64, copy=True)
    if array.ndim != len(shape) or any(n is not None and array.shape[i] != n for i, n in enumerate(shape)):
        raise ValueError(f"{name}: expected shape {shape}, received {array.shape}")
    if not np.isfinite(array).all():
        raise ValueError(f"{name}: non-finite values")
    return array


@dataclass(frozen=True)
class Camera:
    matrix: FloatArray
    width: int
    height: int

    def __post_init__(self) -> None:
        matrix = finite(self.matrix, (3, 4), "camera matrix")
        if not 2 <= self.width <= 16384 or not 2 <= self.height <= 16384:
            raise ValueError("camera dimensions must be in [2, 16384]")
        if abs(np.linalg.det(matrix[:, :3])) < 1e-12:
            raise ValueError("camera must have independent image/depth axes")
        matrix.setflags(write=False)
        object.__setattr__(self, "matrix", matrix)

    @property
    def toward_camera(self) -> FloatArray:
        # Correct even when the calibrated image axes are not orthogonal.
        ray = np.linalg.solve(self.matrix[:, :3], [0.0, 0.0, -1.0])
        return ray / np.linalg.norm(ray)

    def project(self, points: FloatArray) -> FloatArray:
        return np.asarray(points) @ self.matrix[:, :3].T + self.matrix[:, 3]

    @classmethod
    def look_at(cls, direction: Iterable[float], center: Iterable[float], pixels_per_unit: float, size: int = 256) -> "Camera":
        toward = finite(list(direction), (3,), "camera direction")
        if np.linalg.norm(toward) < 1e-12 or pixels_per_unit <= 0:
            raise ValueError("zero direction or nonpositive image scale")
        toward /= np.linalg.norm(toward)
        up = np.array([0.0, 1.0, 0.0])
        if abs(toward @ up) > .99:
            up = np.array([0.0, 0.0, 1.0])
        right = np.cross(up, toward)
        right /= np.linalg.norm(right)
        image_up = np.cross(toward, right)
        axes = np.array([right * pixels_per_unit, -image_up * pixels_per_unit, -toward])
        offset = np.array([(size - 1) / 2, (size - 1) / 2, 0.0]) - axes @ np.asarray(center)
        return cls(np.column_stack([axes, offset]), size, size)

    @classmethod
    def pz_isometric(cls, width: int, height: int, anchor: tuple[float, float], horizontal: float, vertical: float) -> "Camera":
        if horizontal <= 0 or vertical <= 0:
            raise ValueError("PZ projection scales must be positive")
        rows = np.array([[horizontal, 0, -horizontal], [horizontal / 2, -vertical, horizontal / 2]], dtype=float)
        toward = np.cross(rows[1], rows[0])
        toward /= np.linalg.norm(toward)
        return cls(np.column_stack([np.vstack([rows, -toward]), [anchor[0], anchor[1], 0]]), width, height)


@dataclass(frozen=True)
class Mesh:
    vertices: FloatArray
    faces: NDArray[np.int64]

    def __post_init__(self) -> None:
        vertices = finite(self.vertices, (None, 3), "vertices")
        raw = np.asarray(self.faces)
        if raw.ndim != 2 or raw.shape[1] != 3 or not np.issubdtype(raw.dtype, np.integer):
            raise ValueError("faces must be integer triangles")
        faces = raw.astype(np.int64, copy=True)
        if len(vertices) < 3 or len(faces) < 1 or faces.min() < 0 or faces.max() >= len(vertices):
            raise ValueError("empty mesh or out-of-range vertex index")
        cross = np.cross(vertices[faces[:, 1]] - vertices[faces[:, 0]], vertices[faces[:, 2]] - vertices[faces[:, 0]])
        if np.any(np.linalg.norm(cross, axis=1) <= 1e-12):
            raise ValueError("degenerate triangles must be removed explicitly")
        vertices.setflags(write=False)
        faces.setflags(write=False)
        object.__setattr__(self, "vertices", vertices)
        object.__setattr__(self, "faces", faces)

    @property
    def normals(self) -> FloatArray:
        triangles = self.vertices[self.faces]
        values = np.cross(triangles[:, 1] - triangles[:, 0], triangles[:, 2] - triangles[:, 0])
        return values / np.linalg.norm(values, axis=1, keepdims=True)

    @property
    def bounds(self) -> FloatArray:
        return np.array([self.vertices.min(axis=0), self.vertices.max(axis=0)])

    def validate_bounds(self, bounds: FloatArray, tolerance: float = 1e-6) -> None:
        bounds = finite(bounds, (2, 3), "constraint bounds")
        if np.any(bounds[0] >= bounds[1]):
            raise ValueError("constraint bounds have nonpositive extent")
        if np.any(self.bounds[0] < bounds[0] - tolerance) or np.any(self.bounds[1] > bounds[1] + tolerance):
            raise ValueError("candidate geometry violates the authoritative footprint/height bounds")

    def transformed(self, matrix: FloatArray) -> "Mesh":
        matrix = finite(matrix, (4, 4), "object transform")
        if not np.allclose(matrix[3], [0, 0, 0, 1]) or abs(np.linalg.det(matrix[:3, :3])) < 1e-12:
            raise ValueError("transform must be affine and invertible")
        faces = self.faces[:, ::-1] if np.linalg.det(matrix[:3, :3]) < 0 else self.faces
        return Mesh(self.vertices @ matrix[:3, :3].T + matrix[:3, 3], faces)

    @classmethod
    def box(cls, bounds: FloatArray) -> "Mesh":
        bounds = finite(bounds, (2, 3), "box bounds")
        if np.any(bounds[0] >= bounds[1]):
            raise ValueError("box bounds must have positive extent")
        geometry = trimesh.creation.box(extents=bounds[1] - bounds[0])
        return cls(geometry.vertices + bounds.mean(axis=0), geometry.faces)

    @classmethod
    def load(cls, path: Path) -> "Mesh":
        scene = trimesh.load_scene(path, process=False)
        # to_mesh applies node transforms instead of flattening raw source arrays.
        value = scene.to_mesh()
        return cls(value.vertices, value.faces)


@dataclass
class Raster:
    face: NDArray[np.int32]
    depth: FloatArray
    barycentric: FloatArray


def rasterize(mesh: Mesh, camera: Camera) -> Raster:
    """Deterministic CPU reference rasterizer for compilation, not the game renderer."""
    projected = camera.project(mesh.vertices)
    faces = np.full((camera.height, camera.width), -1, dtype=np.int32)
    depths = np.full(faces.shape, np.inf)
    bary = np.zeros((*faces.shape, 3))
    for face_id, indices in enumerate(mesh.faces):
        tri = projected[indices]
        low = np.maximum(np.ceil(tri[:, :2].min(axis=0)).astype(int), [0, 0])
        high = np.minimum(np.floor(tri[:, :2].max(axis=0)).astype(int), [camera.width - 1, camera.height - 1])
        if np.any(high < low):
            continue
        x0, y0 = tri[0, :2]
        x1, y1 = tri[1, :2]
        x2, y2 = tri[2, :2]
        determinant = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
        if abs(determinant) < 1e-12:
            continue
        yy, xx = np.mgrid[low[1]:high[1] + 1, low[0]:high[0] + 1]
        w0 = ((y1 - y2) * (xx - x2) + (x2 - x1) * (yy - y2)) / determinant
        w1 = ((y2 - y0) * (xx - x2) + (x0 - x2) * (yy - y2)) / determinant
        weights = np.stack([w0, w1, 1 - w0 - w1], axis=-1)
        z = weights @ tri[:, 2]
        region = np.s_[low[1]:high[1] + 1, low[0]:high[0] + 1]
        valid = (weights.min(axis=-1) >= -1e-8) & (z < depths[region] - 1e-10)
        depths[region][valid] = z[valid]
        faces[region][valid] = face_id
        bary[region][valid] = weights[valid]
    return Raster(faces, depths, bary)


def visual_hull(bounds: FloatArray, silhouettes: list[tuple[Camera, NDArray[np.bool_]]], resolution: int = 48) -> Mesh:
    """Intersect calibrated silhouettes inside known bounds; concavities stay unknown.

    Unobserved pixels do not carve space. The input bounds are a hard outer
    constraint, not a claim that every enclosed voxel was observed occupied.
    """
    bounds = finite(bounds, (2, 3), "hull bounds")
    if np.any(bounds[0] >= bounds[1]) or not 8 <= resolution <= 192 or not silhouettes:
        raise ValueError("invalid bounds/resolution or no silhouettes")
    step = (bounds[1] - bounds[0]) / resolution
    axes = [bounds[0, axis] + (np.arange(resolution) + .5) * step[axis] for axis in range(3)]
    points = np.stack(np.meshgrid(*axes, indexing="ij"), axis=-1).reshape(-1, 3)
    occupancy = np.ones(len(points), dtype=bool)
    observed = np.zeros(len(points), dtype=bool)
    for camera, silhouette in silhouettes:
        if silhouette.shape != (camera.height, camera.width):
            raise ValueError("silhouette dimensions do not match calibration")
        pixels = np.rint(camera.project(points)[:, :2]).astype(int)
        inside = (pixels[:, 0] >= 0) & (pixels[:, 0] < camera.width) & (pixels[:, 1] >= 0) & (pixels[:, 1] < camera.height)
        occupancy[inside] &= silhouette[pixels[inside, 1], pixels[inside, 0]]
        observed |= inside
    if not observed.any() or not occupancy.any():
        raise ValueError("silhouettes have no consistent visible volume")
    volume = np.pad(occupancy.reshape((resolution,) * 3).astype(np.float32), 1)
    vertices, faces, _, _ = marching_cubes(volume, .5, spacing=tuple(step), gradient_direction="ascent")
    vertices += bounds[0] - step / 2
    result = Mesh(vertices, faces)
    result.validate_bounds(bounds, tolerance=1e-5)
    return result
