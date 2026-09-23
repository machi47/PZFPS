from __future__ import annotations

import json
import math
from pathlib import Path
import struct
from typing import Any, Iterable
import zlib

from .asset_coverage import category
from .common import now_utc, sha256_file, write_json
from .texture_packs import TexturePackError, read_indexed_pages


class DepthSurfaceError(ValueError):
    pass


_PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
_TILE_WIDTH = 128
_TILE_HEIGHT = 256
_SOURCE_Y_SCALE = 64.0 * math.sqrt(1.5)
_DEPTH_Y_SCALE = 1.0 / (2.0 * math.sqrt(6.0))
_STRICT_PATCH_DISTANCE = 0.018
_QUANTISED_PATCH_DISTANCE = 0.025
_MAXIMUM_PATCH_RMS = 0.012
_SOURCE_EQUIVALENT_ROOF_FAMILIES = {
    "walls_exterior_roofs_30_21": "walls_exterior_roofs_30_19",
}


def compile_planar_roof_surfaces(
    definitions_path: Path,
    assignments_path: Path,
    depthmaps: Path,
    output: Path,
    *,
    game_version: str,
    textures_path: Path | None = None,
    map_usage_path: Path | None = None,
) -> dict[str, Any]:
    """Recover planar roof patches from PZ's own per-pixel depth evidence."""
    definitions = json.loads(definitions_path.read_text(encoding="utf-8"))
    if definitions.get("game_version") != game_version:
        raise DepthSurfaceError("tile definitions describe a different game version")
    textures = (
        json.loads(textures_path.read_text(encoding="utf-8"))
        if textures_path is not None else {"textures": {}}
    )
    if textures_path is not None and textures.get("game_version") != game_version:
        raise DepthSurfaceError("texture index describes a different game version")
    map_usage = (
        json.loads(map_usage_path.read_text(encoding="utf-8"))
        if map_usage_path is not None else {"identities": {}}
    )
    if map_usage_path is not None and map_usage.get("game_version") != game_version:
        raise DepthSurfaceError("map usage index describes a different game version")
    assignments = parse_depth_assignments(assignments_path)
    tiles: dict[str, Any] = definitions.get("tiles", {})
    texture_records: dict[str, Any] = textures.get("textures", {})
    images: dict[Path, PngPixels] = {}
    target_cache: dict[str, tuple[list[dict[str, Any]], dict[str, Any]] | str] = {}
    target_error_details: dict[str, str] = {}
    compiled: dict[str, dict[str, Any]] = {}
    rejected: dict[str, int] = {}
    rejected_identities: dict[str, dict[str, str]] = {}
    skipped: dict[str, int] = {}
    skipped_identities: dict[str, dict[str, str]] = {}

    def reject(identity: str, reason: str, target: str, detail: str = "") -> None:
        _count(rejected, reason)
        rejected_identities[identity] = {
            "reason": reason,
            "depth_target": target,
        }
        if detail:
            rejected_identities[identity]["detail"] = detail

    for identity, definition in sorted(tiles.items()):
        if category(identity) != "roof":
            continue
        placeholder_evidence = empty_source_placeholder_evidence(
            identity,
            definition,
            assignments,
            texture_records,
            texture_index_available=textures_path is not None,
        )
        if placeholder_evidence:
            _count(skipped, "empty_source_placeholder")
            skipped_identities[identity] = {
                "reason": "empty_source_placeholder",
                "evidence": placeholder_evidence,
            }
            continue
        target = assignments.get(identity, identity)
        if not has_physical_roof_anchor(identity, definition):
            # A depth assignment is an occlusion hint, not proof that the target sprite is
            # itself a square-anchored surface. PZ places ridge/overlay art on upper squares
            # (for example roofs_05_47) so it can extend down across lower roof sprites in the
            # isometric compositor. Reusing the assigned plane as local 3D geometry instead
            # produces the observed long strips suspended above the building. Fail closed
            # until those identities have a topology-aware ridge/accent assembly.
            reject(identity, "unanchored_roof_overlay", target)
            continue
        cached = target_cache.get(target)
        if isinstance(cached, str):
            reject(identity, cached, target, target_error_details.get(target, ""))
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
                reject(identity, "missing_depth_target_coordinates", target)
                continue
            target_index: int | None = None
        else:
            tileset, target_index = split_tile_identity(target)
            if target_index is None:
                target_cache[target] = "missing_depth_target_definition"
                reject(identity, "missing_depth_target_definition", target)
                continue
        depth_path = depthmaps / f"DEPTH_{tileset}.png"
        if not depth_path.is_file():
            target_cache[target] = "missing_depth_image"
            reject(identity, "missing_depth_image", target)
            continue
        image = images.get(depth_path)
        if image is None:
            image = read_png(depth_path)
            images[depth_path] = image
        if target_definition is None:
            if image.width % _TILE_WIDTH:
                target_cache[target] = "invalid_depth_atlas_width"
                reject(identity, "invalid_depth_atlas_width", target)
                continue
            columns = image.width // _TILE_WIDTH
            assert target_index is not None
            xy = [target_index % columns, target_index // columns]
        origin_x = int(xy[0]) * _TILE_WIDTH
        origin_y = int(xy[1]) * _TILE_HEIGHT
        if origin_x + _TILE_WIDTH > image.width or origin_y + _TILE_HEIGHT > image.height:
            target_cache[target] = "depth_tile_outside_image"
            reject(identity, "depth_tile_outside_image", target)
            continue
        samples = _tile_samples(image, origin_x, origin_y)
        if len(samples) < 64:
            target_cache[target] = "empty_or_tiny_depth_tile"
            reject(identity, "empty_or_tiny_depth_tile", target)
            continue
        plane = fit_planar_surface(samples)
        if plane is not None:
            patches = [PlanarPatch(
                _implicit_from_y_plane(plane),
                samples,
            )]
            surface_method = "single_plane"
            fit_inlier_distance_threshold = 0.03
        else:
            patches, surface_method, fit_inlier_distance_threshold = fit_roof_planar_patches(samples)
        if not patches:
            target_cache[target] = "not_planar_surface"
            reject(identity, "not_planar_surface", target)
            continue
        try:
            triangles, patch_properties = triangulate_planar_patches(patches)
        except DepthSurfaceError as error:
            detail = str(error)
            reason = (
                "unsafe_tile_local_envelope"
                if "conservative tile-local roof envelope" in detail
                else "unsafe_planar_patch_mesh"
            )
            target_cache[target] = reason
            target_error_details[target] = detail
            reject(identity, reason, target, detail)
            continue
        properties = {
            "depth_target": target,
            "depth_image": depth_path.name,
            "surface_method": surface_method,
            "fit_inlier_distance_threshold": fit_inlier_distance_threshold,
            **patch_properties,
        }
        target_cache[target] = (triangles, properties)
        compiled[identity] = {
            "geometry": triangles,
            "properties": properties,
        }

    source_equivalent_aliases = compile_source_equivalent_roof_aliases(
        compiled,
        texture_records,
        textures,
        map_usage.get("identities", {}),
    )
    compiled.update(source_equivalent_aliases)

    source_hashes = {
        "tile_definitions": sha256_file(definitions_path),
        "depth_assignments": sha256_file(assignments_path),
        "depth_images": {
            path.name: sha256_file(path) for path in sorted(images)
        },
    }
    if textures_path is not None:
        source_hashes["texture_index"] = sha256_file(textures_path)
    if map_usage_path is not None:
        source_hashes["map_usage"] = sha256_file(map_usage_path)
    document = {
        "schema_version": 1,
        "generated_at": now_utc(),
        "game_version": game_version,
        "source": {
            "tile_definitions": str(definitions_path),
            "depth_assignments": str(assignments_path),
            "depthmaps": str(depthmaps),
            "texture_index": str(textures_path) if textures_path is not None else "not supplied",
            "map_usage": str(map_usage_path) if map_usage_path is not None else "not supplied",
        },
        "source_sha256": _stable_hash(source_hashes),
        "source_hashes": source_hashes,
        "method": "strict-planar-patch-fit-from-installed-depth-texture",
        "source_equivalent_alias_count": len(source_equivalent_aliases),
        "tile_count": len(compiled),
        "triangle_count": sum(len(value["geometry"]) for value in compiled.values()),
        "rejected": dict(sorted(rejected.items())),
        "rejected_identities": dict(sorted(rejected_identities.items())),
        "skipped": dict(sorted(skipped.items())),
        "skipped_identities": dict(sorted(skipped_identities.items())),
        "tiles": compiled,
    }
    document["audit"] = audit_roof_surfaces(document)
    write_json(output, document)
    return document


def compile_source_equivalent_roof_aliases(
    compiled: dict[str, dict[str, Any]],
    texture_records: dict[str, Any],
    texture_index: dict[str, Any],
    map_usage_records: dict[str, Any],
) -> dict[str, dict[str, Any]]:
    """Recover map-used atlas-only variants from proven source-equivalent families.

    Build 42.20 ships ``walls_exterior_roofs_30_21`` in the atlas and names four of its
    pieces in Muldraugh map headers, but omits the family from tile definitions and geometry.
    Its matching ``30_19`` pieces have the same original frame and a near-identical alpha
    silhouette. We only inherit geometry when the destination is map-referenced and its
    opaque mask is a strict subset of the source with no more than a one-pixel border removed.
    """
    candidates: list[tuple[str, str]] = []
    for identity in sorted(map_usage_records):
        family, suffix = split_tile_identity(identity)
        source_family = _SOURCE_EQUIVALENT_ROOF_FAMILIES.get(family)
        if source_family is None or suffix is None or identity in compiled:
            continue
        source_identity = f"{source_family}_{suffix}"
        if (
            source_identity in compiled
            and identity in texture_records
            and source_identity in texture_records
        ):
            candidates.append((identity, source_identity))
    if not candidates:
        return {}

    page_names = {
        str(texture_records[identity]["page"])
        for pair in candidates
        for identity in pair
    }
    try:
        page_bytes = read_indexed_pages(texture_index, page_names)
    except (KeyError, OSError, TexturePackError) as error:
        raise DepthSurfaceError(f"could not verify source-equivalent roof masks: {error}") from error
    pages = {
        name: decode_png(data, f"indexed texture page {name}")
        for name, data in page_bytes.items()
    }
    aliases: dict[str, dict[str, Any]] = {}
    for identity, source_identity in candidates:
        destination_record = texture_records[identity]
        source_record = texture_records[source_identity]
        destination_mask = sprite_alpha_mask(destination_record, pages)
        source_mask = sprite_alpha_mask(source_record, pages)
        if not destination_mask or not source_mask:
            continue
        removed = source_mask - destination_mask
        added = destination_mask - source_mask
        retained_fraction = len(destination_mask) / len(source_mask)
        # The verified family differs by a one-pixel trim: it may remove at most one full
        # tile-width row/column, but may never add unsupported opaque pixels.
        if added or len(removed) > _TILE_WIDTH or retained_fraction < 0.90:
            continue
        source = compiled[source_identity]
        aliases[identity] = {
            "geometry": source["geometry"],
            "properties": source["properties"] | {
                "source_equivalent_identity": source_identity,
                "source_equivalent_evidence": "installed_atlas_alpha_subset_and_map_header_reference",
                "source_alpha_pixels": len(source_mask),
                "destination_alpha_pixels": len(destination_mask),
                "removed_border_pixels": len(removed),
                "retained_alpha_fraction": round(retained_fraction, 7),
                "installed_map_header_count": int(
                    map_usage_records[identity].get("header_count", 0)
                ),
            },
        }
    return aliases


def sprite_alpha_mask(
    texture: dict[str, Any], pages: dict[str, "PngPixels"]
) -> set[tuple[int, int]]:
    """Return an atlas sprite's alpha mask in its original-frame coordinates."""
    page_name = str(texture["page"])
    page = pages[page_name]
    atlas_x = int(texture["x"])
    atlas_y = int(texture["y"])
    width = int(texture["width"])
    height = int(texture["height"])
    offset_x = int(texture["offset_x"])
    offset_y = int(texture["offset_y"])
    original_width = int(texture["original_width"])
    original_height = int(texture["original_height"])
    if original_width != _TILE_WIDTH or original_height != _TILE_HEIGHT:
        return set()
    if (
        atlas_x < 0
        or atlas_y < 0
        or atlas_x + width > page.width
        or atlas_y + height > page.height
    ):
        raise DepthSurfaceError(f"sprite crop lies outside indexed page: {page_name}")
    return {
        (offset_x + x, offset_y + y)
        for y in range(height)
        for x in range(width)
        if page.alpha[(atlas_y + y) * page.width + atlas_x + x] != 0
    }


def empty_source_placeholder_evidence(
    identity: str,
    definition: dict[str, Any],
    assignments: dict[str, str],
    texture_records: dict[str, Any],
    *,
    texture_index_available: bool,
) -> str:
    """Identify intentionally empty exterior-roof slots from installed-source evidence.

    Build 42.20 contains eight ``walls_exterior_roofs_05`` definition slots whose only
    property is a burnt-tile fallback. They have no depth assignment and are either absent
    from the texture atlas or represented by a 1x1 placeholder. Treating those slots as
    missing geometry inflates the unresolved roof count and invites fabricated surfaces.
    """
    if not texture_index_available or not identity.startswith("walls_exterior_roofs_"):
        return ""
    if set(definition.get("properties", {})) != {"BurntTile"} or identity in assignments:
        return ""
    texture = texture_records.get(identity)
    if texture is None:
        return "absent_from_texture_atlas; burnt_fallback_only; no_depth_assignment"
    if int(texture.get("width", 0)) <= 1 and int(texture.get("height", 0)) <= 1:
        return "one_pixel_texture_placeholder; burnt_fallback_only; no_depth_assignment"
    return ""


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
    return decode_png(path.read_bytes(), str(path))


def decode_png(data: bytes, source: str) -> PngPixels:
    if not data.startswith(_PNG_SIGNATURE):
        raise DepthSurfaceError(f"not a PNG: {source}")
    position = len(_PNG_SIGNATURE)
    header: tuple[int, int, int, int, int, int, int] | None = None
    compressed = bytearray()
    palette = b""
    transparency = b""
    while position < len(data):
        if position + 12 > len(data):
            raise DepthSurfaceError(f"truncated PNG chunk in {source}")
        length = struct.unpack(">I", data[position:position + 4])[0]
        kind = data[position + 4:position + 8]
        payload = data[position + 8:position + 8 + length]
        if len(payload) != length:
            raise DepthSurfaceError(f"truncated PNG payload in {source}")
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
        raise DepthSurfaceError(f"PNG has no IHDR: {source}")
    width, height, bit_depth, color_type, compression, filtering, interlace = header
    if bit_depth != 8 or compression != 0 or filtering != 0 or interlace != 0:
        raise DepthSurfaceError(f"unsupported PNG encoding in {source}")
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}.get(color_type)
    if channels is None:
        raise DepthSurfaceError(f"unsupported PNG color type {color_type} in {source}")
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
                    raise DepthSurfaceError(f"palette index outside PLTE in {source}")
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


