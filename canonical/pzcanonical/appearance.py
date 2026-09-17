"""Shared object-space appearance: visibility-tested observations, persistent fill.

This implements a calibrated multiview bake, not TexFusion's trained denoiser.
Optional learned completion works in rendered novel views and is backprojected
into this same atlas; observed source texels are never overwritten by it.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol

import numpy as np
from numpy.typing import NDArray
from scipy.ndimage import distance_transform_edt, map_coordinates
from scipy.spatial import cKDTree
from scipy.sparse import coo_matrix, diags
from scipy.sparse.linalg import cg

from .geometry import Camera, FloatArray, Mesh, rasterize


def srgb_to_linear(rgb: FloatArray) -> FloatArray:
    return np.where(rgb <= .04045, rgb / 12.92, ((rgb + .055) / 1.055) ** 2.4)


def linear_to_srgb(rgb: FloatArray) -> FloatArray:
    rgb = np.clip(rgb, 0, 1)
    return np.where(rgb <= .0031308, rgb * 12.92, 1.055 * rgb ** (1 / 2.4) - .055)


@dataclass(frozen=True)
class View:
    name: str
    camera: Camera
    rgba: NDArray[np.uint8]
    depth: FloatArray | None = None
    weight: float = 1.0
    generated: bool = False

    def __post_init__(self) -> None:
        rgba = np.asarray(self.rgba)
        if rgba.shape != (self.camera.height, self.camera.width, 4) or rgba.dtype != np.uint8:
            raise ValueError("source image must be uint8 RGBA and match its camera")
        if not np.isfinite(self.weight) or self.weight <= 0:
            raise ValueError("source view weight must be positive")
        if self.depth is not None:
            depth = np.asarray(self.depth, dtype=float)
            if depth.shape != rgba.shape[:2] or np.isnan(depth).any():
                raise ValueError("depth dimensions invalid or depth contains NaN")


@dataclass
class Atlas:
    mesh: Mesh
    uv: FloatArray
    face: NDArray[np.int32]
    points: FloatArray
    normals: FloatArray
    valid: NDArray[np.bool_]
    size: int


def unwrap(mesh: Mesh, size: int = 256, padding: int = 4) -> Atlas:
    """Deterministic planar charts; no source atlas pixels are reused as UVs.

    Coplanar triangles share a chart. General curved meshes may require many
    charts; fail on insufficient resolution instead of silently dropping faces.
    UV and vertex splitting are persisted in the GLB, not repeated per frame.
    """
    if not 32 <= size <= 4096 or padding < 2:
        raise ValueError("atlas size must be 32..4096 with >=2 pixels padding")
    normals = mesh.normals
    offsets = np.sum(normals * mesh.vertices[mesh.faces[:, 0]], axis=1)
    keys = np.round(np.column_stack([normals, offsets]), 6)
    _, groups = np.unique(keys, axis=0, return_inverse=True)
    count = int(groups.max()) + 1
    columns = int(np.ceil(np.sqrt(count)))
    rows = int(np.ceil(count / columns))
    cell_x, cell_y = size / columns, size / rows
    if min(cell_x, cell_y) < 2 * padding + 5:
        raise ValueError(f"{count} planar charts do not fit atlas={size}; increase atlas resolution or provide a simpler mesh")
    corners = mesh.vertices[mesh.faces].reshape(-1, 3)
    uv_pixels = np.zeros((len(corners), 2))
    for group in range(count):
        face_ids = np.flatnonzero(groups == group)
        ids = (face_ids[:, None] * 3 + np.arange(3)).ravel()
        normal = normals[face_ids[0]]
        reference = np.eye(3)[np.argmin(np.abs(normal))]
        right = np.cross(reference, normal)
        right /= np.linalg.norm(right)
        up = np.cross(normal, right)
        projected = corners[ids] @ np.array([right, up]).T
        low, high = projected.min(axis=0), projected.max(axis=0)
        extent = high - low
        if extent.min() < 1e-12:
            raise ValueError("degenerate planar chart")
        available = np.array([cell_x, cell_y]) - 2 * padding - 1
        scale = np.min(available / extent)
        start = np.array([group % columns * cell_x, group // columns * cell_y]) + padding
        start += (available - extent * scale) / 2
        uv_pixels[ids] = (projected - low) * scale + start
    faces = np.arange(len(corners), dtype=np.int64).reshape(-1, 3)
    expanded = Mesh(corners, faces)
    uv = np.column_stack([uv_pixels[:, 0] / (size - 1), 1 - uv_pixels[:, 1] / (size - 1)])
    uv_geometry = Mesh(np.column_stack([uv_pixels, np.zeros(len(corners))]), faces)
    raster = rasterize(uv_geometry, Camera(np.column_stack([np.eye(3), np.zeros(3)]), size, size))
    valid = raster.face >= 0
    represented = np.unique(raster.face[valid])
    if len(represented) != len(faces):
        raise ValueError("one or more faces have no atlas samples; increase resolution")
    points = np.zeros((size, size, 3))
    atlas_normals = np.zeros_like(points)
    triangle = expanded.vertices[expanded.faces[raster.face[valid]]]
    points[valid] = np.einsum("ni,nij->nj", raster.barycentric[valid], triangle)
    atlas_normals[valid] = expanded.normals[raster.face[valid]]
    return Atlas(expanded, uv, raster.face, points, atlas_normals, valid, size)


@dataclass
class Bake:
    atlas: Atlas
    linear: FloatArray
    confidence: FloatArray
    observed: NDArray[np.bool_]
    generated: NDArray[np.bool_]
    owner: NDArray[np.int32]
    disagreement: FloatArray

    def rgba(self) -> NDArray[np.uint8]:
        rgb = (linear_to_srgb(self.linear) * 255 + .5).astype(np.uint8)
        # Extrude chart colors into empty padding. Geometry alpha remains opaque;
        # source silhouette alpha is evidence, not holes in canonical geometry.
        _, nearest = distance_transform_edt(~self.atlas.valid, return_indices=True)
        rgb[~self.atlas.valid] = rgb[nearest[0][~self.atlas.valid], nearest[1][~self.atlas.valid]]
        return np.dstack([rgb, np.full(rgb.shape[:2], 255, dtype=np.uint8)])


def project_samples(atlas: Atlas, view: View, depth_tolerance: float) -> tuple[NDArray[np.int64], FloatArray, FloatArray]:
    """Return atlas sample IDs, linear RGB and weights for truly visible evidence."""
    ids = np.flatnonzero(atlas.valid)
    positions = atlas.points.reshape(-1, 3)[ids]
    normals = atlas.normals.reshape(-1, 3)[ids]
    projected = view.camera.project(positions)
    rounded = np.rint(projected[:, :2]).astype(int)
    cosine = normals @ view.camera.toward_camera
    visible = (cosine > .08) & (rounded[:, 0] >= 0) & (rounded[:, 0] < view.camera.width) & (rounded[:, 1] >= 0) & (rounded[:, 1] < view.camera.height)
    ids, projected, rounded, cosine = ids[visible], projected[visible], rounded[visible], cosine[visible]
    if not len(ids):
        return ids, np.empty((0, 3)), np.empty(0)
    surface = rasterize(atlas.mesh, view.camera)
    sampled_depth = surface.depth[rounded[:, 1], rounded[:, 0]]
    # Calibration determines depth change across a pixel. Only relax by the
    # maximum half-pixel slope for this source-facing surface, not object depth.
    inverse = np.linalg.inv(view.camera.matrix[:, :3])
    selected_normals = atlas.normals.reshape(-1, 3)[ids]
    denominators = selected_normals @ inverse[:, 2]
    slope = .55 * (np.abs(selected_normals @ inverse[:, 0]) + np.abs(selected_normals @ inverse[:, 1])) / np.maximum(np.abs(denominators), 1e-9)
    visible = np.isfinite(sampled_depth) & (np.abs(projected[:, 2] - sampled_depth) <= depth_tolerance + slope)
    if view.depth is not None:
        evidence_depth = np.asarray(view.depth)[rounded[:, 1], rounded[:, 0]]
        visible &= np.isfinite(evidence_depth) & (np.abs(projected[:, 2] - evidence_depth) <= depth_tolerance + slope)
    # Sample only inside this isolated sprite. Never filter across neighboring
    # sprites in the game's packed image page.
    alpha = view.rgba[rounded[:, 1], rounded[:, 0], 3] / 255.0
    visible &= alpha >= .98
    ids, projected, cosine, alpha = ids[visible], projected[visible], cosine[visible], alpha[visible]
    coords = [projected[:, 1], projected[:, 0]]
    rgb = np.column_stack([map_coordinates(view.rgba[..., c].astype(float) / 255.0, coords, order=1, mode="nearest") for c in range(3)])
    return ids, srgb_to_linear(rgb), cosine ** 4 * alpha * view.weight


def bake_views(atlas: Atlas, views: list[View], depth_tolerance: float = .002) -> Bake:
    if not views or depth_tolerance < 0:
        raise ValueError("at least one calibrated source view and nonnegative depth tolerance required")
    count = atlas.size ** 2
    samples: list[tuple[NDArray[np.int64], FloatArray, FloatArray]] = []
    total = np.zeros(count)
    sums = np.zeros((count, 3))
    best = np.zeros(count)
    owner = np.full(count, -1, dtype=np.int32)
    observed = np.zeros(count, dtype=bool)
    generated = np.zeros(count, dtype=bool)
    for view_id, view in enumerate(views):
        ids, color, weight = project_samples(atlas, view, depth_tolerance)
        samples.append((ids, color, weight))
        total[ids] += weight
        sums[ids] += color * weight[:, None]
        stronger = weight > best[ids]
        owner[ids[stronger]] = view_id
        best[ids[stronger]] = weight[stronger]
        if view.generated:
            generated[ids] = True
        else:
            observed[ids] = True
    known = total > 0
    if not known.any():
        raise ValueError("no source observations survived calibration/visibility checks")
    mean = sums / np.maximum(total[:, None], 1e-12)
    residual = np.zeros(count)
    # Disagreement is preserved as evidence, not disguised by averaging/blur.
    for ids, color, weight in samples:
        residual[ids] += np.mean((color - mean[ids]) ** 2, axis=1) * weight
    residual = np.sqrt(residual / np.maximum(total, 1e-12))
    shape = (atlas.size, atlas.size)
    return Bake(atlas, mean.reshape(*shape, 3), total.reshape(shape), observed.reshape(shape), generated.reshape(shape), owner.reshape(shape), residual.reshape(shape))


def complete_harmonic(bake: Bake, neighbors: int = 8, material_groups: NDArray[np.int32] | None = None) -> None:
    """Solve missing colors on a surface graph, keeping all observations exact.

    This is a deterministic low-frequency completion baseline, NOT a learned
    recovery of hidden detail or unlit reflectance. Surface-neighbor links span
    UV seams. Optional face material groups prevent color crossing materials.
    """
    atlas = bake.atlas
    ids = np.flatnonzero(atlas.valid)
    points = atlas.points.reshape(-1, 3)[ids]
    normals = atlas.normals.reshape(-1, 3)[ids]
    colors = bake.linear.reshape(-1, 3)[ids].copy()
    fixed = bake.confidence.ravel()[ids] > 0
    if fixed.all():
        return
    if material_groups is None:
        groups = np.zeros(len(ids), dtype=np.int32)
    else:
        material_groups = np.asarray(material_groups)
        if material_groups.shape != (len(atlas.mesh.faces),):
            raise ValueError("material groups must provide one group per source face")
        groups = material_groups[atlas.face.ravel()[ids]]
    for group in np.unique(groups):
        member = np.flatnonzero(groups == group)
        group_fixed = fixed[member]
        if not group_fixed.any():
            # Explicit neutral prior, never claimed to be observed.
            colors[member] = srgb_to_linear(np.array([.35, .35, .35]))
            continue
        missing = np.flatnonzero(~group_fixed)
        if not len(missing):
            continue
        p, n = points[member], normals[member]
        nearest_known = cKDTree(p[group_fixed]).query(p[missing])[1]
        prior = colors[member[group_fixed]][nearest_known]
        colors[member[missing]] = prior
        k = min(neighbors + 1, len(member))
        distance, target = cKDTree(p).query(p, k=k)
        if k == 1:
            continue
        source = np.repeat(np.arange(len(member)), k - 1)
        target = target[:, 1:].ravel()
        distance = distance[:, 1:].ravel()
        scale = np.median(distance[distance > 1e-9]) if np.any(distance > 1e-9) else 1.0
        alignment = np.maximum(np.einsum("ij,ij->i", n[source], n[target]), .05)
        weights = alignment * np.exp(-(distance / max(scale * 3, 1e-8)) ** 2)
        graph = coo_matrix((weights, (source, target)), shape=(len(member), len(member))).tocsr()
        graph = (graph + graph.T) * .5
        degree = np.asarray(graph.sum(axis=1)).ravel()
        laplacian = diags(degree) - graph
        # Small screened prior makes disconnected/unobserved patches solvable.
        strength = .01
        system = laplacian[missing][:, missing] + diags(np.full(len(missing), strength))
        rhs = -laplacian[missing][:, group_fixed] @ colors[member[group_fixed]] + strength * prior
        for channel in range(3):
            solution, info = cg(system, rhs[:, channel], x0=prior[:, channel], rtol=1e-6, maxiter=1200)
            if info != 0 or not np.isfinite(solution).all():
                raise RuntimeError(f"surface completion solver failed for material={group}, channel={channel}, info={info}")
            colors[member[missing], channel] = solution
    bake.linear.reshape(-1, 3)[ids] = np.clip(colors, 0, 1)
    # No change to observed/generated flags: harmonic values remain prior-only.


class Inpainter(Protocol):
    def __call__(self, rgb: NDArray[np.uint8], missing: NDArray[np.bool_], prompt: str, seed: int) -> NDArray[np.uint8]: ...


def render(bake: Bake, camera: Camera, lit: bool = False) -> tuple[NDArray[np.uint8], NDArray[np.bool_]]:
    """CPU inspection renderer. Output alpha/coverage is geometry, not a screenshot overlay."""
    raster = rasterize(bake.atlas.mesh, camera)
    valid = raster.face >= 0
    uv = np.einsum("ni,nij->nj", raster.barycentric[valid], bake.atlas.uv[bake.atlas.mesh.faces[raster.face[valid]]])
    x = np.clip(np.rint(uv[:, 0] * (bake.atlas.size - 1)).astype(int), 0, bake.atlas.size - 1)
    y = np.clip(np.rint((1 - uv[:, 1]) * (bake.atlas.size - 1)).astype(int), 0, bake.atlas.size - 1)
    # Quantization near chart edges can land in padding; use nearest valid
    # canonical texel rather than returning an uninitialized/black border.
    _, nearest = distance_transform_edt(~bake.atlas.valid, return_indices=True)
    y, x = nearest[0, y, x], nearest[1, y, x]
    color = bake.linear[y, x].copy()
    if lit:
        light = np.array([.3, .85, .43]); light /= np.linalg.norm(light)
        diffuse = np.maximum(bake.atlas.mesh.normals[raster.face[valid]] @ light, 0)
        color *= (.25 + .75 * diffuse[:, None])
    image = np.zeros((camera.height, camera.width, 4), dtype=np.uint8)
    image[valid, :3] = (linear_to_srgb(color) * 255 + .5).astype(np.uint8)
    image[valid, 3] = 255
    supported = np.zeros(valid.shape, dtype=bool)
    supported[valid] = (bake.confidence[y, x] > 0)
    return image, supported


def complete_novel_views(bake: Bake, cameras: list[Camera], inpainter: Inpainter, prompt: str, seed: int = 0) -> int:
    """Generate unseen views serially into one persistent atlas, never per frame.

    Accepted observed and already-generated texels are locked. The geometry,
    silhouette, source mask and depth determine where novel pixels may land.
    """
    adopted = 0
    for index, camera in enumerate(cameras):
        current, supported = render(bake, camera)
        missing = (current[..., 3] > 0) & ~supported
        if not missing.any():
            continue
        output = np.asarray(inpainter(current[..., :3], missing, prompt, seed + index))
        if output.shape != current[..., :3].shape or output.dtype != np.uint8:
            raise ValueError("inpainting provider returned invalid image")
        # Do not trust the model to honor its mask outside the missing region.
        output = np.where(missing[..., None], output, current[..., :3])
        view = View(f"generated-{index}", camera, np.dstack([output, current[..., 3]]), generated=True)
        ids, color, weights = project_samples(bake.atlas, view, .002)
        unknown = bake.confidence.ravel()[ids] == 0
        ids, color, weights = ids[unknown], color[unknown], weights[unknown]
        bake.linear.reshape(-1, 3)[ids] = color
        bake.confidence.ravel()[ids] = weights * .25
        bake.generated.ravel()[ids] = True
        adopted += len(ids)
    return adopted
