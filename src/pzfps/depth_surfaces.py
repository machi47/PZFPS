from __future__ import annotations

import json
import math
from pathlib import Path
import struct
from typing import Any, Iterable
import zlib

from .asset_coverage import category
from .common import now_utc, sha256_file, write_json


class DepthSurfaceError(ValueError):
    pass


_PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
_TILE_WIDTH = 128
_TILE_HEIGHT = 256
_SOURCE_Y_SCALE = 64.0 * math.sqrt(1.5)
_DEPTH_Y_SCALE = 1.0 / (2.0 * math.sqrt(6.0))


def compile_planar_roof_surfaces(
    definitions_path: Path,
    assignments_path: Path,
    depthmaps: Path,
    output: Path,
    *,
    game_version: str,
) -> dict[str, Any]:
    """Recover strictly planar roof faces from PZ's own per-pixel depth evidence.

    This is deliberately narrower than a generic sprite extrusion. A roof is emitted only
    when one plane explains at least 97% of its valid depth pixels after quantisation-noise
    trimming. Compound hips, ridges and eaves remain explicit holes until a multi-plane
    compiler exists.
    """
    definitions = json.loads(definitions_path.read_text(encoding="utf-8"))
    if definitions.get("game_version") != game_version:
        raise DepthSurfaceError("tile definitions describe a different game version")
    assignments = parse_depth_assignments(assignments_path)
    tiles: dict[str, Any] = definitions.get("tiles", {})
    images: dict[Path, PngPixels] = {}
    target_cache: dict[str, tuple[list[dict[str, Any]], dict[str, Any]] | str] = {}
    compiled: dict[str, dict[str, Any]] = {}
    rejected: dict[str, int] = {}

    for identity, definition in sorted(tiles.items()):
        if category(identity) != "roof":
            continue
        if not has_physical_roof_anchor(identity, definition):
            # A depth assignment is an occlusion hint, not proof that the target sprite is
            # itself a square-anchored surface. PZ places ridge/overlay art on upper squares
            # (for example roofs_05_47) so it can extend down across lower roof sprites in the
            # isometric compositor. Reusing the assigned plane as local 3D geometry instead
            # produces the observed long strips suspended above the building. Fail closed
            # until those identities have a topology-aware ridge/accent assembly.
            _count(rejected, "unanchored_roof_overlay")
            continue
        target = assignments.get(identity, identity)
        cached = target_cache.get(target)
        if isinstance(cached, str):
            _count(rejected, cached)
            continue
        if cached is not None:
            geometry, properties = cached
            compiled[identity] = {"geometry": geometry, "properties": properties | {"depth_target": target}}
            continue
        target_definition = tiles.get(target)
        if target_definition is not None:
            tileset = str(target_definition.get("tileset", ""))
            xy = target_definition.get("xy", [])
            if len(xy) != 2:
                target_cache[target] = "missing_depth_target_coordinates"
                _count(rejected, "missing_depth_target_coordinates")
                continue
            target_index: int | None = None
        else:
            tileset, target_index = split_tile_identity(target)
            if target_index is None:
                target_cache[target] = "missing_depth_target_definition"
                _count(rejected, "missing_depth_target_definition")
                continue
        depth_path = depthmaps / f"DEPTH_{tileset}.png"
        if not depth_path.is_file():
            target_cache[target] = "missing_depth_image"
            _count(rejected, "missing_depth_image")
            continue
        image = images.get(depth_path)
        if image is None:
            image = read_png(depth_path)
            images[depth_path] = image
        if target_definition is None:
            if image.width % _TILE_WIDTH:
                target_cache[target] = "invalid_depth_atlas_width"
                _count(rejected, "invalid_depth_atlas_width")
                continue
            columns = image.width // _TILE_WIDTH
            assert target_index is not None
            xy = [target_index % columns, target_index // columns]
        origin_x = int(xy[0]) * _TILE_WIDTH
        origin_y = int(xy[1]) * _TILE_HEIGHT
        if origin_x + _TILE_WIDTH > image.width or origin_y + _TILE_HEIGHT > image.height:
            target_cache[target] = "depth_tile_outside_image"
            _count(rejected, "depth_tile_outside_image")
            continue
        samples = _tile_samples(image, origin_x, origin_y)
        if len(samples) < 64:
            target_cache[target] = "empty_or_tiny_depth_tile"
            _count(rejected, "empty_or_tiny_depth_tile")
            continue
        plane = fit_planar_surface(samples)
        if plane is not None:
            patches = [PlanarPatch(
                _implicit_from_y_plane(plane),
                samples,
            )]
            surface_method = "single_plane"
        else:
            patches = fit_piecewise_planar_surfaces(samples)
            surface_method = "piecewise_planar"
        if not patches:
            target_cache[target] = "not_planar_surface"
            _count(rejected, "not_planar_surface")
            continue
        try:
            triangles, patch_properties = triangulate_planar_patches(patches)
        except DepthSurfaceError:
            target_cache[target] = "unsafe_planar_patch_hull"
            _count(rejected, "unsafe_planar_patch_hull")
            continue
        properties = {
            "depth_target": target,
            "depth_image": depth_path.name,
            "surface_method": surface_method,
            **patch_properties,
        }
        target_cache[target] = (triangles, properties)
        compiled[identity] = {
            "geometry": triangles,
            "properties": properties,
        }

    source_hashes = {
        "tile_definitions": sha256_file(definitions_path),
        "depth_assignments": sha256_file(assignments_path),
        "depth_images": {
            path.name: sha256_file(path) for path in sorted(images)
        },
    }
    document = {
        "schema_version": 1,
        "generated_at": now_utc(),
        "game_version": game_version,
        "source": {
            "tile_definitions": str(definitions_path),
            "depth_assignments": str(assignments_path),
            "depthmaps": str(depthmaps),
        },
        "source_sha256": _stable_hash(source_hashes),
        "source_hashes": source_hashes,
        "method": "strict-planar-patch-fit-from-installed-depth-texture",
        "tile_count": len(compiled),
        "triangle_count": sum(len(value["geometry"]) for value in compiled.values()),
        "rejected": dict(sorted(rejected.items())),
        "tiles": compiled,
    }
    document["audit"] = audit_roof_surfaces(document)
    write_json(output, document)
    return document


def has_physical_roof_anchor(identity: str, definition: dict[str, Any]) -> bool:
    """Whether installed semantics justify treating a depth map as local roof geometry.

    Exterior-roof wall/fascia families have an explicit structural family identity. Plain
    ``roofs_*`` art must carry a rain-blocking, eave, floor or attachment property; RoofGroup
    and WestRoofT alone only identify compositing/style and do not locate an overlay in 3D.
    """
    if not identity.startswith("roofs_"):
        return True
    properties = definition.get("properties", {})
    anchors = {
        "BlockRain",
        "isEave",
        "diamondFloor",
        "solidfloor",
        "attachedN",
        "attachedE",
        "attachedS",
        "attachedW",
        "attachedFloor",
    }
    return any(anchor in properties for anchor in anchors)


def parse_depth_assignments(path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    for line_number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.split("//", 1)[0].strip()
        if not line or line in {"tileDepthTextureAssignments", "{", "}"}:
            continue
        if line.startswith("VERSION"):
            continue
        if "=" not in line:
            raise DepthSurfaceError(f"line {line_number}: malformed depth assignment")
        key, value = (part.strip().rstrip(",") for part in line.split("=", 1))
        if not key or not value:
            raise DepthSurfaceError(f"line {line_number}: empty depth assignment")
        result[key] = value
    return result


def split_tile_identity(identity: str) -> tuple[str, int | None]:
    try:
        tileset, suffix = identity.rsplit("_", 1)
        return tileset, int(suffix)
    except (ValueError, IndexError):
        return identity, None


class PngPixels:
    def __init__(self, width: int, height: int, depth: bytes, alpha: bytes):
        self.width = width
        self.height = height
        self.depth = depth
        self.alpha = alpha


def read_png(path: Path) -> PngPixels:
    data = path.read_bytes()
    if not data.startswith(_PNG_SIGNATURE):
        raise DepthSurfaceError(f"not a PNG: {path}")
    position = len(_PNG_SIGNATURE)
    header: tuple[int, int, int, int, int, int, int] | None = None
    compressed = bytearray()
    palette = b""
    transparency = b""
    while position < len(data):
        if position + 12 > len(data):
            raise DepthSurfaceError(f"truncated PNG chunk in {path}")
        length = struct.unpack(">I", data[position:position + 4])[0]
        kind = data[position + 4:position + 8]
        payload = data[position + 8:position + 8 + length]
        if len(payload) != length:
            raise DepthSurfaceError(f"truncated PNG payload in {path}")
        position += length + 12
        if kind == b"IHDR":
            header = struct.unpack(">IIBBBBB", payload)
        elif kind == b"IDAT":
            compressed.extend(payload)
        elif kind == b"PLTE":
            palette = payload
        elif kind == b"tRNS":
            transparency = payload
        elif kind == b"IEND":
            break
    if header is None:
        raise DepthSurfaceError(f"PNG has no IHDR: {path}")
    width, height, bit_depth, color_type, compression, filtering, interlace = header
    if bit_depth != 8 or compression != 0 or filtering != 0 or interlace != 0:
        raise DepthSurfaceError(f"unsupported PNG encoding in {path}")
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}.get(color_type)
    if channels is None:
        raise DepthSurfaceError(f"unsupported PNG color type {color_type} in {path}")
    raw = zlib.decompress(bytes(compressed))
    rows = _unfilter(raw, width * channels, height, channels)
    depth = bytearray(width * height)
    alpha = bytearray([255]) * (width * height)
    for y, row in enumerate(rows):
        for x in range(width):
            source = x * channels
            target = y * width + x
            if color_type == 0:
                depth[target] = row[source]
            elif color_type == 2:
                depth[target] = row[source + 2]
            elif color_type == 3:
                index = row[source]
                palette_offset = index * 3
                if palette_offset + 2 >= len(palette):
                    raise DepthSurfaceError(f"palette index outside PLTE in {path}")
                depth[target] = palette[palette_offset + 2]
                alpha[target] = transparency[index] if index < len(transparency) else 255
            elif color_type == 4:
                depth[target] = row[source]
                alpha[target] = row[source + 1]
            else:
                depth[target] = row[source + 2]
                alpha[target] = row[source + 3]
    return PngPixels(width, height, bytes(depth), bytes(alpha))


def _unfilter(raw: bytes, stride: int, height: int, bytes_per_pixel: int) -> list[bytes]:
    expected = height * (stride + 1)
    if len(raw) != expected:
        raise DepthSurfaceError(f"PNG scanline size mismatch: expected {expected}, found {len(raw)}")
    rows: list[bytes] = []
    previous = bytearray(stride)
    offset = 0
    for _ in range(height):
        filter_type = raw[offset]
        source = raw[offset + 1:offset + 1 + stride]
        offset += stride + 1
        row = bytearray(stride)
        for index, value in enumerate(source):
            left = row[index - bytes_per_pixel] if index >= bytes_per_pixel else 0
            up = previous[index]
            upper_left = previous[index - bytes_per_pixel] if index >= bytes_per_pixel else 0
            if filter_type == 0:
                predictor = 0
            elif filter_type == 1:
                predictor = left
            elif filter_type == 2:
                predictor = up
            elif filter_type == 3:
                predictor = (left + up) // 2
            elif filter_type == 4:
                predictor = _paeth(left, up, upper_left)
            else:
                raise DepthSurfaceError(f"unsupported PNG filter {filter_type}")
            row[index] = (value + predictor) & 255
        rows.append(bytes(row))
        previous = row
    return rows


def _paeth(left: int, up: int, upper_left: int) -> int:
    estimate = left + up - upper_left
    distances = (abs(estimate - left), abs(estimate - up), abs(estimate - upper_left))
    return (left, up, upper_left)[distances.index(min(distances))]


class Plane:
    def __init__(self, a: float, b: float, c: float, inlier_fraction: float, rms: float):
        self.a = a
        self.b = b
        self.c = c
        self.inlier_fraction = inlier_fraction
        self.rms = rms


class Plane3:
    def __init__(
        self,
        normal: tuple[float, float, float],
        d: float,
        inlier_fraction: float,
        rms: float,
    ):
        self.normal = normal
        self.d = d
        self.inlier_fraction = inlier_fraction
        self.rms = rms


class PlanarPatch:
    def __init__(self, plane: Plane3, samples: list[tuple[float, float, float]]):
        self.plane = plane
        self.samples = samples


def _tile_samples(image: PngPixels, origin_x: int, origin_y: int) -> list[tuple[float, float, float]]:
    samples = []
    for y in range(_TILE_HEIGHT):
        row = (origin_y + y) * image.width + origin_x
        for x in range(_TILE_WIDTH):
            index = row + x
            if image.alpha[index] == 0:
                continue
            samples.append((x + 0.5, y + 0.5, image.depth[index] / 255.0))
    return samples


def depth_point(u: float, v: float, depth: float) -> tuple[float, float, float]:
    difference = (u - 64.0) / 64.0
    # PZ's installed projection gives these independent equations for sum=x+z and y.
    determinant = 32.0 * -_DEPTH_Y_SCALE - (-_SOURCE_Y_SCALE * -0.25)
    source_v = v - 224.0
    source_depth = depth - 0.75
    total = (source_v * -_DEPTH_Y_SCALE - (-_SOURCE_Y_SCALE * source_depth)) / determinant
    y = (32.0 * source_depth - (-0.25 * source_v)) / determinant
    return ((total + difference) * 0.5, y, (total - difference) * 0.5)


def fit_planar_surface(samples: list[tuple[float, float, float]]) -> Plane | None:
    points = [depth_point(*sample) for sample in samples]
    selected = points
    for _ in range(3):
        coefficients = _fit_y_plane(selected)
        if coefficients is None:
            return None
        a, b, c = coefficients
        residuals = [abs(y - (a * x + b * z + c)) for x, y, z in points]
        selected = [point for point, residual in zip(points, residuals) if residual <= 0.03]
    fraction = len(selected) / len(points)
    if fraction < 0.97:
        return None
    a, b, c = _fit_y_plane(selected) or (0.0, 0.0, 0.0)
    rms = math.sqrt(sum((y - (a * x + b * z + c)) ** 2 for x, y, z in selected) / len(selected))
    if rms > 0.012:
        return None
    return Plane(a, b, c, fraction, rms)


def _fit_y_plane(points: list[tuple[float, float, float]]) -> tuple[float, float, float] | None:
    if len(points) < 3:
        return None
    sxx = sxz = sx = szz = sz = sy = sxy = szy = 0.0
    for x, y, z in points:
        sxx += x * x
        sxz += x * z
        sx += x
        szz += z * z
        sz += z
        sy += y
        sxy += x * y
        szy += z * y
    return _solve3(
        [[sxx, sxz, sx], [sxz, szz, sz], [sx, sz, float(len(points))]],
        [sxy, szy, sy],
    )


def _solve3(matrix: list[list[float]], values: list[float]) -> tuple[float, float, float] | None:
    rows = [matrix[index][:] + [values[index]] for index in range(3)]
    for column in range(3):
        pivot = max(range(column, 3), key=lambda row: abs(rows[row][column]))
        if abs(rows[pivot][column]) < 1e-9:
            return None
        rows[column], rows[pivot] = rows[pivot], rows[column]
        divisor = rows[column][column]
        rows[column] = [value / divisor for value in rows[column]]
        for row in range(3):
            if row == column:
                continue
            factor = rows[row][column]
            rows[row] = [left - factor * right for left, right in zip(rows[row], rows[column])]
    return rows[0][3], rows[1][3], rows[2][3]


def opaque_hull(samples: list[tuple[float, float, float]]) -> list[tuple[float, float]]:
    rows: dict[int, list[int]] = {}
    for u, v, _ in samples:
        x = int(u - 0.5)
        y = int(v - 0.5)
        extent = rows.setdefault(y, [x, x])
        extent[0] = min(extent[0], x)
        extent[1] = max(extent[1], x)
    corners = []
    for y, (low, high) in rows.items():
        corners.extend(((low, y), (low, y + 1), (high + 1, y), (high + 1, y + 1)))
    return _convex_hull(corners)


def _convex_hull(points: Iterable[tuple[float, float]]) -> list[tuple[float, float]]:
    ordered = sorted(set(points))
    if len(ordered) <= 1:
        return ordered

    def cross(origin: tuple[float, float], a: tuple[float, float], b: tuple[float, float]) -> float:
        return (a[0] - origin[0]) * (b[1] - origin[1]) - (a[1] - origin[1]) * (b[0] - origin[0])

    lower: list[tuple[float, float]] = []
    for point in ordered:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], point) <= 0:
            lower.pop()
        lower.append(point)
    upper: list[tuple[float, float]] = []
    for point in reversed(ordered):
        while len(upper) >= 2 and cross(upper[-2], upper[-1], point) <= 0:
            upper.pop()
        upper.append(point)
    return lower[:-1] + upper[:-1]


