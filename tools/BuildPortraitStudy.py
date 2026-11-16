"""Lossless, integer-coordinate portrait cut-and-paste study (Pillow + NumPy)."""
from pathlib import Path
import json
from collections import deque
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'assets/portraits/modular-study'
SOURCES = [
    ROOT / '.codex-attachments/79e925c3-b2ac-445d-802c-811b206e7a7c/2e31a51a-7f7d-44fc-91fa-fcf454567bbd/4275.png',
    ROOT / '.codex-attachments/79e925c3-b2ac-445d-802c-811b206e7a7c/95210c4f-7a2e-42ec-af61-14a04a9a5e39/4269.png',
]
OUT.mkdir(parents=True, exist_ok=True)
(OUT / 'pieces').mkdir(exist_ok=True)
(OUT / 'essais').mkdir(exist_ok=True)
FONT = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', 13)
TITLE = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', 20)


def polygon(points):
    im = Image.new('L', (256, 256))
    ImageDraw.Draw(im).polygon(points, fill=255)
    return np.array(im) != 0


def background(a):
    # Only light neutral pixels connected to the image border; never eye whites.
    candidate = (a.min(axis=2) > 205) & ((a.max(axis=2).astype(int) - a.min(axis=2)) < 22)
    found = np.zeros((256, 256), dtype=bool)
    q = deque((x, y) for x in range(256) for y in (0, 255))
    q.extend((x, y) for y in range(256) for x in (0, 255))
    while q:
        x, y = q.popleft()
        if not (0 <= x < 256 and 0 <= y < 256) or found[y, x] or not candidate[y, x]:
            continue
        found[y, x] = True
        q.extend(((x-1, y), (x+1, y), (x, y-1), (x, y+1)))
    return found


RECTS = [
    {'sourcil_g': (82, 97, 122, 107), 'sourcil_d': (137, 96, 177, 106),
     'oeil_g': (84, 107, 120, 127), 'oeil_d': (137, 107, 173, 127),
     'nez': (115, 127, 143, 151), 'bouche': (106, 153, 150, 176)},
    {'sourcil_g': (80, 101, 120, 111), 'sourcil_d': (137, 101, 177, 111),
     'oeil_g': (84, 111, 120, 131), 'oeil_d': (137, 111, 173, 131),
     'nez': (115, 132, 143, 156), 'bouche': (106, 161, 150, 184)},
]
FACE = [
    [(81,104),(115,105),(133,99),(146,85),(156,62),(171,101),(177,116),
     (176,147),(168,166),(152,184),(139,192),(119,192),(101,182),(87,167),(80,146),(78,118)],
    [(79,104),(98,99),(119,101),(137,92),(151,80),(162,91),(174,100),(179,120),
     (177,149),(169,178),(150,198),(139,205),(118,205),(99,196),(86,178),(78,151),(75,123)],
]
EAR = [
    [[(62,112),(73,110),(80,121),(82,148),(72,147),(64,137)],
     [(183,113),(193,111),(195,127),(187,141),(176,148),(178,124)]],
    [[(57,113),(68,111),(76,123),(79,153),(69,151),(59,138)],
     [(184,113),(197,113),(199,128),(190,147),(179,154),(180,126)]],
]
LABELS = {'fond':'Fond', 'chevelure':'Chevelure et raccords', 'visage':'Support du visage',
          'cou':'Cou et épaules', 'oreille_g':'Oreille gauche / image', 'oreille_d':'Oreille droite / image',
          'sourcil_g':'Sourcil gauche / image', 'sourcil_d':'Sourcil droit / image',
          'oeil_g':'Œil gauche / image', 'oeil_d':'Œil droit / image', 'nez':'Nez', 'bouche':'Bouche'}
ORDER = list(LABELS)
images, layers, metadata, checks = [], [], [], []
for idx, source in enumerate(SOURCES):
    im = Image.open(source).convert('RGB').resize((256,256), Image.Resampling.NEAREST)
    im.save(OUT / f'reference-{idx+1}-256.png')
    images.append(im)
    a = np.array(im)
    masks = {}
    occupied = np.zeros((256,256), dtype=bool)
    # Traits are independent, rectangular copy windows of equal dimensions across donors.
    for name, box in RECTS[idx].items():
        x0,y0,x1,y1 = box
        mask = np.zeros_like(occupied); mask[y0:y1,x0:x1] = True
        assert not (mask & occupied).any(), name
        masks[name] = mask; occupied |= mask
    masks['fond'] = background(a) & ~occupied; occupied |= masks['fond']
    for name, points in zip(('oreille_g','oreille_d'), EAR[idx]):
        masks[name] = polygon(points) & ~occupied; occupied |= masks[name]
    masks['visage'] = polygon(FACE[idx]) & ~occupied; occupied |= masks['visage']
    neck = polygon([(96 if idx == 0 else 84,176),(162 if idx == 0 else 171,176),
                    (164 if idx == 0 else 172,205),(255,247),(255,255),(0,255),(0,247),
                    (93 if idx == 0 else 84,205)])
    neck[205:, :] = True
    masks['cou'] = neck & ~occupied; occupied |= masks['cou']
    masks['chevelure'] = ~occupied
    assert np.all(sum(m.astype(int) for m in masks.values()) == 1)
    pieces = {}
    records = {}
    for name in ORDER:
        rgba = np.zeros((256,256,4), dtype=np.uint8)
        rgba[masks[name],:3] = a[masks[name]]
        rgba[masks[name],3] = 255
        piece = Image.fromarray(rgba)
        piece.save(OUT / 'pieces' / f'{idx+1}-{name}.png')
        pieces[name] = piece
        records[name] = {'file':f'pieces/{idx+1}-{name}.png', 'bbox':piece.getbbox(),
                         'opaque_pixels':int(masks[name].sum())}
    layers.append(pieces)
    rebuilt = Image.new('RGBA',(256,256))
    for name in ORDER:
        rebuilt.alpha_composite(pieces[name])
    assert rebuilt.getchannel('A').getextrema() == (255,255)
    result = rebuilt.convert('RGB')
    result.save(OUT / f'recomposition-{idx+1}.png')
    differing = int(np.any(np.array(result) != a, axis=2).sum())
    assert differing == 0
    checks.append({'reference':idx+1,'differing_pixels':differing,'pixels':65536,
                   'partition_exactly_once':True,'alpha_binary':True})
    metadata.append({'source':str(source.relative_to(ROOT)), 'pieces':records,'traits':RECTS[idx]})


