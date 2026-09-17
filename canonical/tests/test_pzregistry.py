import json

import numpy as np
from PIL import Image
import pytest

from pzcanonical.geometry import Camera, Mesh, rasterize
from pzcanonical.pipeline import Compiler
from pzcanonical.pzregistry import calibrate_anchor, prepare_job, registry_mesh
from pzcanonical.store import Store, digest


def registry(primitive):
    return {"schema_version": 1, "game_version": "42.20", "source_sha256": "a" * 64,
            "tiles": {"test_0": {"geometry": [dict(translate=[0, 0, 0], rotate_degrees=[0, 0, 0], **primitive)]}}}


def test_exact_existing_registry_box():
    mesh = registry_mesh(registry(dict(kind="box", min=[-.5, 0, -.3], max=[.5, 1.5, .3])), "test_0")
    np.testing.assert_allclose(mesh.bounds, [[-.5, 0, -.3], [.5, 1.5, .3]])


def test_registry_cylinder_y_up_and_winding():
    mesh = registry_mesh(registry(dict(kind="cylinder", radius1=.3, radius2=.2, height=1.4)), "test_0")
    np.testing.assert_allclose(mesh.bounds[:, 1], [0, 1.4])
    assert mesh.normals[:, 1].max() > .99


def test_concave_polygon_extrudes_without_hull_filling():
    source = registry(dict(kind="polygon", plane="XZ", points=[[0,0],[1,0],[1,.4],[.4,.4],[.4,1],[0,1]]))
    with pytest.raises(ValueError, match="thickness"):
        registry_mesh(source, "test_0")
    mesh = registry_mesh(source, "test_0", polygon_thickness=.02)
    np.testing.assert_allclose(mesh.bounds[:, 1], [-.01,.01])
    # Concave missing upper-right corner must not become filled geometry.
    center = mesh.vertices[mesh.faces].mean(axis=1)
    assert not ((center[:, 0] > .4 + 1e-8) & (center[:, 2] > .4 + 1e-8)).any()


def test_anchor_fitted_not_guessed_from_canvas():
    mesh = Mesh.box(np.array([[-.4, 0, -.3], [.4, .6, .3]]))
    original = Camera.pz_isometric(160, 192, (81, 146), 64, 192)
    mask = rasterize(mesh, original).face >= 0
    rgba = np.zeros((192,160,4),dtype=np.uint8); rgba[mask] = [160,80,40,255]
    fitted, score = calibrate_anchor(mesh, rgba, 64,192)
    assert score > .99
    np.testing.assert_allclose(fitted.matrix[:2,3], [81,146])


def test_bad_source_pair_rejected():
    mesh = Mesh.box(np.array([[-.4, 0, -.3], [.4, .6, .3]]))
    rgba = np.zeros((192,160,4),dtype=np.uint8); rgba[10:12,10:12] = 255
    with pytest.raises(ValueError,match="mismatch"):
        calibrate_anchor(mesh,rgba,64,192)


def test_actual_schema_to_compiled_glb(tmp_path):
    record = registry(dict(kind="box", min=[-.4, 0, -.3], max=[.4, .6, .3]))
    mesh = registry_mesh(record,"test_0")
    camera = Camera.pz_isometric(160,192,(81,146),64,192)
    mask = rasterize(mesh,camera).face >= 0
    rgba = np.zeros((192,160,4),dtype=np.uint8); rgba[mask] = [160,80,40,255]
    image = tmp_path/'source.png'; Image.fromarray(rgba).save(image)
    appearance = {"schema_version":1,"game_version":"42.20","sprite":"test_0","page_path":str(image),"page_sha256":digest(image.read_bytes()),"region":{"x":0,"y":0,"width":160,"height":192,"offset_x":0,"offset_y":0,"original_width":160,"original_height":192}}
    geometry = tmp_path/'geometry.json'; geometry.write_text(json.dumps(record))
    sprite = tmp_path/'sprite.json'; sprite.write_text(json.dumps(appearance))
    job = prepare_job(geometry,sprite,tmp_path/'compiled',horizontal=64,vertical=192,atlas_size=128)
    result = Compiler(Store(tmp_path/'store')).compile(job)
    assert (result.path/'asset.glb').is_file()
    assert result.report['prototype']=='test_0'
    assert result.report['observed_fraction'] > .4


def test_registry_rotation_matches_java_xyz_order():
    record = registry(dict(kind="box", min=[-.5,0,-.2], max=[.5,1,.2]))
    source = record["tiles"]["test_0"]["geometry"][0]
    source["rotate_degrees"] = [20,35,70]
    source["translate"] = [.2,.3,.4]
    original = Mesh.box(np.array([source['min'],source['max']])).vertices
    rx,ry,rz = np.deg2rad(source['rotate_degrees'])
    x,y,z = original.T
    y1 = y*np.cos(rx)-z*np.sin(rx); z1=y*np.sin(rx)+z*np.cos(rx)
    x2=x*np.cos(ry)+z1*np.sin(ry); z2=-x*np.sin(ry)+z1*np.cos(ry)
    x3=x2*np.cos(rz)-y1*np.sin(rz); y3=x2*np.sin(rz)+y1*np.cos(rz)
    expected=np.column_stack([x3,y3,z2])+source['translate']
    np.testing.assert_allclose(registry_mesh(record,'test_0').vertices,expected,atol=1e-12)