def polygon_area(points: list[tuple[float, float]]) -> float:
    if len(points) < 3:
        return 0.0
    return abs(sum(
        points[index][0] * points[(index + 1) % len(points)][1]
        - points[(index + 1) % len(points)][0] * points[index][1]
        for index in range(len(points))
    )) * 0.5


def audit_roof_surfaces(document: dict[str, Any]) -> dict[str, Any]:
    """Fail closed when compiled vertices cannot reproduce their source-tile footprint."""
    tile_count = 0
    triangle_count = 0
    points: list[list[float]] = []
    minimum_triangle_area = math.inf
    maximum_hull_fill_ratio = 0.0
    for identity, record in document.get("tiles", {}).items():
        geometry = record.get("geometry", [])
        if not geometry:
            raise DepthSurfaceError(f"compiled roof has no geometry: {identity}")
        tile_count += 1
        maximum_hull_fill_ratio = max(
            maximum_hull_fill_ratio,
            float(record.get("properties", {}).get("opaque_hull_fill_ratio", 0.0)),
        )
        for primitive in geometry:
            if primitive.get("kind") != "triangle" or len(primitive.get("points", [])) != 3:
                raise DepthSurfaceError(f"compiled roof contains a non-triangle: {identity}")
            triangle = primitive["points"]
            if not all(len(point) == 3 for point in triangle):
                raise DepthSurfaceError(f"compiled roof contains a malformed point: {identity}")
            if not all(math.isfinite(float(value)) for point in triangle for value in point):
                raise DepthSurfaceError(f"compiled roof contains a non-finite point: {identity}")
            area = triangle_area_3d(triangle)
            if area <= 1e-8:
                raise DepthSurfaceError(f"compiled roof contains a degenerate triangle: {identity}")
            minimum_triangle_area = min(minimum_triangle_area, area)
            for point in triangle:
                u, v = source_pixel(point)
                if not (-1e-3 <= u <= _TILE_WIDTH + 1e-3
                        and -1e-3 <= v <= _TILE_HEIGHT + 1e-3):
                    raise DepthSurfaceError(
                        f"compiled roof projects outside its source tile: {identity} ({u}, {v})"
                    )
                points.append(point)
            triangle_count += 1
    if tile_count != document.get("tile_count") or triangle_count != document.get("triangle_count"):
        raise DepthSurfaceError("compiled roof summary counts do not match emitted geometry")
    if not points:
        raise DepthSurfaceError("compiled roof document is empty")
    return {
        "finite_points": len(points),
        "minimum_triangle_area": round(minimum_triangle_area, 9),
        "maximum_hull_fill_ratio": round(maximum_hull_fill_ratio, 7),
        "authored_bounds": {
            "min_x": min(point[0] for point in points),
            "max_x": max(point[0] for point in points),
            "min_y": min(point[1] for point in points),
            "max_y": max(point[1] for point in points),
            "min_z": min(point[2] for point in points),
            "max_z": max(point[2] for point in points),
        },
        "source_projection_bounds": {
            "min_u": round(min(source_pixel(point)[0] for point in points), 6),
            "max_u": round(max(source_pixel(point)[0] for point in points), 6),
            "min_v": round(min(source_pixel(point)[1] for point in points), 6),
            "max_v": round(max(source_pixel(point)[1] for point in points), 6),
        },
    }


