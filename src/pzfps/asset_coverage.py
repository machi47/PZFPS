from __future__ import annotations

from collections import Counter, defaultdict
import json
from pathlib import Path
from typing import Any

from .common import now_utc, write_json


EXACT_RULES: dict[str, tuple[str, str]] = {
    "carpentry_01_16": ("closed_crate_completion", "implemented_unaccepted"),
    "carpentry_01_19": ("closed_crate_completion", "implemented_unaccepted"),
    "location_business_machinery_01_32": ("opposite_side_completion", "implemented_unaccepted"),
    "location_business_machinery_01_33": ("opposite_side_completion", "implemented_unaccepted"),
    "location_business_machinery_01_34": ("opposite_side_completion", "implemented_unaccepted"),
    "location_business_machinery_01_35": ("opposite_side_completion", "implemented_unaccepted"),
    "furniture_storage_02_36": ("opposite_side_completion", "implemented_unaccepted"),
    "furniture_storage_02_37": ("opposite_side_completion", "implemented_unaccepted"),
}


def _suffix(name: str) -> int | None:
    try:
        return int(name.rsplit("_", 1)[1])
    except (IndexError, ValueError):
        return None


def category(name: str) -> str:
    lower = name.lower()
    if lower.startswith(("roofs_", "roofing_", "walls_exterior_roofs_")):
        return "roof"
    if lower.startswith("fixtures_doors_fences_"):
        return "gate"
    if lower.startswith("fixtures_doors_"):
        return "door"
    if lower.startswith("fixtures_windows_"):
        return "window"
    if lower.startswith("fencing_"):
        return "fence"
    if lower.startswith(("walls_", "wall_")):
        return "wall"
    if lower.startswith(("floors_", "floor_")):
        return "floor"
    if lower.startswith("lighting_"):
        return "lighting_fixture"
    if lower.startswith(("furniture_", "fixtures_counters_", "fixtures_sinks_", "appliances_")):
        return "furniture_fixture"
    if lower.startswith(("vegetation_", "e_newgrass_", "d_plants_", "blends_natural_")):
        return "vegetation"
    if lower.startswith(("street_", "industry_railroad_")):
        return "street"
    return "other"


def renderer_rule(name: str, geometry_count: int, depth_surface_count: int = 0) -> tuple[str, str]:
    exact = EXACT_RULES.get(name)
    if exact:
        return exact
    kind = category(name)
    suffix = _suffix(name)
    if name.startswith("lighting_indoor_01_") and suffix is not None and 0 <= suffix <= 3:
        return "shallow_wall_attachment", "implemented_unaccepted"
    if name.startswith("fencing_01_") and suffix is not None and 24 <= suffix <= 27:
        return "short_chainlink_boundary_panel", "implemented_unaccepted"
    if kind == "roof" and geometry_count:
        return "installed_authored_geometry", "implemented_unaccepted"
    if kind == "roof" and depth_surface_count:
        return "installed_depth_planar_surface", "implemented_pending_live_acceptance"
    if kind == "roof":
        return "no_roof_family_assembly", "rejected_or_unsupported"
    if kind == "door":
        return "generic_state_edge_card", "rejected_not_volumetric"
    if kind == "window":
        return "generic_translucent_edge_card", "rejected_not_physical"
    if kind in {"fence", "gate"}:
        return (
            "direct_source_geometry" if geometry_count else "generic_edge_card_when_runtime_flags_exist",
            "unaccepted",
        )
    if kind == "wall":
        return (
            "direct_source_geometry" if geometry_count else "generic_edge_card_when_runtime_flags_exist",
            "partial_unaccepted",
        )
    if kind == "floor":
        return "runtime_source_floor_card", "partial_unaccepted"
    if geometry_count:
        return "direct_source_geometry", "unaccepted"
    return "no_static_geometry_path", "unsupported_unless_native_runtime_path"