def opaque_rectangles(
    samples: list[tuple[float, float, float]],
) -> list[tuple[int, int, int, int]]:
    """Cover an opaque pixel mask exactly with vertically merged scanline runs.

    Each returned rectangle is ``(left, top, right, bottom)`` with exclusive right/bottom
    bounds. Unlike a convex hull, this can never bridge a transparent notch or hole. Runs
    only merge across adjacent rows when their horizontal extent is identical, keeping the
    decomposition deterministic and bounded by the number of source scanline runs.
    """
    by_row: dict[int, set[int]] = {}
    for u, v, _ in samples:
        by_row.setdefault(int(v - 0.5), set()).add(int(u - 0.5))
    rectangles: list[tuple[int, int, int, int]] = []
    active: dict[tuple[int, int], tuple[int, int, int, int]] = {}
    previous_y: int | None = None
    for y in sorted(by_row):
        if previous_y is None or y != previous_y + 1:
            rectangles.extend(active.values())
            active = {}
        xs = sorted(by_row[y])
        runs: list[tuple[int, int]] = []
        if xs:
            start = end = xs[0]
            for x in xs[1:]:
                if x == end + 1:
                    end = x
                else:
                    runs.append((start, end + 1))
                    start = end = x
            runs.append((start, end + 1))
        next_active: dict[tuple[int, int], tuple[int, int, int, int]] = {}
        for run in runs:
            prior = active.pop(run, None)
            if prior is None:
                next_active[run] = (run[0], y, run[1], y + 1)
            else:
                next_active[run] = (prior[0], prior[1], prior[2], y + 1)
        rectangles.extend(active.values())
        active = next_active
        previous_y = y
    rectangles.extend(active.values())
    return rectangles


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