def checker():
    im = Image.new('RGB',(256,256),'#e0e3e8'); d = ImageDraw.Draw(im)
    for y in range(0,256,8):
        for x in range(0,256,8):
            if (x//8+y//8)%2: d.rectangle((x,y,x+7,y+7),fill='#f6f7f9')
    return im


def sheet(items, columns, title, subtitle, filename):
    cw,ch = 280,306
    rows = (len(items)+columns-1)//columns
    im = Image.new('RGB',(columns*cw+24,rows*ch+88),'#19212d')
    d = ImageDraw.Draw(im)
    d.text((16,12),title,font=TITLE,fill='white')
    d.text((16,43),subtitle,font=FONT,fill='#c4d2e5')
    for i,(label,detail,picture) in enumerate(items):
        x,y=12+(i%columns)*cw,76+(i//columns)*ch
        base=checker(); base.paste(picture,(0,0),picture if picture.mode=='RGBA' else None)
        im.paste(base,(x+12,y))
        d.text((x+12,y+261),label,font=FONT,fill='white')
        d.text((x+12,y+280),detail,font=FONT,fill='#c4d2e5')
    im.save(OUT/filename)


sheet([(f'{idx+1} · {LABELS[name]}', 'Position originale · alpha 0/255',layers[idx][name])
       for name in ORDER for idx in range(2)],4,
      'Pièces originales — copie exacte à 256 × 256',
      'Chaque paire : référence 1 puis référence 2. Damier = transparence. Aucun pixel redessiné.',
      'planche-pieces.png')
sheet([(f'Référence {i+1}', 'Réduction au plus proche voisin', images[i]) if j == 0 else
       (f'Recomposition {i+1}', '0 pixel différent sur 65 536',Image.open(OUT/f'recomposition-{i+1}.png'))
       for i in range(2) for j in range(2)],4,
      'Vérification des deux recompositions','Référence et assemblage côte à côte, à leur résolution native.',
      'planche-recompositions.png')

variants = [([], 'Témoin'), (['sourcil_g','sourcil_d'],'Sourcils croisés'),
            (['oeil_g','oeil_d'],'Yeux croisés'), (['nez'],'Nez croisé'),
            (['bouche'],'Bouche croisée'),(list(RECTS[0]),'Tous les traits croisés')]
trials=[]; recipes=[]
for recipient in range(2):
    for selected,label in variants:
        donor=1-recipient
        # Assemble from the extracted atlas alone, translating whole donor windows.
        composite=Image.new('RGBA',(256,256))
        for name in ORDER:
            if name in selected: continue
            composite.alpha_composite(layers[recipient][name])
        for name in selected:
            patch=layers[donor][name].crop(RECTS[donor][name])
            box=RECTS[recipient][name]
            assert patch.size == (box[2]-box[0],box[3]-box[1])
            assert patch.getchannel('A').getextrema() == (255,255)
            composite.alpha_composite(patch,(box[0],box[1]))
        assert composite.getchannel('A').getextrema() == (255,255)
        result=composite.convert('RGB')
        # Independent oracle: exact integer-coordinate copy from the reduced sources.
        expected=images[recipient].copy()
        for name in selected:
            expected.paste(images[donor].crop(RECTS[donor][name]),RECTS[recipient][name][:2])
        assert np.array_equal(np.array(result),np.array(expected))
        n=len(trials)+1
        result.save(OUT/'essais'/f'{n:02}.png')
        trials.append((f'{n:02} · Base {recipient+1} · {label}',f'Traits donneurs : référence {donor+1}' if selected else 'Assemblage original',result))
        recipes.append({'file':f'essais/{n:02}.png','support':recipient+1,'donor':donor+1,
                        'replaced':selected,'exact_copy_verified':True})
sheet(trials,3,'Essais de combinaisons — aucune retouche',
      'Deux supports, six essais chacun. Les raccords de peau sont conservés volontairement.',
      'planche-essais.png')
(OUT/'manifest.json').write_text(json.dumps({'size':[256,256],'resize':'NEAREST',
    'references':metadata,'recipes':recipes},indent=2,ensure_ascii=False)+'\n')
(OUT/'verification.json').write_text(json.dumps({'recompositions':checks,
    'trials_exact_copy_verified':len(recipes),'generated_or_interpolated_pixels_after_resize':0},indent=2)+'\n')
print(json.dumps(checks)); print(f'{len(recipes)} essais vérifiés ; sortie : {OUT}')
