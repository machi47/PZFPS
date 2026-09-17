#!/usr/bin/env python3
"""Offline CPU reference for the closed-crate shader; source art stays project-local.

Not gameplay or a GPU test. Unwrap the six completed faces for inspecting source
alignment and measuring how much alpha-edge extension/fallback this prior needs.
"""
import argparse
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw


def inspect(manifest_path, output):
    manifest = json.loads(manifest_path.read_text())
    if manifest['sprite'] not in ('carpentry_01_16', 'carpentry_01_19'):
        raise ValueError('Only the inspected closed-crate family is supported')
    region = manifest['region']
    with Image.open(manifest['page_path']) as image:
        source = np.asarray(image.convert('RGBA').crop((region['x'], region['y'],
            region['x'] + region['width'], region['y'] + region['height'])), dtype=float) / 255

    def sample(x, y):
        x = np.clip(x, 0, source.shape[1] - 1)
        y = np.clip(y, 0, source.shape[0] - 1)
        ix, iy = x.astype(int), y.astype(int)
        jx, jy = np.minimum(ix + 1, source.shape[1] - 1), np.minimum(iy + 1, source.shape[0] - 1)
        fx, fy = (x - ix)[..., None], (y - iy)[..., None]
        return ((source[iy, ix] * (1-fx) + source[iy, jx] * fx) * (1-fy)
                + (source[jy, ix] * (1-fx) + source[jy, jx] * fx) * fy)

    n = 192
    u, v = np.meshgrid(np.linspace(0, 1, n), np.linspace(0, 1, n))
    # Installed authored box: min(-.5,0,-.5), max(.5,.8,.5), no rotation/translation.
    # All six faces share explicit donor geometry with the Java implementation.
    donors = {
        '+X observed': (u*0+.5, .8*(1-v), u-.5),
        '-X completed': (u*0+.5, .8*(1-v), .5-u),
        '+Z observed': (u-.5, .8*(1-v), u*0+.5),
        '-Z completed': (.5-u, .8*(1-v), u*0+.5),
        'lid observed': (u-.5, u*0+.8, v-.5),
        'bottom wood prior': (u-.5, .8*v, u*0+.5),
    }
    sheet = Image.new('RGB', (n*3, (n+24)*2), '#ddd')
    draw = ImageDraw.Draw(sheet)
    reports = []
    projected_min_y = 224 - 32 - .8*78.38367
    for index, (name, (x, y, z)) in enumerate(donors.items()):
        sx, sy = 64 + (x-z)*64, 224 + (x+z)*32 - y*78.38367
        px = sx/128 * (source.shape[1]-1)
        py = (sy-projected_min_y)/(256-projected_min_y) * (source.shape[0]-1)
        colour = sample(px, py)
        repair = colour[..., 3] < .999
        for dy in range(-4, 5):
            for dx in range(-4, 5):
                candidate = sample(px+dx, py+dy)
                better = repair & (candidate[..., 3] > colour[..., 3])
                colour[better] = candidate[better]
        fallback = repair & (colour[..., 3] < .5)
        colour[fallback] = sample(np.asarray((source.shape[1]-1)/2), np.asarray((source.shape[0]-1)/2))
        colour[..., 3][repair] = 1
        tile = Image.fromarray(np.round(np.clip(colour, 0, 1)*255).astype('uint8'))
        left, top = (index % 3)*n, (index // 3)*(n+24)
        sheet.paste(tile.convert('RGB'), (left, top+24))
        draw.text((left+4, top+5), name, fill='black')
        reports.append(dict(face=name, samples=n*n, edge_repair=int(repair.sum()),
                            centre_material_fallback=int(fallback.sum())))
    output.mkdir(parents=True, exist_ok=True)
    sheet.save(output / (manifest['sprite'] + '-faces.png'))
    return dict(sprite=manifest['sprite'], page_sha256=manifest['page_sha256'], faces=reports,
                kind='offline-CPU-reference-not-live-acceptance')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('manifests', type=Path, nargs='+')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    if not args.output.resolve().is_relative_to(root / '.local'):
        parser.error('Output must remain under project .local/')
    print(json.dumps([inspect(path, args.output) for path in args.manifests], indent=2))


if __name__ == '__main__':
    main()