def source_pixel(point: list[float]) -> tuple[float, float]:
    x, y, z = point
    return (
        64.0 + (x - z) * 64.0,
        224.0 + (x + z) * 32.0 - y * _SOURCE_Y_SCALE,
    )


def triangle_area_3d(points: list[list[float]]) -> float:
    a, b, c = points
    ux, uy, uz = (b[index] - a[index] for index in range(3))
    vx, vy, vz = (c[index] - a[index] for index in range(3))
    cross = (uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx)
    return 0.5 * math.sqrt(sum(value * value for value in cross))


def point_on_plane(u: float, v: float, plane: Plane) -> list[float]:
    difference = (u - 64.0) / 64.0
    coefficient = 64.0 - _SOURCE_Y_SCALE * (plane.a + plane.b)
    if abs(coefficient) < 1e-8:
        raise DepthSurfaceError("planar surface is parallel to the source projection")
    x = ((v - 224.0) - difference * (-32.0 + _SOURCE_Y_SCALE * plane.b)
         + _SOURCE_Y_SCALE * plane.c) / coefficient
    z = x - difference
    y = plane.a * x + plane.b * z + plane.c
    return [round(x, 7), round(y, 7), round(z, 7)]


def _implicit_from_y_plane(plane: Plane) -> Plane3:
    implicit = _normalise_plane((-plane.a, 1.0, -plane.b), -plane.c)
    return Plane3(implicit[0], implicit[1], plane.inlier_fraction, plane.rms)


