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


# Identity-level scopes for the owner's visual issue ledger.  These are broad
# work queues, not assertions that every member has reproduced the pictured
# defect.  Global renderer concerns (lighting, render distance, player body,
# and input) deliberately remain outside this mapping because attaching them
# to every sprite would create misleading per-asset counts.
VISUAL_ISSUE_CATEGORY_SCOPES: dict[str, tuple[str, ...]] = {
    "V-03": ("wall", "floor"),
    "V-04": ("furniture_fixture", "lighting_fixture"),
    "V-05": ("window", "door"),
    "V-06": ("window", "fence", "gate", "street", "lighting_fixture"),
    "V-07": ("furniture_fixture",),
    "V-08": ("lighting_fixture",),
    "V-09": ("door",),
    "V-11": ("fence", "gate"),
    "V-12": ("roof",),
}


def visual_issue_ids(kind: str) -> list[str]:
    """Return ledger issues whose declared identity scope includes ``kind``.

    Scope membership is useful for exhaustive backlog queries, but remains
    separate from live acceptance and from a confirmed defect observation.
    """
    return [
        issue_id
        for issue_id, categories in VISUAL_ISSUE_CATEGORY_SCOPES.items()
        if kind in categories
    ]


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


def renderer_rule(
    name: str,
    geometry_count: int,
    depth_surface_count: int = 0,
    *,
    source_placeholder: bool = False,
) -> tuple[str, str]:
    if source_placeholder:
        return "empty_installed_source_placeholder", "not_applicable_source_placeholder"
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
    map_usage_path: Path | None = None,
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
    map_usage = (
        json.loads(map_usage_path.read_text(encoding="utf-8"))
        if map_usage_path is not None else {"game_version": geometry.get("game_version"), "identities": {}}
    )
    versions = {
        str(geometry.get("game_version", "")),
        str(textures.get("game_version", "")),
        str(models.get("game_version", "")),
        str(definitions.get("game_version", "")),
        str(depth_surfaces.get("game_version", "")),
        str(items.get("game_version", "")),
        str(map_usage.get("game_version", "")),
    }
    if len(versions) != 1 or "" in versions:
        raise ValueError(f"asset indexes describe different/unknown game versions: {sorted(versions)}")

    tile_records = geometry.get("tiles", {})
    texture_records = textures.get("textures", {})
    definition_records = definitions.get("tiles", {})
    depth_surface_records = depth_surfaces.get("tiles", {})
    depth_surface_rejections = depth_surfaces.get("rejected_identities", {})
    depth_surface_skips = depth_surfaces.get("skipped_identities", {})
    map_usage_records = map_usage.get("identities", {})
    identities = sorted(
        set(tile_records)
        | set(texture_records)
        | set(definition_records)
        | set(depth_surface_records)
        | set(depth_surface_rejections)
        | set(depth_surface_skips)
        | set(map_usage_records)
    )
    rows: list[dict[str, Any]] = []
    by_category: dict[str, Counter[str]] = defaultdict(Counter)
    by_issue: dict[str, Counter[str]] = defaultdict(Counter)
    map_referenced_by_issue: dict[str, Counter[str]] = defaultdict(Counter)
    for identity in identities:
        record = tile_records.get(identity, {})
        primitives = record.get("geometry", [])
        depth_primitives = depth_surface_records.get(identity, {}).get("geometry", [])
        depth_rejection = depth_surface_rejections.get(identity, {})
        depth_skip = depth_surface_skips.get(identity, {})
        map_record = map_usage_records.get(identity, {})
        rule, state = renderer_rule(
            identity,
            len(primitives),
            len(depth_primitives),
            source_placeholder=bool(depth_skip),
        )
        kind = category(identity)
        issue_ids = visual_issue_ids(kind)
        item = {
            "identity": identity,
            "category": kind,
            "in_geometry_registry": identity in tile_records,
            "in_texture_atlas": identity in texture_records,
            "in_tile_definitions": identity in definition_records,
            "in_installed_map_headers": identity in map_usage_records,
            "installed_map_header_count": int(map_record.get("header_count", 0)),
            "installed_map_directories": map_record.get("map_directories", []),
            "primitive_count": len(primitives),
            "primitive_kinds": dict(sorted(Counter(value.get("kind", "unknown") for value in primitives).items())),
            "depth_surface_primitive_count": len(depth_primitives),
            "depth_surface_primitive_kinds": dict(sorted(
                Counter(value.get("kind", "unknown") for value in depth_primitives).items()
            )),
            "depth_surface_rejection_reason": depth_rejection.get("reason", ""),
            "depth_surface_rejection_detail": depth_rejection.get("detail", ""),
            "depth_surface_skip_reason": depth_skip.get("reason", ""),
            "depth_surface_skip_evidence": depth_skip.get("evidence", ""),
            "depth_surface_target": depth_rejection.get(
                "depth_target",
                depth_surface_records.get(identity, {}).get("properties", {}).get("depth_target", ""),
            ),
            "renderer_rule": rule,
            "coverage_state": state,
            "visual_issue_scope_ids": issue_ids,
            "source_properties": definition_records.get(identity, {}).get("properties", {}),
        }
        rows.append(item)
        by_category[item["category"]][state] += 1
        for issue_id in issue_ids:
            by_issue[issue_id][state] += 1
            if item["in_installed_map_headers"]:
                map_referenced_by_issue[issue_id][state] += 1

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
        "schema_version": 2,
        "generated_at": now_utc(),
        "game_version": versions.pop(),
        "sources": {
            "geometry": str(geometry_path.resolve()),
            "textures": str(texture_path.resolve()),
            "models": str(model_path.resolve()),
            "tile_definitions": str(definitions_path.resolve()) if definitions_path is not None else "not supplied",
            "depth_surfaces": str(depth_surfaces_path.resolve()) if depth_surfaces_path is not None else "not supplied",
            "items": str(items_path.resolve()) if items_path is not None else "not supplied",
            "map_usage": str(map_usage_path.resolve()) if map_usage_path is not None else "not supplied",
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
            "installed_map_referenced_identities": len(map_usage_records),
            "atlas_only_map_referenced_identities": sum(
                row["in_installed_map_headers"]
                and row["in_texture_atlas"]
                and not row["in_tile_definitions"]
                and not row["in_geometry_registry"]
                for row in rows
            ),
            "atlas_only_not_observed_in_map_headers": sum(
                not row["in_installed_map_headers"]
                and row["in_texture_atlas"]
                and not row["in_tile_definitions"]
                and not row["in_geometry_registry"]
                for row in rows
            ),
            "map_referenced_tile_states": dict(sorted(Counter(
                row["coverage_state"] for row in rows if row["in_installed_map_headers"]
            ).items())),
            "map_referenced_by_category": {
                name: {
                    "total": sum(
                        row["in_installed_map_headers"] and row["category"] == name
                        for row in rows
                    ),
                    "states": dict(sorted(Counter(
                        row["coverage_state"]
                        for row in rows
                        if row["in_installed_map_headers"] and row["category"] == name
                    ).items())),
                }
                for name in sorted({
                    row["category"] for row in rows if row["in_installed_map_headers"]
                })
            },
            "tile_states": dict(sorted(Counter(row["coverage_state"] for row in rows).items())),
            "model_states": dict(sorted(model_states.items())),
            "item_states": dict(sorted(item_states.items())),
            "depth_surface_rejection_reasons": dict(sorted(Counter(
                row["depth_surface_rejection_reason"] for row in rows
                if row["depth_surface_rejection_reason"]
            ).items())),
            "depth_surface_skip_reasons": dict(sorted(Counter(
                row["depth_surface_skip_reason"] for row in rows
                if row["depth_surface_skip_reason"]
            ).items())),
            "by_category": {
                key: {"total": sum(value.values()), "states": dict(sorted(value.items()))}
                for key, value in sorted(by_category.items())
            },
            "visual_issue_identity_scopes": {
                issue_id: {
                    "categories": list(VISUAL_ISSUE_CATEGORY_SCOPES[issue_id]),
                    "identity_count": sum(by_issue[issue_id].values()),
                    "states": dict(sorted(by_issue[issue_id].items())),
                    "installed_map_referenced_identity_count": sum(
                        map_referenced_by_issue[issue_id].values()
                    ),
                    "installed_map_referenced_states": dict(sorted(
                        map_referenced_by_issue[issue_id].items()
                    )),
                }
                for issue_id in sorted(VISUAL_ISSUE_CATEGORY_SCOPES)
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
    map_usage_path: Path | None = None,
) -> dict[str, Any]:
    report = build_coverage(
        geometry_path,
        texture_path,
        model_path,
        definitions_path,
        depth_surfaces_path,
        items_path,
        map_usage_path,
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
    issue_counts: Counter[str] = Counter()
    depth_rejection_counts: Counter[str] = Counter()
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
            issue_ids = (
                list(row.get("visual_issue_scope_ids", visual_issue_ids(kind)))
                if row else visual_issue_ids(kind)
            )
            for issue_id in issue_ids:
                issue_counts[issue_id] += 1
            if row and row.get("depth_surface_rejection_reason"):
                depth_rejection_counts[row["depth_surface_rejection_reason"]] += 1
            item = identities.setdefault(identity, {
                "identity": identity,
                "category": kind,
                "coverage_state": state,
                "renderer_rule": row.get("renderer_rule", "none") if row else "none",
                "visual_issue_scope_ids": issue_ids,
                "depth_surface_rejection_reason": (
                    row.get("depth_surface_rejection_reason", "") if row else ""
                ),
                "depth_surface_rejection_detail": (
                    row.get("depth_surface_rejection_detail", "") if row else ""
                ),
                "depth_surface_target": row.get("depth_surface_target", "") if row else "",
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
        "schema_version": 2,
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
            "object_instances_by_visual_issue_scope": dict(sorted(issue_counts.items())),
            "depth_surface_rejected_instances_by_reason": dict(
                sorted(depth_rejection_counts.items())
            ),
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
