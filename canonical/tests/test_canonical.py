from __future__ import annotations

import asyncio
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path

import numpy as np
from PIL import Image
import pytest
import trimesh

from pzcanonical.appearance import View, bake_views, complete_harmonic, complete_novel_views, render, unwrap
from pzcanonical.cli import make_fixture, orbit
from pzcanonical.geometry import Camera, Mesh, rasterize, visual_hull
from pzcanonical.pipeline import Compiler, isolated_sprite
from pzcanonical.runtime import NeedsSnapshot, SceneState, matrices_from_wire, skin_vertices
from pzcanonical.store import Store, digest


@pytest.fixture
def box():
    return Mesh.box(np.array([[-.5, -.5, -.5], [.5, .5, .5]]))


def solid_view(mesh, direction, color=(210, 70, 30), size=96):
    camera = Camera.look_at(direction, [0, 0, 0], 60, size)
    mask = rasterize(mesh, camera).face >= 0
    image = np.zeros((size, size, 4), dtype=np.uint8)
    image[mask, :3] = color
    image[mask, 3] = 255
    return View("known", camera, image)


def test_calibrated_pz_axes_and_inverse():
    c = Camera.pz_isometric(128, 384, (64, 256), 64, 192)
    np.testing.assert_allclose(c.project(np.array([[0, 0, 0], [1, 0, 0], [0, 1, 0]]))[:, :2], [[64, 256], [128, 288], [64, 64]])
    assert np.all(c.toward_camera > 0)


def test_camera_rejects_singular_and_nan():
    with pytest.raises(ValueError): Camera(np.zeros((3, 4)), 32, 32)
    with pytest.raises(ValueError): Camera(np.full((3, 4), np.nan), 32, 32)


def test_mesh_rejects_degenerate_and_footprint(box):
    with pytest.raises(ValueError): Mesh([[0, 0, 0], [1, 0, 0], [2, 0, 0]], [[0, 1, 2]])
    with pytest.raises(ValueError): box.validate_bounds(np.array([[-.4]*3, [.4]*3]))


def test_transform_preserves_winding_under_reflection(box):
    reflected = box.transformed(np.diag([-1, 1, 1, 1]))
    centers = reflected.vertices[reflected.faces].mean(axis=1)
    assert np.all(np.einsum("ij,ij->i", centers, reflected.normals) > 0)


def test_backfaces_never_receive_front_texture(box):
    atlas = unwrap(box, 96)
    bake = bake_views(atlas, [solid_view(box, [0, 0, 1])])
    rear = atlas.valid & (atlas.normals[..., 2] < -.9)
    assert rear.any() and not bake.observed[rear].any()
    front = atlas.valid & (atlas.normals[..., 2] > .9)
    assert bake.observed[front].mean() > .9


def test_depth_occluder_rejects_false_observation(box):
    atlas = unwrap(box, 96)
    view = solid_view(box, [0, 0, 1])
    depth = rasterize(box, view.camera).depth.copy()
    depth[np.isfinite(depth)] -= .3
    with pytest.raises(ValueError, match="no source"):
        bake_views(atlas, [View(view.name, view.camera, view.rgba, depth)])


def test_complete_preserves_observations_and_no_nan(box):
    bake = bake_views(unwrap(box, 96), [solid_view(box, [2, 1, 3])])
    before = bake.linear.copy()
    complete_harmonic(bake)
    assert np.array_equal(before[bake.observed], bake.linear[bake.observed])
    assert np.isfinite(bake.linear).all()
    assert bake.linear[bake.atlas.valid & ~bake.observed].max() > 0
    assert not bake.generated.any()


def test_multiview_shared_texture_and_disagreement(box):
    atlas = unwrap(box, 96)
    view = solid_view(box, [1, 1, 3])
    second = solid_view(box, [1, 1, 3], (20, 220, 50))
    bake = bake_views(atlas, [view, second])
    assert bake.disagreement.max() > .1
    complete_harmonic(bake)
    image, _ = render(bake, view.camera)
    repeat, _ = render(bake, view.camera)
    assert np.array_equal(image, repeat)


def test_generated_views_fill_rear_but_cannot_change_front(box):
    bake = bake_views(unwrap(box, 96), [solid_view(box, [0, 0, 1])])
    complete_harmonic(bake)
    before = bake.linear.copy()
    class AdversarialProvider:
        def __call__(self, rgb, mask, prompt, seed):
            # Deliberately ignores mask; compiler must reimpose it.
            return np.full_like(rgb, [30, 180, 90])
    count = complete_novel_views(bake, [Camera.look_at([0, 0, -1], [0, 0, 0], 60, 96)], AdversarialProvider(), "back panel")
    assert count > 0 and bake.generated.any()
    assert np.array_equal(before[bake.observed], bake.linear[bake.observed])
    assert not np.any(bake.generated & bake.observed)


def test_material_groups_do_not_copy_red_to_unknown_material(box):
    atlas = unwrap(box, 96)
    bake = bake_views(atlas, [solid_view(box, [0, 0, 1], (255, 0, 0))])
    groups = (box.normals[:, 2] < -.9).astype(np.int32)
    complete_harmonic(bake, material_groups=groups)
    rear = atlas.valid & (atlas.normals[..., 2] < -.9)
    colors = bake.linear[rear]
    np.testing.assert_allclose(colors[:, 0], colors[:, 1])