def fit_piecewise_planar_surfaces(
    samples: list[tuple[float, float, float]],
    *,
    distance_threshold: float = 0.018,
    minimum_coverage: float = 0.95,
    maximum_planes: int = 10,
) -> list[PlanarPatch]:
    """Split an installed depth tile into conservative connected planar patches.

    Local source-pixel triples provide candidate planes. Candidates are refined against
    reconstructed 3D points, then their inliers are split by pixel connectivity so one
    convex hull can never bridge unrelated coplanar islands.
    """
    if len(samples) < 64:
        return []
    points = [depth_point(*sample) for sample in samples]
    by_pixel = {
        (int(sample[0] - 0.5), int(sample[1] - 0.5)): index
        for index, sample in enumerate(samples)
    }
    candidates: list[Plane3] = []
    candidate_keys: set[tuple[int, int, int, int]] = set()
    stride = 4
    for y in range(0, _TILE_HEIGHT - stride, stride):
        for x in range(0, _TILE_WIDTH - stride, stride):
            indices = (
                by_pixel.get((x, y)),
                by_pixel.get((x + stride, y)),
                by_pixel.get((x, y + stride)),
            )
            if any(index is None for index in indices):
                continue
            candidate = _plane_from_three(*(points[int(index)] for index in indices))
            if candidate is None:
                continue
            key = tuple(round(value * 40) for value in (*candidate.normal, candidate.d))
            if key in candidate_keys:
                continue
            candidate_keys.add(key)
            candidates.append(candidate)
    if not candidates:
        return []

    remaining = list(range(len(points)))
    patches: list[PlanarPatch] = []
    captured = 0
    minimum_inliers = max(48, int(len(samples) * 0.004))
    for _ in range(maximum_planes):
        best: Plane3 | None = None
        best_indices: list[int] = []
        for candidate in candidates:
            indices = [
                index for index in remaining
                if _plane_distance(candidate, points[index]) <= distance_threshold
            ]
            if len(indices) > len(best_indices):
                best = candidate
                best_indices = indices
        if best is None or len(best_indices) < minimum_inliers:
            break
        refined = best
        for _ in range(2):
            refined = _refine_plane([points[index] for index in best_indices], refined)
            best_indices = [
                index for index in remaining
                if _plane_distance(refined, points[index]) <= distance_threshold
            ]
        if len(best_indices) < minimum_inliers:
            break
        residuals = [_plane_distance(refined, points[index]) for index in best_indices]
        refined.inlier_fraction = len(best_indices) / len(samples)
        refined.rms = math.sqrt(sum(value * value for value in residuals) / len(residuals))
        components = _connected_sample_components(best_indices, samples)
        for component in components:
            if len(component) < 16:
                continue
            patches.append(PlanarPatch(refined, [samples[index] for index in component]))
            captured += len(component)
        used = set(best_indices)
        remaining = [index for index in remaining if index not in used]
        if len(remaining) / len(samples) <= 1.0 - minimum_coverage:
            break
    if captured / len(samples) < minimum_coverage:
        return []
    return patches