def build_coverage(
    geometry_path: Path,
    texture_path: Path,
    model_path: Path,
    definitions_path: Path | None = None,
    depth_surfaces_path: Path | None = None,
    items_path: Path | None = None,
) -> dict[str, Any]:
    geometry = json.loads(geometry_path.read_text(encoding="utf-8"))
    textures = json.loads(texture_path.read_text(encoding="utf-8"))
    models = json.loads(model_path.read_text(encoding="utf-8"))
    definitions = (
        json.loads(definitions_path.read_text(encoding="utf-8"))
        if definitions_path is not None else {"game_version": geometry.get("game_version"), "tiles": {}}
    )
    depth_surfaces = (
        json.loads(depth_surfaces_path.read_text(encoding="utf-8"))
        if depth_surfaces_path is not None else {"game_version": geometry.get("game_version"), "tiles": {}}
    )
    items = (
        json.loads(items_path.read_text(encoding="utf-8"))
        if items_path is not None else {"game_version": geometry.get("game_version"), "items": {}}
    )
    versions = {
        str(geometry.get("game_version", "")),
        str(textures.get("game_version", "")),
        str(models.get("game_version", "")),
        str(definitions.get("game_version", "")),
        str(depth_surfaces.get("game_version", "")),
        str(items.get("game_version", "")),
    }
    if len(versions) != 1 or "" in versions:
        raise ValueError(f"asset indexes describe different/unknown game versions: {sorted(versions)}")

    tile_records = geometry.get("tiles", {})
    texture_records = textures.get("textures", {})
    definition_records = definitions.get("tiles", {})
    depth_surface_records = depth_surfaces.get("tiles", {})
    identities = sorted(
        set(tile_records) | set(texture_records) | set(definition_records) | set(depth_surface_records)
    )
    rows: list[dict[str, Any]] = []
    by_category: dict[str, Counter[str]] = defaultdict(Counter)
    for identity in identities:
        record = tile_records.get(identity, {})
        primitives = record.get("geometry", [])
        depth_primitives = depth_surface_records.get(identity, {}).get("geometry", [])
        rule, state = renderer_rule(identity, len(primitives), len(depth_primitives))
        item = {
            "identity": identity,
            "category": category(identity),
            "in_geometry_registry": identity in tile_records,
            "in_texture_atlas": identity in texture_records,
            "in_tile_definitions": identity in definition_records,
            "primitive_count": len(primitives),
            "primitive_kinds": dict(sorted(Counter(value.get("kind", "unknown") for value in primitives).items())),
            "depth_surface_primitive_count": len(depth_primitives),
            "depth_surface_primitive_kinds": dict(sorted(
                Counter(value.get("kind", "unknown") for value in depth_primitives).items()
            )),
            "renderer_rule": rule,
            "coverage_state": state,
            "source_properties": definition_records.get(identity, {}).get("properties", {}),
        }
        rows.append(item)
        by_category[item["category"]][state] += 1

    model_rows = []
    model_states: Counter[str] = Counter()
    for identity, record in sorted(models.get("models", {}).items()):
        mesh = bool(record.get("mesh_path"))
        texture = bool(record.get("texture_path"))
        state = "native_model_resolved" if mesh and texture else (
            "missing_mesh" if not mesh else "missing_texture"
        )
        model_states[state] += 1
        model_rows.append({
            "identity": identity,
            "mesh": record.get("mesh", ""),
            "texture": record.get("texture", ""),
            "mesh_resolved": mesh,
            "texture_resolved": texture,
            "world_attachment_present": bool(record.get("world_attachment", {}).get("present")),
            "coverage_state": state,
        })

    item_rows = []
    item_states: Counter[str] = Counter()
    for identity, record in sorted(items.get("items", {}).items()):
        world_model = str(record.get("world_model", ""))
        resolved = bool(record.get("world_model_resolved"))
        state = "native_world_model_resolved" if resolved else (
            "no_world_model_declared" if not world_model else "world_model_unresolved"
        )
        item_states[state] += 1
        item_rows.append({
            "identity": identity,
            "item_type": record.get("item_type", "unknown"),
            "icon": record.get("icon", ""),
            "world_model": world_model,
            "resolved_model_identity": record.get("resolved_model_identity", ""),
            "coverage_state": state,
        })

    return {
        "schema_version": 1,
        "generated_at": now_utc(),
        "game_version": versions.pop(),
        "sources": {
            "geometry": str(geometry_path.resolve()),
            "textures": str(texture_path.resolve()),
            "models": str(model_path.resolve()),
            "tile_definitions": str(definitions_path.resolve()) if definitions_path is not None else "not supplied",
            "depth_surfaces": str(depth_surfaces_path.resolve()) if depth_surfaces_path is not None else "not supplied",
            "items": str(items_path.resolve()) if items_path is not None else "not supplied",
        },
        "summary": {
            "tile_geometry_identities": len(tile_records),
            "texture_identities": len(texture_records),
            "tile_definition_identities": len(definition_records),
            "joined_tile_texture_identities": len(rows),
            "identities_with_source_geometry": sum(row["primitive_count"] > 0 for row in rows),
            "identities_with_depth_surfaces": sum(
                row["depth_surface_primitive_count"] > 0 for row in rows
            ),
            "model_identities": len(model_rows),
            "item_identities": len(item_rows),
            "tile_states": dict(sorted(Counter(row["coverage_state"] for row in rows).items())),
            "model_states": dict(sorted(model_states.items())),
            "item_states": dict(sorted(item_states.items())),
            "by_category": {
                key: {"total": sum(value.values()), "states": dict(sorted(value.items()))}
                for key, value in sorted(by_category.items())
            },
        },
        "tile_texture_identities": rows,
        "model_identities": model_rows,
        "item_identities": item_rows,
    }


