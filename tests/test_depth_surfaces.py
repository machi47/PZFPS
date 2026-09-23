import json
import struct
import tempfile
import unittest
from unittest import mock
from pathlib import Path
import zlib

from pzfps.depth_surfaces import (
    compile_planar_roof_surfaces,
    compile_source_equivalent_roof_aliases,
    audit_roof_surfaces,
    atlas_only_tiny_placeholder_evidence,
    depth_point,
    empty_source_placeholder_evidence,
    fit_planar_surface,
    fit_piecewise_planar_surfaces,
    fit_roof_planar_patches,
    has_physical_roof_anchor,
    opaque_rectangles,
    parse_depth_assignments,
    parse_roof_seams,
    Plane3,
    PlanarPatch,
    point_on_implicit_plane,
    point_on_plane,
    polygon_area,
    PngPixels,
    read_png,
    split_tile_identity,
    triangulate_planar_patches,
)


def png_gray_alpha(width: int, height: int, pixels: list[tuple[int, int]]) -> bytes:
    raw = b"".join(b"\x00" + bytes(channel for pixel in pixels[y * width:(y + 1) * width] for channel in pixel)
                   for y in range(height))
    def chunk(kind: bytes, payload: bytes) -> bytes:
        body = kind + payload
        return struct.pack(">I", len(payload)) + body + struct.pack(">I", zlib.crc32(body))
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 4, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