def _plane_from_three(
    a: tuple[float, float, float],
    b: tuple[float, float, float],
    c: tuple[float, float, float],
) -> Plane3 | None:
    u = tuple(b[index] - a[index] for index in range(3))
    v = tuple(c[index] - a[index] for index in range(3))
    normal = (
        u[1] * v[2] - u[2] * v[1],
        u[2] * v[0] - u[0] * v[2],
        u[0] * v[1] - u[1] * v[0],
    )
    length = math.sqrt(sum(value * value for value in normal))
    if length < 1e-8:
        return None
    normal, d = _normalise_plane(normal, -sum(normal[index] * a[index] for index in range(3)))
    return Plane3(normal, d, 0.0, 0.0)


def _normalise_plane(
    normal: tuple[float, float, float], d: float
) -> tuple[tuple[float, float, float], float]:
    length = math.sqrt(sum(value * value for value in normal))
    if length < 1e-12:
        raise DepthSurfaceError("degenerate plane")
    normal = tuple(value / length for value in normal)
    d /= length
    for value in normal:
        if abs(value) > 1e-8:
            if value < 0:
                normal = tuple(-component for component in normal)
                d = -d
            break
    return normal, d


def _plane_distance(plane: Plane3, point: tuple[float, float, float]) -> float:
    return abs(sum(plane.normal[index] * point[index] for index in range(3)) + plane.d)