def write_coverage(
    geometry_path: Path,
    texture_path: Path,
    model_path: Path,
    output: Path,
    definitions_path: Path | None = None,
    depth_surfaces_path: Path | None = None,
    items_path: Path | None = None,
) -> dict[str, Any]:
    report = build_coverage(
        geometry_path,
        texture_path,
        model_path,
        definitions_path,
        depth_surfaces_path,
        items_path,
    )
    write_json(output, report)
    return report


def build_scene_coverage(scene_path: Path, coverage_path: Path) -> dict[str, Any]:
    scene = json.loads(scene_path.read_text(encoding="utf-8"))
    coverage = json.loads(coverage_path.read_text(encoding="utf-8"))
    indexed = {
        row["identity"]: row for row in coverage.get("tile_texture_identities", [])
    }
    identities: dict[str, dict[str, Any]] = {}
    state_counts: Counter[str] = Counter()
    category_counts: Counter[str] = Counter()
    object_count = 0
    for square in scene.get("squares", []):
        position = square.get("position", [])
        for obj in square.get("objects", []):
            object_count += 1
            identity = str(obj.get("sprite", "")).strip() or f"<class:{obj.get('java', 'unknown')}>"
            row = indexed.get(identity)
            state = row.get("coverage_state", "absent_from_installed_corpus") if row else "absent_from_installed_corpus"
            kind = row.get("category", category(identity)) if row else category(identity)
            state_counts[state] += 1
            category_counts[kind] += 1
            item = identities.setdefault(identity, {
                "identity": identity,
                "category": kind,
                "coverage_state": state,
                "renderer_rule": row.get("renderer_rule", "none") if row else "none",
                "instances": 0,
                "sample_positions": [],
                "java_types": set(),
                "object_kinds": set(),
            })
            item["instances"] += 1
            if len(item["sample_positions"]) < 8 and len(position) == 3:
                item["sample_positions"].append(position)
            item["java_types"].add(str(obj.get("java", "")))
            item["object_kinds"].add(str(obj.get("kind", "")))
    rows = []
    for item in identities.values():
        item["java_types"] = sorted(item["java_types"])
        item["object_kinds"] = sorted(item["object_kinds"])
        rows.append(item)
    rows.sort(key=lambda item: (item["category"], item["coverage_state"], item["identity"]))
    return {
        "schema_version": 1,
        "generated_at": now_utc(),
        "game_version": coverage.get("game_version", ""),
        "sources": {
            "scene": str(scene_path.resolve()),
            "coverage": str(coverage_path.resolve()),
        },
        "summary": {
            "scene_objects": object_count,
            "scene_identities": len(rows),
            "object_instances_by_state": dict(sorted(state_counts.items())),
            "object_instances_by_category": dict(sorted(category_counts.items())),
            "identities_absent_from_installed_corpus": sum(
                item["coverage_state"] == "absent_from_installed_corpus" for item in rows
            ),
        },
        "identities": rows,
    }


def write_scene_coverage(
    scene_path: Path, coverage_path: Path, output: Path
) -> dict[str, Any]:
    report = build_scene_coverage(scene_path, coverage_path)
    write_json(output, report)
    return report
