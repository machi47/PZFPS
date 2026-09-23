import json
import tempfile
import unittest
from pathlib import Path

from pzfps.asset_coverage import build_coverage, build_scene_coverage, category, renderer_rule


class AssetCoverageTests(unittest.TestCase):
    def test_report_joins_tiles_textures_and_models_without_claiming_acceptance(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            geometry = root / "geometry.json"
            textures = root / "textures.json"
            models = root / "models.json"
            geometry.write_text(json.dumps({
                "schema_version": 1,
                "game_version": "42.20",
                "tiles": {
                    "roofs_02_3": {"geometry": []},
                    "furniture_storage_02_36": {"geometry": [{"kind": "box"}]},
                },
            }))
            textures.write_text(json.dumps({
                "schema_version": 1,
                "game_version": "42.20",
                "textures": {"roofs_02_3": {}, "texture_only_0": {}},
            }))
            models.write_text(json.dumps({
                "schema_version": 1,
                "game_version": "42.20",
                "models": {"Base.Item": {"mesh_path": "/mesh", "texture_path": "/texture"}},
            }))

            definitions = root / "definitions.json"
            definitions.write_text(json.dumps({
                "schema_version": 1,
                "game_version": "42.20",
                "tiles": {"roofs_02_3": {"properties": {"RoofGroup": "3"}}},
            }))
            depth_surfaces = root / "roof-surfaces.json"
            depth_surfaces.write_text(json.dumps({
                "schema_version": 1,
                "game_version": "42.20",
                "rejected_identities": {
                    "roofs_rejected_0": {
                        "reason": "not_planar_surface",
                        "depth_target": "preset_depthmaps_01_7",
                        "detail": "fixture diagnostic",
                    },
                },
                "skipped_identities": {
                    "walls_exterior_roofs_05_18": {
                        "reason": "empty_source_placeholder",
                        "evidence": "one_pixel_texture_placeholder",
                    },
                },
                "tiles": {"roofs_02_3": {"geometry": [{"kind": "triangle"}]}},
            }))
            items = root / "items.json"
            items.write_text(json.dumps({
                "schema_version": 1,
                "game_version": "42.20",
                "items": {
                    "Base.Hammer": {
                        "item_type": "base:weapon",
                        "icon": "Hammer",
                        "world_model": "Hammer",
                        "resolved_model_identity": "Base.Item",
                        "world_model_resolved": True,
                    },
                    "Base.Paper": {
                        "item_type": "base:normal",
                        "icon": "Paper",
                        "world_model": "",
                        "resolved_model_identity": "",
                        "world_model_resolved": False,
                    },
                },
            }))
            map_usage = root / "map-usage.json"
            map_usage.write_text(json.dumps({
                "schema_version": 1,
                "game_version": "42.20",
                "identities": {
                    "texture_only_0": {
                        "header_count": 2,
                        "map_directories": ["Muldraugh, KY"],
                    },
                },
            }))
            report = build_coverage(
                geometry, textures, models, definitions, depth_surfaces, items, map_usage)
            rows = {row["identity"]: row for row in report["tile_texture_identities"]}
            self.assertEqual(
                rows["roofs_02_3"]["coverage_state"],
                "implemented_pending_live_acceptance",
            )
            self.assertEqual(rows["roofs_02_3"]["renderer_rule"], "installed_depth_planar_surface")
            self.assertEqual(rows["roofs_02_3"]["depth_surface_primitive_count"], 1)
            self.assertEqual(rows["roofs_02_3"]["depth_surface_rejection_reason"], "")
            self.assertEqual(
                rows["roofs_rejected_0"]["depth_surface_rejection_reason"],
                "not_planar_surface",
            )
            self.assertEqual(
                rows["roofs_rejected_0"]["depth_surface_target"],
                "preset_depthmaps_01_7",
            )
            self.assertEqual(
                rows["roofs_rejected_0"]["depth_surface_rejection_detail"],
                "fixture diagnostic",
            )
            self.assertEqual(rows["roofs_02_3"]["source_properties"]["RoofGroup"], "3")
            self.assertEqual(
                rows["walls_exterior_roofs_05_18"]["coverage_state"],
                "not_applicable_source_placeholder",
            )
            self.assertEqual(
                rows["walls_exterior_roofs_05_18"]["depth_surface_skip_reason"],
                "empty_source_placeholder",
            )
            self.assertEqual(rows["furniture_storage_02_36"]["coverage_state"], "implemented_unaccepted")
            self.assertEqual(rows["texture_only_0"]["coverage_state"], "unsupported_unless_native_runtime_path")
            self.assertTrue(rows["texture_only_0"]["in_installed_map_headers"])
            self.assertEqual(rows["texture_only_0"]["installed_map_header_count"], 2)
            self.assertEqual(report["summary"]["model_states"], {"native_model_resolved": 1})
            self.assertEqual(report["summary"]["identities_with_depth_surfaces"], 1)
            self.assertEqual(report["summary"]["depth_surface_rejection_reasons"], {
                "not_planar_surface": 1,
            })
            self.assertEqual(report["summary"]["depth_surface_skip_reasons"], {
                "empty_source_placeholder": 1,
            })
            self.assertEqual(report["summary"]["item_identities"], 2)
            self.assertEqual(report["summary"]["installed_map_referenced_identities"], 1)
            self.assertEqual(report["summary"]["atlas_only_map_referenced_identities"], 1)
            self.assertEqual(report["summary"]["map_referenced_tile_states"], {
                "unsupported_unless_native_runtime_path": 1,
            })
            self.assertEqual(
                report["summary"]["map_referenced_by_category"]["other"]["total"], 1)
            self.assertEqual(report["summary"]["item_states"], {
                "native_world_model_resolved": 1,
                "no_world_model_declared": 1,
            })

    def test_known_problem_families_are_explicit(self) -> None:
        self.assertEqual(category("walls_exterior_roofs_10_5"), "roof")
        self.assertEqual(renderer_rule("fixtures_doors_01_44", 1)[1], "rejected_not_volumetric")
        self.assertEqual(renderer_rule("fixtures_windows_01_24", 0)[1], "rejected_not_physical")
        self.assertEqual(renderer_rule("fencing_01_25", 0),
                         ("short_chainlink_boundary_panel", "implemented_unaccepted"))

    def test_scene_report_joins_runtime_instances_to_identity_coverage(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            coverage = root / "coverage.json"
            coverage.write_text(json.dumps({
                "game_version": "42.20",
                "tile_texture_identities": [{
                    "identity": "roofs_02_3",
                    "category": "roof",
                    "coverage_state": "implemented_pending_live_acceptance",
                    "renderer_rule": "installed_depth_planar_surface",
                    "depth_surface_rejection_reason": "",
                    "depth_surface_target": "roofs_02_3",
                }],
            }))
            scene = root / "scene.json"
            scene.write_text(json.dumps({"squares": [{
                "position": [10, 20, 1],
                "objects": [
                    {"sprite": "roofs_02_3", "java": "IsoObject", "kind": "MAX"},
                    {"sprite": "unknown_mod_1", "java": "IsoObject", "kind": "MAX"},
                ],
            }]}))
            report = build_scene_coverage(scene, coverage)
            self.assertEqual(report["summary"]["scene_objects"], 2)
            self.assertEqual(report["summary"]["scene_identities"], 2)
            self.assertEqual(report["summary"]["identities_absent_from_installed_corpus"], 1)
            self.assertEqual(report["summary"]["depth_surface_rejected_instances_by_reason"], {})
            roof = next(item for item in report["identities"] if item["identity"] == "roofs_02_3")
            self.assertEqual(roof["instances"], 1)
            self.assertEqual(roof["sample_positions"], [[10, 20, 1]])
            self.assertEqual(roof["depth_surface_target"], "roofs_02_3")


if __name__ == "__main__":
    unittest.main()