def _refine_plane(points: list[tuple[float, float, float]], seed: Plane3) -> Plane3:
    dependent = max(range(3), key=lambda index: abs(seed.normal[index]))
    independent = [index for index in range(3) if index != dependent]
    fit = _fit_y_plane([
        (point[independent[0]], point[dependent], point[independent[1]])
        for point in points
    ])
    if fit is None:
        return seed
    a, b, c = fit
    normal = [0.0, 0.0, 0.0]
    normal[dependent] = 1.0
    normal[independent[0]] = -a
    normal[independent[1]] = -b
    normalised, d = _normalise_plane(tuple(normal), -c)
    return Plane3(normalised, d, seed.inlier_fraction, seed.rms)


def _connected_sample_components(
    indices: list[int], samples: list[tuple[float, float, float]]
) -> list[list[int]]:
    by_pixel = {
        (int(samples[index][0] - 0.5), int(samples[index][1] - 0.5)): index
        for index in indices
    }
    remaining = set(by_pixel)
    components: list[list[int]] = []
    while remaining:
        first = remaining.pop()
        stack = [first]
        component = [by_pixel[first]]
        while stack:
            x, y = stack.pop()
            for neighbor in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
                if neighbor not in remaining:
                    continue
                remaining.remove(neighbor)
                stack.append(neighbor)
                component.append(by_pixel[neighbor])
        components.append(component)
    return components