def test_sprite_isolation_prevents_atlas_neighbor_bleed(tmp_path):
    image = np.zeros((8, 16, 4), dtype=np.uint8)
    image[:, :8] = [255, 0, 0, 255]
    image[:, 8:] = [0, 255, 0, 255]
    page = tmp_path / "page.png"
    Image.fromarray(image).save(page)
    manifest = {"schema_version": 1, "page_path": "page.png", "page_sha256": digest(page.read_bytes()), "region": dict(x=0, y=0, width=8, height=8, offset_x=2, offset_y=3, original_width=12, original_height=14)}
    path = tmp_path / "sprite.json"; path.write_text(json.dumps(manifest))
    result = isolated_sprite(path)
    assert result.shape == (14, 12, 4)
    assert result[..., 1].max() == 0
    assert (result[..., 3] > 0).sum() == 64
    manifest["region"]["x"] = 20; path.write_text(json.dumps(manifest))
    with pytest.raises(ValueError): isolated_sprite(path)


def test_visual_hull_keeps_bounds(box):
    views = [solid_view(box, d, size=96) for d in ([1, 0, 0], [0, 1, 0], [0, 0, 1])]
    mesh = visual_hull(box.bounds, [(v.camera, v.rgba[..., 3] > 127) for v in views], 16)
    mesh.validate_bounds(box.bounds, 1e-5)
    assert len(mesh.faces) > 20


def test_store_concurrency_integrity_and_assignment(tmp_path):
    store = Store(tmp_path)
    key = digest(b"asset-v1")
    calls = []
    def build(path):
        calls.append(1); (path / "asset.glb").write_bytes(b"fixture-content")
    with ThreadPoolExecutor(4) as pool:
        values = list(pool.map(lambda _: store.materialize(key, build), range(4)))
    assert len(calls) == 1 and sum(cached for _, cached in values) == 3
    second = digest(b"asset-v2")
    store.materialize(second, build)
    assert store.assign("world", "door:life1", "door", key) == key
    assert store.assign("world", "door:life1", "door", second) == key
    assert Store(tmp_path).lookup("world", "door:life1") == key
    store.accept("world", "door:life1", key, second)
    with pytest.raises(ValueError): store.accept("world", "door:life1", key, second)
    (store.path(key) / "asset.glb").write_bytes(b"corrupt")
    with pytest.raises(ValueError): store.verify(key)


def test_transactional_scene_gap_removal_and_age():
    state = SceneState()
    node = dict(id="door:1", prototype="door", kind="door", transform=np.eye(4).tolist())
    snapshot = dict(schema_version=1, type="snapshot", session="s", sequence=1, upsert=[node], age_at_receipt=.02)
    assert state.apply(snapshot, 10)
    assert state.read().age(12) == pytest.approx(2.02)
    with pytest.raises(NeedsSnapshot):
        state.apply(dict(schema_version=1, type="delta", session="s", sequence=3, base_sequence=2, remove=["door:1"]), 11)
    assert "door:1" in state.read().nodes
    state.apply(dict(schema_version=1, type="delta", session="s", sequence=2, base_sequence=1, remove=["door:1"]), 12)
    assert not state.read().nodes
    assert not state.apply(snapshot, 13)


def test_scene_validation_is_atomic():
    state = SceneState()
    node = dict(id="room", prototype="room", kind="structure", transform=np.eye(4).tolist())
    state.apply(dict(schema_version=1, type="snapshot", session="s", sequence=1, upsert=[node]), 0)
    bad = dict(node, transform=np.zeros((4, 4)).tolist())
    with pytest.raises(ValueError):
        state.apply(dict(schema_version=1, type="delta", session="s", sequence=2, base_sequence=1, upsert=[bad]), 1)
    assert state.read().sequence == 1


def test_evaluated_skin_pose_and_matrix_order():
    pose = np.stack([np.eye(4), np.eye(4)])
    pose[1, 1, 3] = 2
    world = np.eye(4); world[0, 3] = 100
    result = skin_vertices([[0, 0, 0], [1, 0, 0]], [[0, 1], [1, 0]], [[.25, .75], [1, 0]], pose, np.stack([np.eye(4)] * 2), world)
    np.testing.assert_allclose(result, [[100, 1.5, 0], [101, 2, 0]])
    column = pose.transpose(0, 2, 1).reshape(2, 16)
    np.testing.assert_allclose(matrices_from_wire(column, "column-major"), pose)
    with pytest.raises(ValueError): matrices_from_wire(column, "guess")


def test_full_compile_cached_glb_and_changed_source(tmp_path):
    job = make_fixture(tmp_path / "fixture")
    compiler = Compiler(Store(tmp_path / "store"))
    results = asyncio.run(compiler.compile_many([job, job], 2))
    assert results[0].key == results[1].key
    assert sum(result.cache_hit for result in results) == 1
    scene = trimesh.load_scene(results[0].path / "asset.glb", process=False)
    assert len(scene.geometry) == 1
    geometry = next(iter(scene.geometry.values()))
    assert geometry.visual.kind == "texture"
    assert results[0].report["constraints"]["observations_unchanged"]
    proof = orbit(results[0].path, tmp_path / "orbit", 4)
    assert proof["repeat_camera_identical"] and (tmp_path / "orbit/orbit.gif").is_file()
    document = json.loads(job.read_text()); document["roughness"] = .5; job.write_text(json.dumps(document))
    assert compiler.compile(job).key != results[0].key
