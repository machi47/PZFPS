#!/usr/bin/env python3
"""CPU reference for bounded live-shader alpha repair on extracted PZ surface sprites.

Uses real extraction manifests; does not modify source images or prove GPU/gameplay output.
Run with the project-local canonical venv (NumPy/Pillow) and explicit manifest paths.
"""

import argparse
import json
from pathlib import Path

import numpy as np
from PIL import Image


def inspect(path, kind):
    manifest = json.loads(path.read_text())
    region = manifest['region']
    with Image.open(manifest['page_path']) as page:
        bounds = (region['x'], region['y'], region['x'] + region['width'],
                  region['y'] + region['height'])
        alpha = np.asarray(page.convert('RGBA').crop(bounds), dtype=float)[:, :, 3] / 255

    def sample(px, py):
        # Match shader texel-centre clamping, preventing atlas-neighbour contamination.
        px = np.clip(px - region['offset_x'] - .5, 0, alpha.shape[1] - 1)
        py = np.clip(py - region['offset_y'] - .5, 0, alpha.shape[0] - 1)
        ix, iy = px.astype(int), py.astype(int)
        jx, jy = np.minimum(ix + 1, alpha.shape[1] - 1), np.minimum(iy + 1, alpha.shape[0] - 1)
        fx, fy = px - ix, py - iy
        return ((alpha[iy, ix] * (1-fx) + alpha[iy, jx] * fx) * (1-fy)
                + (alpha[jy, ix] * (1-fx) + alpha[jy, jx] * fx) * fy)

    q = np.linspace(-.5, .5, 257)
    u, v = np.meshgrid(q, q)
    if kind == 'floor':
        sx, sy = 64 + (u-v)*64, 224 + (u+v)*32
        edge = np.maximum(abs(u), abs(v)) > .465
        offsets = [(dx, dy) for dy in range(-2, 3) for dx in range(-2, 3)]
    else:
        # Both orientations use the exact fallback wall plane and sourcePixel projection.
        height = (v + .5) * 3
        if kind == 'north-wall':
            sx, sy = 64 + (u+.5)*64, 224 + (u-.5)*32 - height*64
            slope = .5
        else:
            sx, sy = 64 + (-.5-u)*64, 224 + (-.5+u)*32 - height*64
            slope = -.5
        edge = np.minimum(sx % 64, 64-sx % 64) < 6
        offsets = [(sign*i, sign*i*slope) for i in range(1, 7) for sign in (-1, 1)]
    outside = ((sx < region['offset_x']) | (sy < region['offset_y'])
               | (sx > region['offset_x'] + region['width'])
               | (sy > region['offset_y'] + region['height']))
    before = sample(sx, sy)
    before[outside] = 0
    after = before.copy()
    repair = edge & (before < .999)
    for dx, dy in offsets:
        candidate = sample(sx+dx, sy+dy)
        if kind != 'floor':
            inside = ((sx+dx >= region['offset_x']) & (sy+dy >= region['offset_y'])
                      & (sx+dx < region['offset_x'] + region['width'])
                      & (sy+dy < region['offset_y'] + region['height']))
            candidate[~inside] = 0
        after[repair] = np.maximum(after[repair], candidate[repair])
    assert np.array_equal(after[~edge], before[~edge]), 'interior alpha changed'
    return dict(sprite=manifest['sprite'], kind=kind, page_sha256=manifest['page_sha256'],
                samples=int(before.size), before_transparent=int((before < .02).sum()),
                after_transparent=int((after < .02).sum()),
                before_partial=int((before < .999).sum()), after_partial=int((after < .999).sum()),
                interior_changed=int((after[~edge] != before[~edge]).sum()))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kind', choices=('floor', 'north-wall', 'west-wall'), required=True)
    parser.add_argument('manifests', nargs='+', type=Path)
    args = parser.parse_args()
    print(json.dumps([inspect(path, args.kind) for path in args.manifests], indent=2))


if __name__ == '__main__':
    main()