def triangulate_planar_patches(
    patches: list[PlanarPatch],
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    triangles: list[dict[str, Any]] = []
    total_pixels = 0
    total_area = 0.0
    maximum_fill_ratio = 0.0
    maximum_rms = 0.0
    hull_vertices = 0
    for patch_index, patch in enumerate(patches):
        hull = opaque_hull(patch.samples)
        if len(hull) < 3:
            continue
        area = polygon_area(hull)
        fill_ratio = area / len(patch.samples)
        if fill_ratio > 1.15:
            raise DepthSurfaceError("planar patch convex hull would invent excessive coverage")
        points = [point_on_implicit_plane(u, v, patch.plane) for u, v in hull]
        if not all(math.isfinite(component) for point in points for component in point):
            raise DepthSurfaceError("non-finite planar patch point")
        if any(
            abs(point[0]) > 2.5 or not -1.0 <= point[1] <= 4.0 or abs(point[2]) > 2.5
            for point in points
        ):
            raise DepthSurfaceError("planar patch exceeds a conservative tile-local roof envelope")
        for index in range(1, len(points) - 1):
            triangles.append({
                "kind": "triangle",
                "points": [points[0], points[index], points[index + 1]],
                "evidence": "installed_depth_map_planar_patch_fit",
                "patch": patch_index,
            })
        total_pixels += len(patch.samples)
        total_area += area
        maximum_fill_ratio = max(maximum_fill_ratio, fill_ratio)
        maximum_rms = max(maximum_rms, patch.plane.rms)
        hull_vertices += len(hull)
    if not triangles:
        raise DepthSurfaceError("planar patches produced no triangles")
    return triangles, {
        "plane_patch_count": len(patches),
        "planar_inlier_pixels": total_pixels,
        "maximum_planar_rms": round(maximum_rms, 7),
        "opaque_hull_vertices": hull_vertices,
        "opaque_pixels": total_pixels,
        "opaque_hull_area": round(total_area, 3),
        "opaque_hull_fill_ratio": round(maximum_fill_ratio, 7),
    }


def point_on_implicit_plane(u: float, v: float, plane: Plane3) -> list[float]:
    start = depth_point(u, v, 0.0)
    end = depth_point(u, v, 1.0)
    direction = tuple(end[index] - start[index] for index in range(3))
    denominator = sum(plane.normal[index] * direction[index] for index in range(3))
    if abs(denominator) < 1e-8:
        raise DepthSurfaceError("planar surface is parallel to the source projection")
    distance = -(sum(plane.normal[index] * start[index] for index in range(3)) + plane.d)
    scale = distance / denominator
    return [round(start[index] + direction[index] * scale, 7) for index in range(3)]


def _stable_hash(value: Any) -> str:
    import hashlib
    encoded = json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _count(values: dict[str, int], key: str) -> None:
    values[key] = values.get(key, 0) + 1
