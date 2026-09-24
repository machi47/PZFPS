from __future__ import annotations

from collections import OrderedDict
import hashlib
import json
import math
from pathlib import Path
from typing import Any

from .asset_coverage import category
from .common import now_utc, sha256_file, write_json
from .depth_surfaces import (
    DepthSurfaceError,
    PlanarPatch,
    _stable_hash,
    _tile_samples,
    decode_png,
    fit_piecewise_planar_surfaces,
    opaque_rectangles,
    parse_depth_assignments,
    point_on_implicit_plane,
    read_png,
    split_tile_identity,
)
from .texture_packs import TexturePackError, read_indexed_pages
from .depth_surfaces import sprite_alpha_mask


_PROP_CATEGORIES = {"furniture_fixture", "lighting_fixture"}
_STRICT_DISTANCE = 0.018
_QUANTISED_DISTANCE = 0.025
_MINIMUM_COVERAGE = 0.98
_MAXIMUM_PLANES = 24
_MAXIMUM_CANDIDATES = 64
_MAXIMUM_RMS = 0.015
_TILE_WIDTH = 128
_TILE_HEIGHT = 256
_SOURCE_Y_SCALE = 64.0 * math.sqrt(1.5)
_WALL_ATTACHMENT_CLEARANCE = 0.002


def wall_attachment_properties(definition: dict[str, Any]) -> dict[str, Any]:
    """Return a cardinal wall anchor only when PZ declares one unambiguously.

    ``MoveType=WallObject`` by itself is not enough: counters, satellite dishes and
    other objects use that placement mode without declaring a wall face.  The installed
    tile definitions provide the stronger ``attachedN/S/E/W`` evidence for fixtures that
    really are owned by one wall boundary.
    """
    properties = definition.get("properties", {})
    if properties.get("MoveType") != "WallObject":
        return {}
    edges = [edge for edge in "NSEW" if f"attached{edge}" in properties]
    if len(edges) != 1:
        return {}
    edge = edges[0]
    return {
        "wall_attachment_edge": edge,
        "wall_attachment_evidence": f"MoveType=WallObject+attached{edge}",
    }


def wall_attachment_metrics(
    geometry: list[dict[str, Any]], attachment: dict[str, Any]
) -> dict[str, Any]:
    edge = str(attachment.get("wall_attachment_edge", ""))
    if not edge:
        return {}
    axis = 2 if edge in {"N", "S"} else 0
    values = [
        float(point[axis]) + 0.5
        for primitive in geometry
        for point in primitive.get("points", [])
    ]
    if not values:
        raise DepthSurfaceError("wall attachment has no source surface points")
    source_clearance = min(values) if edge in {"N", "W"} else 1.0 - max(values)
    translation = (
        _WALL_ATTACHMENT_CLEARANCE - source_clearance
        if edge in {"N", "W"}
        else source_clearance - _WALL_ATTACHMENT_CLEARANCE
    )
    return {
        "wall_attachment_source_clearance": round(source_clearance, 7),
        "wall_attachment_normal_extent": round(max(values) - min(values), 7),
        "wall_attachment_target_clearance": _WALL_ATTACHMENT_CLEARANCE,
        "wall_attachment_translation": round(translation, 7),
    }


class _DepthImageCache:
    """Small bounded cache: installed atlases are large and this compiler is batch I/O."""

    def __init__(self, capacity: int = 12):
        self.capacity = capacity
        self.values: OrderedDict[Path, Any] = OrderedDict()

    def get(self, path: Path):
        value = self.values.pop(path, None)
        if value is None:
            value = read_png(path)
        self.values[path] = value
        while len(self.values) > self.capacity:
            self.values.popitem(last=False)
        return value