class DepthSurfaceTests(unittest.TestCase):
    def test_atlas_only_tiny_roof_requires_all_absence_evidence(self) -> None:
        identity = "roofs_01_48"
        texture = {"width": 2, "height": 1}
        self.assertIn("atlas_crop_2x1", atlas_only_tiny_placeholder_evidence(
            identity, texture, {}, {}, {}))
        self.assertEqual(atlas_only_tiny_placeholder_evidence(
            identity, texture, {identity: {}}, {}, {}), "")
        self.assertEqual(atlas_only_tiny_placeholder_evidence(
            identity, {"width": 5, "height": 1}, {}, {}, {}), "")

    def test_map_used_source_equivalent_roof_inherits_only_verified_subset(self) -> None:
        source_identity = "walls_exterior_roofs_30_19_16"
        destination_identity = "walls_exterior_roofs_30_21_16"
        alpha = bytearray(20)
        alpha[0:10] = b"\xff" * 10
        alpha[10:19] = b"\xff" * 9
        page = PngPixels(10, 2, bytes(20), bytes(alpha))
        texture = {
            "page": "page",
            "x": 0,
            "width": 10,
            "height": 1,
            "offset_x": 0,
            "offset_y": 0,
            "original_width": 128,
            "original_height": 256,
        }
        textures = {
            source_identity: texture | {"y": 0},
            destination_identity: texture | {"y": 1, "width": 9},
        }
        compiled = {
            source_identity: {
                "geometry": [{"kind": "triangle", "points": [[0, 0, 0]] * 3}],
                "properties": {"depth_target": "preset_depthmaps_01_5"},
            },
        }
        with (
            mock.patch(
                "pzfps.depth_surfaces.read_indexed_pages",
                return_value={"page": b"source-backed-page"},
            ),
            mock.patch("pzfps.depth_surfaces.decode_png", return_value=page),
        ):
            aliases = compile_source_equivalent_roof_aliases(
                compiled,
                textures,
                {},
                {destination_identity: {"header_count": 1}},
            )
        properties = aliases[destination_identity]["properties"]
        self.assertEqual(properties["source_equivalent_identity"], source_identity)
        self.assertEqual(properties["removed_border_pixels"], 1)
        self.assertEqual(properties["retained_alpha_fraction"], 0.9)

    def test_empty_roof_slot_requires_atlas_and_semantic_evidence(self) -> None:
        definition = {"properties": {"BurntTile": "walls_burnt_roofs_01_18"}}
        identity = "walls_exterior_roofs_05_18"
        self.assertIn("one_pixel_texture_placeholder", empty_source_placeholder_evidence(
            identity, definition, {}, {identity: {"width": 1, "height": 1}},
            texture_index_available=True,
        ))
        self.assertIn("absent_from_texture_atlas", empty_source_placeholder_evidence(
            identity, definition, {}, {}, texture_index_available=True,
        ))
        self.assertEqual(empty_source_placeholder_evidence(
            identity, definition, {identity: "preset_depthmaps_01_0"}, {},
            texture_index_available=True,
        ), "")
        self.assertEqual(empty_source_placeholder_evidence(
            identity, definition, {}, {}, texture_index_available=False,
        ), "")

    def test_depth_helper_identity_resolves_without_tile_definition(self) -> None:
        self.assertEqual(split_tile_identity("preset_depthmaps_01_5"), ("preset_depthmaps_01", 5))

    def test_plain_roof_overlay_requires_installed_anchor_semantics(self) -> None:
        self.assertFalse(has_physical_roof_anchor(
            "roofs_05_47", {"properties": {"RoofGroup": "10", "WestRoofT": ""}}))
        self.assertTrue(has_physical_roof_anchor(
            "roofs_05_34", {"properties": {"RoofGroup": "10", "BlockRain": ""}}))
        self.assertTrue(has_physical_roof_anchor(
            "roofs_accents_01_25", {"properties": {"attachedN": "", "isEave": ""}}))
        self.assertTrue(has_physical_roof_anchor(
            "walls_exterior_roofs_03_39", {"properties": {}}))

    def test_polygon_area_reports_convex_hull_coverage(self) -> None:
        self.assertEqual(polygon_area([(0, 0), (3, 0), (3, 2), (0, 2)]), 6)

    def test_concave_planar_mask_uses_exact_rectangle_decomposition(self) -> None:
        samples = []
        for y in range(150, 160):
            for x in range(60, 70):
                if x < 62 or y >= 158:
                    samples.append((x + 0.5, y + 0.5, 0.5))
        rectangles = opaque_rectangles(samples)
        self.assertEqual(sum((right - left) * (bottom - top)
                             for left, top, right, bottom in rectangles), len(samples))
        triangles, properties = triangulate_planar_patches([
            PlanarPatch(Plane3((0.0, 1.0, 0.0), -0.8, 1.0, 0.0), samples),
        ])
        self.assertTrue(triangles)
        self.assertTrue(all(item["mesh_method"] == "opaque_mask_rectangles"
                            for item in triangles))
        self.assertGreater(properties["opaque_mask_rectangle_count"], 0)

    def test_reads_depth_and_alpha_without_external_image_package(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "depth.png"
            path.write_bytes(png_gray_alpha(2, 1, [(17, 255), (99, 0)]))
            image = read_png(path)
            self.assertEqual((image.width, image.height), (2, 1))
            self.assertEqual(list(image.depth), [17, 99])
            self.assertEqual(list(image.alpha), [255, 0])

    def test_projection_depth_round_trip_fits_source_plane(self) -> None:
        # y=.8*x+.2*z+1.1, sampled through PZ's documented source projection/depth equations.
        samples = []
        for x in (-.5, 0, .5):
            for z in (-.5, 0, .5):
                y = .8 * x + .2 * z + 1.1
                u = 64 + (x - z) * 64
                v = 224 + (x + z) * 32 - y * 64 * (1.5 ** .5)
                depth = .75 - .25 * (x + z) - y / (2 * (6 ** .5))
                samples.append((u, v, depth))
                actual = depth_point(u, v, depth)
                self.assertAlmostEqual(actual[0], x, places=6)
                self.assertAlmostEqual(actual[1], y, places=6)
                self.assertAlmostEqual(actual[2], z, places=6)
        plane = fit_planar_surface(samples)
        self.assertIsNotNone(plane)
        assert plane is not None
        point = point_on_plane(64, 224 - 1.1 * 64 * (1.5 ** .5), plane)
        self.assertAlmostEqual(point[1], 1.1, places=5)

    def test_piecewise_fit_recovers_horizontal_and_vertical_depth_planes(self) -> None:
        horizontal = Plane3((0.0, 1.0, 0.0), -0.8, 0.0, 0.0)
        vertical = Plane3((0.0, 0.0, 1.0), -0.2, 0.0, 0.0)
        samples = []
        for v in range(48, 209):
            for u in range(8, 121):
                plane = horizontal if u < 64 else vertical
                x, y, z = point_on_implicit_plane(u + 0.5, v + 0.5, plane)
                depth = 0.75 - 0.25 * (x + z) - y / (2 * (6 ** 0.5))
                samples.append((u + 0.5, v + 0.5, depth))
        patches = fit_piecewise_planar_surfaces(samples)
        self.assertGreaterEqual(len(patches), 2)
        self.assertGreaterEqual(sum(len(patch.samples) for patch in patches), len(samples) * 0.95)

    def test_piecewise_fit_can_bound_candidates_by_repeated_local_support(self) -> None:
        horizontal = Plane3((0.0, 1.0, 0.0), -0.8, 0.0, 0.0)
        samples = []
        for v in range(48, 209):
            for u in range(8, 121):
                x, y, z = point_on_implicit_plane(u + 0.5, v + 0.5, horizontal)
                depth = 0.75 - 0.25 * (x + z) - y / (2 * (6 ** 0.5))
                samples.append((u + 0.5, v + 0.5, depth))
        patches = fit_piecewise_planar_surfaces(samples, maximum_candidates=1)
        self.assertEqual(len(patches), 1)
        self.assertGreaterEqual(len(patches[0].samples), len(samples) * 0.95)

    def test_roof_fit_retries_quantised_depth_but_keeps_rms_gate(self) -> None:
        samples = [(float(index), 0.0, 0.0) for index in range(64)]
        strict = [PlanarPatch(Plane3((0.0, 1.0, 0.0), 0.0, 1.0, 0.004), samples)]
        relaxed = [PlanarPatch(Plane3((0.0, 1.0, 0.0), 0.0, 1.0, 0.011), samples)]
        with mock.patch(
            "pzfps.depth_surfaces.fit_piecewise_planar_surfaces",
            side_effect=[[], relaxed],
        ) as fit:
            patches, method, threshold = fit_roof_planar_patches(samples)
        self.assertEqual(patches, relaxed)
        self.assertEqual(method, "piecewise_planar_quantised")
        self.assertEqual(threshold, 0.025)
        self.assertEqual(fit.call_count, 2)

        with mock.patch(
            "pzfps.depth_surfaces.fit_piecewise_planar_surfaces",
            return_value=strict,
        ) as fit:
            patches, method, threshold = fit_roof_planar_patches(samples)
        self.assertEqual(patches, strict)
        self.assertEqual(method, "piecewise_planar")
        self.assertEqual(threshold, 0.018)
        fit.assert_called_once()

        too_coarse = [PlanarPatch(Plane3((0.0, 1.0, 0.0), 0.0, 1.0, 0.0121), samples)]
        with mock.patch(
            "pzfps.depth_surfaces.fit_piecewise_planar_surfaces",
            side_effect=[[], too_coarse],
        ):
            patches, method, threshold = fit_roof_planar_patches(samples)
        self.assertEqual(patches, [])
        self.assertEqual(method, "")
        self.assertEqual(threshold, 0.025)

    def test_compiles_only_planar_roof_depth_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            definitions = root / "definitions.json"
            textures = root / "textures.json"
            assignments = root / "assignments.txt"
            depthmaps = root / "depthmaps"
            depthmaps.mkdir()
            definitions.write_text(json.dumps({
                "game_version": "42.20",
                "tiles": {
                    "roofs_color_0": {"tileset": "roofs_color", "xy": [0, 0], "properties": {"RoofGroup": "3", "BlockRain": ""}},
                    "roofs_depth_0": {"tileset": "roofs_depth", "xy": [0, 0], "properties": {"RoofGroup": "1"}},
                    "roofs_overlay_0": {"tileset": "roofs_overlay", "xy": [0, 0], "properties": {"RoofGroup": "1"}},
                    "walls_exterior_roofs_color_0": {
                        "tileset": "walls_exterior_roofs_color", "xy": [0, 0], "properties": {}
                    },
                    "walls_exterior_roofs_empty_0": {
                        "tileset": "walls_exterior_roofs_empty",
                        "xy": [0, 0],
                        "properties": {"BurntTile": "walls_burnt_roofs_01_18"},
                    },
                },
            }))
            textures.write_text(json.dumps({
                "game_version": "42.20",
                "textures": {
                    "walls_exterior_roofs_empty_0": {"width": 1, "height": 1},
                },
            }))
            assignments.write_text(
                "tileDepthTextureAssignments\n{\nVERSION = 1,\n"
                "roofs_color_0 = roofs_depth_0,\n"
                "roofs_overlay_0 = roofs_depth_0,\n"
                "walls_exterior_roofs_color_0 = preset_depthmaps_01_0,\n}\n"
            )
            pixels = []
            for y in range(256):
                for x in range(128):
                    # A constant-y plane encoded through the installed normalisation.
                    u, v = x + .5, y + .5
                    difference = (u - 64) / 64
                    total = (v - 224 + 64 * (1.5 ** .5)) / 32
                    world_x = (total + difference) / 2
                    world_z = (total - difference) / 2
                    depth = .75 - .25 * (world_x + world_z) - 1 / (2 * (6 ** .5))
                    visible = 8 <= x < 120 and 40 <= y < 216 and 0 < depth < 1
                    pixels.append((max(0, min(255, round(depth * 255))), 255 if visible else 0))
            (depthmaps / "DEPTH_roofs_depth.png").write_bytes(png_gray_alpha(128, 256, pixels))
            (depthmaps / "DEPTH_preset_depthmaps_01.png").write_bytes(
                png_gray_alpha(128, 256, pixels)
            )
            output = root / "surfaces.json"
            report = compile_planar_roof_surfaces(
                definitions, assignments, depthmaps, output,
                game_version="42.20", textures_path=textures)
            self.assertIn("roofs_color_0", report["tiles"])
            self.assertNotIn("roofs_overlay_0", report["tiles"])
            # The explicit overlay and the unanchored depth-helper identity itself both
            # remain absent; the anchored colour tile may still reuse the helper's evidence.
            self.assertEqual(report["rejected"]["unanchored_roof_overlay"], 2)
            self.assertEqual(report["rejected_identities"]["roofs_overlay_0"], {
                "reason": "unanchored_roof_overlay",
                "depth_target": "roofs_depth_0",
            })
            self.assertIn("walls_exterior_roofs_color_0", report["tiles"])
            self.assertEqual(report["skipped"], {"empty_source_placeholder": 1})
            self.assertIn("one_pixel_texture_placeholder", report["skipped_identities"][
                "walls_exterior_roofs_empty_0"
            ]["evidence"])
            self.assertEqual(
                report["tiles"]["walls_exterior_roofs_color_0"]["properties"]["depth_target"],
                "preset_depthmaps_01_0",
            )
            self.assertGreaterEqual(len(report["tiles"]["roofs_color_0"]["geometry"]), 2)
            self.assertEqual(parse_depth_assignments(assignments)["roofs_color_0"], "roofs_depth_0")
            self.assertGreater(report["audit"]["finite_points"], 0)
            self.assertEqual(audit_roof_surfaces(report), report["audit"])

            seams = root / "seams.txt"
            seams.write_text("""
                seams
                {
                    VERSION = 1,
                    tileset
                    {
                        name = roofs_depth,
                        tile
                        {
                            xy = 0x0,
                            south
                            {
                                roofs_depth_1 =,
                            }
                        }
                    }
                }
            """)
            seam_records = parse_roof_seams(seams)
            self.assertEqual(seam_records["roofs_depth_0"]["south"], ["roofs_depth_1"])
            contextual_output = root / "contextual-surfaces.json"
            contextual = compile_planar_roof_surfaces(
                definitions, assignments, depthmaps, contextual_output,
                game_version="42.20", textures_path=textures, seams_path=seams)
            self.assertIn("roofs_overlay_0", contextual["contextual_tiles"])
            candidate = contextual["contextual_tiles"]["roofs_overlay_0"]
            self.assertEqual(candidate["properties"]["depth_target"], "roofs_depth_0")
            self.assertEqual(candidate["properties"]["context_joins"], [{
                "relation": "south",
                "offset": [0, 1, 0],
                "targets": ["roofs_depth_1"],
            }])
            self.assertNotIn("roofs_overlay_0", contextual["rejected_identities"])


if __name__ == "__main__":
    unittest.main()