def fit_roof_planar_patches(
    samples: list[tuple[float, float, float]],
) -> tuple[list[PlanarPatch], str, float]:
    """Fit quantised installed roof depth without silently accepting coarse geometry.

    Most installed depth tiles meet the strict 0.018-unit residual threshold. A small set of
    compound hips, fascia and preset structural depths land just outside that threshold after
    their 8-bit depth quantisation. Retry those at 0.025, but retain the same 95% coverage gate
    and reject any fitted patch whose RMS error exceeds the single-plane compiler's 0.012-unit
    limit. The output records which path was used, so relaxed evidence remains auditable per
    identity rather than becoming an invisible global tolerance change.
    """
    patches = fit_piecewise_planar_surfaces(
        samples,
        distance_threshold=_STRICT_PATCH_DISTANCE,
    )
    if patches:
        return patches, "piecewise_planar", _STRICT_PATCH_DISTANCE
    patches = fit_piecewise_planar_surfaces(
        samples,
        distance_threshold=_QUANTISED_PATCH_DISTANCE,
    )
    if not patches or any(patch.plane.rms > _MAXIMUM_PATCH_RMS for patch in patches):
        return [], "", _QUANTISED_PATCH_DISTANCE
    return patches, "piecewise_planar_quantised", _QUANTISED_PATCH_DISTANCE


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
    mask_rectangles = 0
    for patch_index, patch in enumerate(patches):
        hull = opaque_hull(patch.samples)
        if len(hull) < 3:
            continue
        area = polygon_area(hull)
        fill_ratio = area / len(patch.samples)
        polygons = [hull]
        mesh_method = "convex_hull"
        if fill_ratio > 1.15:
            # The plane is supported, but a convex hull would bridge transparent regions
            # in compound roof silhouettes. Decompose the exact alpha mask into rectangles
            # instead. This is more geometry, but it preserves the installed evidence and
            # eliminates the previous all-or-nothing hole for concave roof pieces.
            polygons = [
                [(left, top), (right, top), (right, bottom), (left, bottom)]
                for left, top, right, bottom in opaque_rectangles(patch.samples)
            ]
            mesh_method = "opaque_mask_rectangles"
            mask_rectangles += len(polygons)
            area = float(len(patch.samples))
            fill_ratio = 1.0
        for polygon in polygons:
            points = [point_on_implicit_plane(u, v, patch.plane) for u, v in polygon]
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
                    "mesh_method": mesh_method,
                })
        total_pixels += len(patch.samples)
        total_area += area
        maximum_fill_ratio = max(maximum_fill_ratio, fill_ratio)
        maximum_rms = max(maximum_rms, patch.plane.rms)
        hull_vertices += sum(len(polygon) for polygon in polygons)
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
        "opaque_mask_rectangle_count": mask_rectangles,
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