def compile_planar_prop_surfaces(
    definitions_path: Path,
    assignments_path: Path,
    depthmaps: Path,
    textures_path: Path,
    map_usage_path: Path,
    output: Path,
    *,
    game_version: str,
    map_referenced_only: bool = False,
) -> dict[str, Any]:
    """Compile source-masked visible prop surfaces from PZ's installed depth evidence.

    PZ's ``tileGeometry`` boxes are interaction/collision support volumes, not necessarily
    visible meshes. Projecting a sprite over those boxes produced the reported open dressers,
    malformed sinks and giant wall fixtures. The installed depth atlas is a stronger source for
    the visible surface. We intersect it with the *actual sprite alpha* before fitting, because
    several depth entries intentionally cover a much larger support volume than the artwork.

    Output remains an honest single-view surface reconstruction. It replaces a rough authored
    support volume only for identities that meet the recorded coverage and residual gates; it
    does not claim unseen backs or interiors have been recovered.
    """
    definitions = json.loads(definitions_path.read_text(encoding="utf-8"))
    textures = json.loads(textures_path.read_text(encoding="utf-8"))
    map_usage = json.loads(map_usage_path.read_text(encoding="utf-8"))
    versions = {
        str(definitions.get("game_version", "")),
        str(textures.get("game_version", "")),
        str(map_usage.get("game_version", "")),
        game_version,
    }
    if len(versions) != 1 or "" in versions:
        raise DepthSurfaceError(
            f"prop surface inputs describe different/unknown game versions: {sorted(versions)}"
        )
    tile_definitions: dict[str, Any] = definitions.get("tiles", {})
    texture_records: dict[str, Any] = textures.get("textures", {})
    usage_records: dict[str, Any] = map_usage.get("identities", {})
    assignments = parse_depth_assignments(assignments_path)
    candidates = [
        identity
        for identity in sorted(texture_records)
        if category(identity) in _PROP_CATEGORIES
        and identity in tile_definitions
        and (not map_referenced_only or identity in usage_records)
    ]
    by_page: dict[str, list[str]] = {}
    for identity in candidates:
        by_page.setdefault(str(texture_records[identity]["page"]), []).append(identity)
    try:
        encoded_pages = read_indexed_pages(textures, by_page)
    except (KeyError, OSError, TexturePackError) as error:
        raise DepthSurfaceError(f"could not read indexed source alpha: {error}") from error

    compiled: dict[str, dict[str, Any]] = {}
    rejected_identities: dict[str, dict[str, str]] = {}
    rejected: dict[str, int] = {}
    used_depth_images: set[Path] = set()
    depth_cache = _DepthImageCache()
    # Palette variants commonly share both a depth target and an exact alpha mask. Cache only
    # after hashing the mask, so a visually different variant cannot silently inherit geometry.
    fit_cache: dict[tuple[str, int, bytes], tuple[list[dict[str, Any]], dict[str, Any]] | str] = {}

    def reject(identity: str, reason: str, target: str, detail: str = "") -> None:
        rejected[reason] = rejected.get(reason, 0) + 1
        record = {"reason": reason, "depth_target": target}
        if detail:
            record["detail"] = detail
        rejected_identities[identity] = record

    for page_name in sorted(by_page):
        page = decode_png(encoded_pages.pop(page_name), f"indexed texture page {page_name}")
        for identity in by_page[page_name]:
            attachment = wall_attachment_properties(tile_definitions[identity])
            target = assignments.get(identity, identity)
            target_definition = tile_definitions.get(target)
            if target_definition is not None:
                tileset = str(target_definition.get("tileset", ""))
                xy = target_definition.get("xy", [])
                if not tileset or len(xy) != 2:
                    reject(identity, "missing_depth_target_coordinates", target)
                    continue
                target_index: int | None = None
            else:
                tileset, target_index = split_tile_identity(target)
                if target_index is None:
                    reject(identity, "missing_depth_target_definition", target)
                    continue
            depth_path = depthmaps / f"DEPTH_{tileset}.png"
            if not depth_path.is_file():
                reject(identity, "missing_depth_image", target)
                continue
            source_mask = sprite_alpha_mask(texture_records[identity], {page_name: page})
            if len(source_mask) < 16:
                reject(identity, "empty_or_tiny_source_sprite", target)
                continue
            mask_bytes = b"".join(
                int(value).to_bytes(2, "little")
                for point in sorted(source_mask)
                for value in point
            )
            cache_key = (
                target,
                len(source_mask),
                hashlib.blake2b(mask_bytes, digest_size=12).digest(),
            )
            cached = fit_cache.get(cache_key)
            if isinstance(cached, str):
                reject(identity, cached, target)
                continue
            if cached is not None:
                geometry, properties = cached
                compiled[identity] = {
                    "geometry": geometry,
                    "properties": properties | {
                        "depth_target": target,
                        "source_alpha_pixels": len(source_mask),
                    } | attachment | wall_attachment_metrics(geometry, attachment),
                }
                continue

            image = depth_cache.get(depth_path)
            used_depth_images.add(depth_path)
            if target_definition is None:
                if image.width % _TILE_WIDTH:
                    reject(identity, "invalid_depth_atlas_width", target)
                    continue
                columns = image.width // _TILE_WIDTH
                assert target_index is not None
                xy = [target_index % columns, target_index // columns]
            origin_x = int(xy[0]) * _TILE_WIDTH
            origin_y = int(xy[1]) * _TILE_HEIGHT
            if (
                origin_x < 0
                or origin_y < 0
                or origin_x + _TILE_WIDTH > image.width
                or origin_y + _TILE_HEIGHT > image.height
            ):
                fit_cache[cache_key] = "depth_tile_outside_image"
                reject(identity, "depth_tile_outside_image", target)
                continue
            samples = [
                sample
                for sample in _tile_samples(image, origin_x, origin_y)
                if (int(sample[0] - 0.5), int(sample[1] - 0.5)) in source_mask
            ]
            depth_coverage = len(samples) / len(source_mask)
            if len(samples) < 64 or depth_coverage < _MINIMUM_COVERAGE:
                reason = "insufficient_source_mask_depth_coverage"
                fit_cache[cache_key] = reason
                reject(
                    identity,
                    reason,
                    target,
                    f"{len(samples)}/{len(source_mask)}={depth_coverage:.6f}",
                )
                continue
            patches = fit_piecewise_planar_surfaces(
                samples,
                distance_threshold=_STRICT_DISTANCE,
                minimum_coverage=_MINIMUM_COVERAGE,
                maximum_planes=_MAXIMUM_PLANES,
                maximum_candidates=_MAXIMUM_CANDIDATES,
            )
            fit_method = "piecewise_planar"
            threshold = _STRICT_DISTANCE
            if not patches:
                patches = fit_piecewise_planar_surfaces(
                    samples,
                    distance_threshold=_QUANTISED_DISTANCE,
                    minimum_coverage=_MINIMUM_COVERAGE,
                    maximum_planes=_MAXIMUM_PLANES,
                    maximum_candidates=_MAXIMUM_CANDIDATES,
                )
                fit_method = "piecewise_planar_quantised"
                threshold = _QUANTISED_DISTANCE
            if not patches or max(patch.plane.rms for patch in patches) > _MAXIMUM_RMS:
                reason = "not_piecewise_planar_prop_surface"
                fit_cache[cache_key] = reason
                reject(identity, reason, target)
                continue
            fitted_pixels = sum(len(patch.samples) for patch in patches)
            fitted_source_coverage = fitted_pixels / len(source_mask)
            if fitted_source_coverage < _MINIMUM_COVERAGE:
                reason = "insufficient_fitted_source_coverage"
                fit_cache[cache_key] = reason
                reject(
                    identity,
                    reason,
                    target,
                    f"{fitted_pixels}/{len(source_mask)}={fitted_source_coverage:.6f}",
                )
                continue
            try:
                geometry, properties = triangulate_prop_patches(patches)
            except DepthSurfaceError as error:
                reason = "unsafe_prop_surface_mesh"
                fit_cache[cache_key] = reason
                reject(identity, reason, target, str(error))
                continue
            properties |= {
                "depth_target": target,
                "depth_image": depth_path.name,
                "surface_method": fit_method,
                "fit_inlier_distance_threshold": threshold,
                "source_alpha_pixels": len(source_mask),
                "source_mask_depth_pixels": len(samples),
                "source_mask_depth_coverage": round(depth_coverage, 7),
                "fitted_source_pixels": fitted_pixels,
                "fitted_source_coverage": round(fitted_source_coverage, 7),
                # TileGeometry boxes are support volumes. This flag is consumed explicitly by
                # the Java registry merger; ordinary supplemental data still only fills holes.
                "replace_authored_geometry": True,
                "replacement_evidence": "installed_depth_map_intersected_with_exact_sprite_alpha",
            }
            fit_cache[cache_key] = (geometry, properties)
            compiled[identity] = {
                "geometry": geometry,
                "properties": properties | attachment | wall_attachment_metrics(
                    geometry, attachment
                ),
            }

    source_hashes = {
        "tile_definitions": sha256_file(definitions_path),
        "depth_assignments": sha256_file(assignments_path),
        "texture_index": sha256_file(textures_path),
        "texture_pack": str(textures.get("source_sha256", "")),
        "map_usage": sha256_file(map_usage_path),
        "depth_images": {
            path.name: sha256_file(path) for path in sorted(used_depth_images)
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
            "texture_index": str(textures_path),
            "map_usage": str(map_usage_path),
        },
        "source_sha256": _stable_hash(source_hashes),
        "source_hashes": source_hashes,
        "method": "source-alpha-masked-piecewise-planar-prop-depth-surface",
        "scope": "installed-map-referenced" if map_referenced_only else "all-installed",
        "candidate_count": len(candidates),
        "tile_count": len(compiled),
        "primitive_count": sum(len(value["geometry"]) for value in compiled.values()),
        "triangle_count": sum(
            2 if primitive.get("kind") == "quad" else 1
            for value in compiled.values()
            for primitive in value["geometry"]
        ),
        "wall_attachment_tile_count": sum(
            "wall_attachment_edge" in value["properties"]
            for value in compiled.values()
        ),
        "rejected": dict(sorted(rejected.items())),
        "rejected_identities": dict(sorted(rejected_identities.items())),
        "tiles": compiled,
    }
    document["audit"] = audit_prop_surfaces(document)
    write_json(output, document)
    return document


def triangulate_prop_patches(
    patches: list[PlanarPatch],
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    """Mesh exactly the fitted alpha pixels; never bridge handles, legs or sink openings."""
    triangles: list[dict[str, Any]] = []
    rectangle_count = 0
    fitted_pixels = 0
    maximum_rms = 0.0
    for patch_index, patch in enumerate(patches):
        rectangles = opaque_rectangles(patch.samples)
        rectangle_count += len(rectangles)
        fitted_pixels += len(patch.samples)
        maximum_rms = max(maximum_rms, patch.plane.rms)
        for left, top, right, bottom in rectangles:
            source = [(left, top), (right, top), (right, bottom), (left, bottom)]
            points = [point_on_implicit_plane(u, v, patch.plane) for u, v in source]
            if not all(math.isfinite(value) for point in points for value in point):
                raise DepthSurfaceError("non-finite prop surface point")
            if any(
                abs(point[0]) > 3.5
                or not -1.5 <= point[1] <= 5.0
                or abs(point[2]) > 3.5
                for point in points
            ):
                raise DepthSurfaceError("prop patch exceeds conservative tile-local envelope")
            triangles.append({
                "kind": "quad",
                "points": points,
                "evidence": "installed_depth_map_and_source_alpha",
                "patch": patch_index,
                "mesh_method": "opaque_mask_rectangles",
            })
    if not triangles:
        raise DepthSurfaceError("prop patches produced no triangles")
    return triangles, {
        "plane_patch_count": len(patches),
        "opaque_mask_rectangle_count": rectangle_count,
        "fitted_source_pixels": fitted_pixels,
        "maximum_planar_rms": round(maximum_rms, 7),
    }


def audit_prop_surfaces(document: dict[str, Any]) -> dict[str, Any]:
    tile_count = 0
    triangle_count = 0
    minimum_area = math.inf
    maximum_projection_error = 0.0
    minimum_coverage = 1.0
    wall_attachment_count = 0
    minimum_wall_source_clearance = math.inf
    maximum_wall_source_clearance = -math.inf
    maximum_wall_translation = 0.0
    for identity, record in document.get("tiles", {}).items():
        properties = record.get("properties", {})
        if not properties.get("replace_authored_geometry"):
            raise DepthSurfaceError(f"prop surface is not an explicit override: {identity}")
        coverage = float(properties.get("fitted_source_coverage", 0.0))
        if coverage < _MINIMUM_COVERAGE:
            raise DepthSurfaceError(f"prop surface misses source coverage gate: {identity}")
        minimum_coverage = min(minimum_coverage, coverage)
        geometry = record.get("geometry", [])
        if not geometry:
            raise DepthSurfaceError(f"compiled prop has no geometry: {identity}")
        tile_count += 1
        edge = str(properties.get("wall_attachment_edge", ""))
        if edge:
            if edge not in {"N", "S", "E", "W"}:
                raise DepthSurfaceError(f"invalid wall attachment edge: {identity}")
            if float(properties.get("wall_attachment_target_clearance", -1.0)) != _WALL_ATTACHMENT_CLEARANCE:
                raise DepthSurfaceError(f"invalid wall attachment target clearance: {identity}")
            source_clearance = float(properties["wall_attachment_source_clearance"])
            translation = float(properties["wall_attachment_translation"])
            wall_attachment_count += 1
            minimum_wall_source_clearance = min(
                minimum_wall_source_clearance, source_clearance
            )
            maximum_wall_source_clearance = max(
                maximum_wall_source_clearance, source_clearance
            )
            maximum_wall_translation = max(maximum_wall_translation, abs(translation))
        for triangle in geometry:
            points = triangle.get("points", [])
            kind = triangle.get("kind")
            if kind not in {"triangle", "quad"} or len(points) != (4 if kind == "quad" else 3):
                raise DepthSurfaceError(f"malformed prop surface primitive: {identity}")
            indices = ((0, 1, 2), (0, 2, 3)) if kind == "quad" else ((0, 1, 2),)
            for first, second, third in indices:
                a, b, c = points[first], points[second], points[third]
                cross = (
                    (b[1] - a[1]) * (c[2] - a[2]) - (b[2] - a[2]) * (c[1] - a[1]),
                    (b[2] - a[2]) * (c[0] - a[0]) - (b[0] - a[0]) * (c[2] - a[2]),
                    (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]),
                )
                area = math.sqrt(sum(value * value for value in cross)) * 0.5
                if area <= 1e-10:
                    raise DepthSurfaceError(f"degenerate prop surface triangle: {identity}")
                minimum_area = min(minimum_area, area)
            for x, y, z in points:
                u = 64.0 + (x - z) * 64.0
                v = 224.0 + (x + z) * 32.0 - y * _SOURCE_Y_SCALE
                outside = max(0.0, -u, u - _TILE_WIDTH, -v, v - _TILE_HEIGHT)
                maximum_projection_error = max(maximum_projection_error, outside)
                if outside > 1e-4:
                    raise DepthSurfaceError(
                        f"prop surface projects outside source tile: {identity} ({u}, {v})"
                    )
            triangle_count += 2 if kind == "quad" else 1
    if tile_count != int(document.get("tile_count", -1)):
        raise DepthSurfaceError("compiled prop tile count does not match output")
    if triangle_count != int(document.get("triangle_count", -1)):
        raise DepthSurfaceError("compiled prop triangle count does not match output")
    if wall_attachment_count != int(document.get("wall_attachment_tile_count", -1)):
        raise DepthSurfaceError("compiled wall attachment count does not match output")
    return {
        "tile_count": tile_count,
        "triangle_count": triangle_count,
        "minimum_triangle_area": 0.0 if math.isinf(minimum_area) else minimum_area,
        "maximum_source_projection_error": maximum_projection_error,
        "minimum_fitted_source_coverage": minimum_coverage if tile_count else 0.0,
        "wall_attachment_tile_count": wall_attachment_count,
        "wall_attachment_target_clearance": _WALL_ATTACHMENT_CLEARANCE,
        "minimum_wall_attachment_source_clearance": (
            0.0 if math.isinf(minimum_wall_source_clearance)
            else minimum_wall_source_clearance
        ),
        "maximum_wall_attachment_source_clearance": (
            0.0 if math.isinf(maximum_wall_source_clearance)
            else maximum_wall_source_clearance
        ),
        "maximum_wall_attachment_translation": maximum_wall_translation,
    }
