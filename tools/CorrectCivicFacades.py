"""Reproducible local shears of v4 artwork; requires Pillow and NumPy.

Window slopes measured from horizontal glazing bars. Each local inverse mapping
is a pure vertical shear in its interior, fading into surrounding masonry.
Original alpha and all pixels outside these facade regions remain unchanged.
"""
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'assets/sprites/civic'


def shear(im, x0, x1, top, bottom, slope, correction, feather=12, preserve_brick=True):
    # top/bottom are heights at the region's horizontal center.
    a = np.asarray(im).copy()
    yy, xx = np.mgrid[0:im.height, 0:im.width]
    cx = (x0 + x1) / 2
    local_y = yy - slope * (xx - cx)
    weight = np.minimum.reduce([
        (xx - x0 + feather) / feather,
        (x1 + feather - xx) / feather,
        (local_y - top + feather) / feather,
        (bottom + feather - local_y) / feather,
        np.ones_like(xx, dtype=float),
    ]).clip(0, 1)
    sy = yy - correction * (xx - cx) * weight
    y0 = np.floor(sy).astype(int).clip(0, im.height - 2)
    fraction = (sy - y0).clip(0, 1)[..., None]
    rgb = a[y0, xx, :3] * (1 - fraction) + a[y0 + 1, xx, :3] * fraction
    if preserve_brick:
        def brick(pixels):
            r, g, b = [pixels[..., i].astype(float) for i in range(3)]
            return (r > g * 1.16) & (g > b * 1.24)
        # Keep masonry away from the moved feature rigid, avoiding bent courses.
        keep = brick(a[..., :3]) & brick(rgb)
        rgb[keep] = a[..., :3][keep]
    a[..., :3] = np.rint(rgb).clip(0, 255).astype('uint8')
    return Image.fromarray(a)


for name in ('fire', 'school'):
    im = Image.open(SOURCE / f'{name}-v4.png').convert('RGBA')
    if name == 'fire':
        windows = [
            (270, 333, 507, 635, .385),
            (363, 427, 548, 676, .410),
            (454, 518, 587, 715, .375),
            (538, 602, 625, 750, .415),
        ]
    else:
        # The floor band is corrected before windows and the doorway.
        # The exposed floor bands are rebuilt below with straight edges.
        windows = [
            (262, 361, 508, 655, .415),
            (482, 580, 601, 747, .440),
            (262, 338, 669, 786, .450),
            (499, 581, 766, 896, .415),
        ]
    for x0, x1, top, bottom, measured in windows:
        im = shear(im, x0, x1, top, bottom, measured, .465 - measured, 10)
    if name == 'fire':
        im = shear(im, 480, 594, 798, 944, .40, .465 - .40, 10)
    if name == 'school':
        im = shear(im, 382, 450, 728, 856, .41, .465 - .41, 10)
        # Rebuild the treads as projected rectangles: u=(1,.5), v=(-1,.5).
        # Riser displacement is purely vertical. Long tread edges match the measured wall footing (.465).
        d = ImageDraw.Draw(im)
        rng = np.random.default_rng(2000)
        def stone(points, color):
            mask = Image.new('L', im.size)
            ImageDraw.Draw(mask).polygon([tuple(np.rint(p).astype(int)) for p in points], fill=255)
            noise = rng.normal(0, 2.0, (im.height, im.width, 1))
            pixels = np.clip(np.array(color[:3])[None, None, :] + noise, 0, 255).astype('uint8')
            tile = Image.fromarray(pixels).convert('RGBA')
            im.paste(tile, (0, 0), mask)
        # Leave the sculpted central arch intact; both exposed bands share one axis.
        for x0, x1 in ((231, 344), (499, 610)):
            a = np.array([x0, 614 + .465 * (x0 - 231)])
            b = np.array([x1, 614 + .465 * (x1 - 231)])
            stone([a, b, b + (0, 12), a + (0, 12)], (216, 190, 157))
            d.line([tuple(a), tuple(b)], fill=(248, 225, 192, 255), width=2)
        left = np.array([369., 833.])
        width = np.array([99., 99. * .465])
        depth = np.array([-14., 7.])
        rise = np.array([0., 8.])
        def polygon(points, fill, outline=None):
            stone(points, fill)
            if outline:
                d.line([tuple(np.rint(p).astype(int)) for p in points + [points[0]]], fill=outline, width=1)
        for step in range(3):
            back = left + step * (depth + rise)
            front = back + depth
            polygon([back, back+width, front+width, front], (221, 202, 174, 255), (170, 148, 121, 255))
            polygon([front, front+width, front+width+rise, front+rise], (181, 157, 128, 255), (145, 125, 104, 255))
            d.line([tuple(front), tuple(front+width)], fill=(247, 229, 201, 255), width=2)
            # Stone joints on each tread are parallel to the depth axis.
            for fraction in (.33, .66):
                a = back + width * fraction
                b = front + width * fraction
                d.line([tuple(a), tuple(b)], fill=(194, 175, 149, 255), width=1)
    im.putalpha(Image.open(SOURCE / f'{name}-v4.png').getchannel('A'))
    im.save(SOURCE / f'{name}-v5.png')
