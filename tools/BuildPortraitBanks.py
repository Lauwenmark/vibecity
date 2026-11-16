"""Extract exact RGBA parts from the ten generated 256px portraits.

Run from any directory with Pillow and NumPy. No model calls, repainting,
colour adjustment, interpolation or facial warping occurs during extraction.
"""
from collections import deque
from pathlib import Path
import json
import hashlib
import zipfile

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'assets/portraits/generations-v1'
OUT = ROOT / 'assets/portraits/element-banks-v1'
FONT = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', 13)
TITLE = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', 21)
LABELS = {
    'fond': 'Fond', 'chevelure': 'Chevelure',
    'visage': 'Support du visage', 'cou-buste': 'Cou et buste',
    'oreille-g': 'Oreille gauche', 'oreille-d': 'Oreille droite',
    'sourcil-g': 'Sourcil gauche', 'sourcil-d': 'Sourcil droit',
    'oeil-g': 'Œil gauche', 'oeil-d': 'Œil droit',
    'nez': 'Nez', 'bouche': 'Bouche', 'raccords': 'Raccords de contour',
}
ORDER = list(LABELS)
TRAITS = ORDER[6:12]
AGE_LABELS = ['Enfant', 'Adolescent', 'Adulte', 'Âge mûr', 'Âgé']
# Windows have the same width and height for each trait across all donors.
# Their origins follow the actual feature positions, not the requested prompt.
EYE_TOP = [109, 109, 101, 101, 100, 100, 98, 106, 101, 100]
MOUTH_TOP = [158, 158, 149, 150, 150, 150, 148, 154, 150, 150]
FACES = [
    [(72,111),(76,99),(99,92),(116,77),(126,61),(137,82),(157,98),(178,108),
     (179,145),(169,170),(148,190),(128,199),(107,192),(87,175),(74,149)],
    [(70,112),(70,96),(89,84),(104,81),(120,85),(135,79),(148,83),(167,80),(182,99),
     (183,151),(173,177),(147,197),(128,202),(108,198),(86,178),(72,151)],
    [(73,105),(75,80),(90,64),(116,54),(144,56),(164,66),(177,83),(180,112),
     (177,145),(163,169),(143,187),(127,194),(108,187),(91,171),(77,146)],
    [(75,106),(78,91),(91,82),(108,91),(123,88),(137,79),(149,76),(172,89),(180,105),
     (178,147),(166,172),(145,190),(128,196),(107,188),(88,169),(77,145)],
    [(75,105),(79,85),(98,63),(119,56),(139,56),(158,64),(176,88),(179,108),
     (177,146),(164,171),(145,188),(128,195),(110,188),(92,172),(78,147)],
    [(76,104),(78,80),(86,62),(108,59),(125,64),(145,62),(169,65),(177,84),(180,108),
     (177,148),(165,177),(145,193),(127,198),(105,192),(89,177),(77,148)],
    [(75,106),(79,87),(97,71),(113,54),(130,54),(145,58),(163,78),(179,102),
     (178,146),(166,171),(145,187),(128,193),(109,186),(90,170),(77,147)],
    [(72,110),(76,87),(85,65),(106,49),(123,56),(140,53),(159,60),(176,83),(183,109),
     (179,153),(168,181),(148,198),(128,204),(107,198),(87,180),(74,151)],
    [(73,109),(78,86),(92,66),(108,62),(125,67),(141,58),(163,70),(178,93),(182,112),
     (178,150),(166,175),(146,193),(128,199),(108,193),(90,176),(76,149)],
    [(72,109),(77,79),(86,52),(98,29),(115,17),(137,18),(157,30),(174,55),(181,84),(183,111),
     (179,153),(167,181),(146,198),(128,204),(108,199),(88,181),(74,153)],
]
EAR_TOP = [116,115,103,103,102,102,103,104,105,104]
EAR_X = [(52,180),(46,181),(60,179),(58,178),(57,179),
         (57,180),(61,179),(52,180),(55,180),(50,180)]


def polygon(points):
    im = Image.new('L', (256,256))
    ImageDraw.Draw(im).polygon(points, fill=255)
    return np.asarray(im) > 0


def connected(candidate, seeds):
    found = np.zeros(candidate.shape, dtype=bool)
    queue = deque(seeds)
    while queue:
        x,y = queue.popleft()
        if not (0 <= x < 256 and 0 <= y < 256):
            continue
        if found[y,x] or not candidate[y,x]:
            continue
        found[y,x] = True
        queue.extend(((x-1,y),(x+1,y),(x,y-1),(x,y+1)))
    return found


def checker(size):
    im = Image.new('RGB',size,'#e0e3e8'); d=ImageDraw.Draw(im)
    for y in range(0,size[1],8):
        for x in range(0,size[0],8):
            if (x//8+y//8)%2:
                d.rectangle((x,y,x+7,y+7),fill='#f6f7f9')
    return im


def board(items, columns, title, subtitle, path):
    cw,ch = 280,294
    rows = (len(items)+columns-1)//columns
    result = Image.new('RGB',(columns*cw+24,rows*ch+88),'#19212d')
    d=ImageDraw.Draw(result)
    d.text((16,12),title,font=TITLE,fill='white')
    d.text((16,44),subtitle,font=FONT,fill='#c4d2e5')
    for i,(label,im) in enumerate(items):
        x,y=24+(i%columns)*cw,76+(i//columns)*ch
        cell=checker((256,256)); cell.paste(im,(0,0),im if im.mode=='RGBA' else None)
        result.paste(cell,(x,y)); d.text((x,y+263),label,font=FONT,fill='white')
    result.save(path)


def feature_boxes(index):
    top=EYE_TOP[index]
    return {
        'sourcil-g': (78,top-14,122,top),
        'sourcil-d': (134,top-14,178,top),
        'oeil-g': (80,top,120,top+24),
        'oeil-d': (136,top,176,top+24),
        'nez': (110,top+24,146,top+48),
        'bouche': (102,MOUTH_TOP[index],154,MOUTH_TOP[index]+24),
    }


def extract(index, record):
    name=record['id']; path=OUT/record['age_class']/record['sex']
    for sub in ('calques','elements','masques'):
        (path/sub).mkdir(parents=True,exist_ok=True)
    im=Image.open(SOURCE/'portraits'/f'{name}.png').convert('RGB')
    assert im.size == (256,256)
    im.save(path/'portrait.png')
    a=np.array(im); rgb=a.astype(int)
    occupied=np.zeros((256,256),dtype=bool); masks={}; boxes=feature_boxes(index)

    def take(key, mask):
        nonlocal occupied
        masks[key]=mask & ~occupied
        occupied |= masks[key]

    for key,box in boxes.items():
        x0,y0,x1,y1=box; mask=np.zeros_like(occupied);mask[y0:y1,x0:x1]=True
        assert not (occupied & mask).any(), (name,key)
        take(key,mask)
    neutral=(rgb.min(axis=2)>165) & (rgb.max(axis=2)-rgb.min(axis=2)<38) & (rgb[:,:,2]>rgb[:,:,0])
    border=[(x,y) for x in range(256) for y in (0,255)]
    border += [(x,y) for y in range(256) for x in (0,255)]
    take('fond', connected(neutral,border))
    for side,x0 in zip(('g','d'),EAR_X[index]):
        y0=EAR_TOP[index]
        # Masked ears retain their natural silhouette and small skin/hair joins.
        if side=='g':
            points=[(x0+5,y0),(x0+16,y0+2),(x0+22,y0+13),(x0+23,y0+38),
                    (x0+12,y0+36),(x0+3,y0+25),(x0,y0+10)]
        else:
            points=[(x0+2,y0+2),(x0+13,y0),(x0+20,y0+9),(x0+18,y0+25),
                    (x0+10,y0+36),(x0-2,y0+38),(x0-1,y0+14)]
        take(f'oreille-{side}',polygon(points))
    take('visage',polygon(FACES[index]))
    # Recover the shirt by colour AND connectivity, keeping low pigtails intact.
    shirt=(rgb.max(axis=2)<185) & (rgb.max(axis=2)-rgb.min(axis=2)<45) & (rgb[:,:,2]>=rgb[:,:,0])
    clothing=connected(shirt,[(x,255) for x in range(256)])
    neck=polygon([(91,176),(166,176),(171,211),(186,224),(160,244),
                  (96,244),(73,223),(88,211)])
    take('cou-buste',clothing | neck)
    hair_region=np.zeros_like(occupied)
    hair_region[:EAR_TOP[index]-2,:]=True
    if index in (2,4,6):
        hair_region[:{2:182,4:192,6:210}[index],:]=True
    if index==0:
        hair_region[140:231,20:95]=True
        hair_region[140:231,160:237]=True
    take('chevelure',hair_region)
    take('raccords',~occupied)
    coverage=sum(mask.astype(int) for mask in masks.values())
    assert np.all(coverage==1)
    pieces={}; entries={}
    for key in ORDER:
        rgba=np.zeros((256,256,4),dtype=np.uint8)
        rgba[masks[key],:3]=a[masks[key]];rgba[masks[key],3]=255
        layer=Image.fromarray(rgba);pieces[key]=layer
        layer.save(path/'calques'/f'{key}.png')
        Image.fromarray((masks[key]*255).astype(np.uint8)).save(path/'masques'/f'{key}.png')
        box=boxes.get(key,layer.getbbox()); assert box is not None
        crop=layer.crop(box);crop.save(path/'elements'/f'{key}.png')
        anchor=[crop.width//2,crop.height//2]
        entries[key]={
            'layer':f'calques/{key}.png','crop':f'elements/{key}.png','mask':f'masques/{key}.png',
            'source_box':list(box),'paste_at':list(box[:2]),'size':list(crop.size),
            'local_anchor':anchor,'source_anchor':[box[0]+anchor[0],box[1]+anchor[1]],
            'pixels':int(masks[key].sum()),
            'compatibility':'equal-sized-copy-window' if key in TRAITS else 'source-specific-contour',
        }
        # Validate persisted files, not just the in-memory renderer.
        loaded=np.array(Image.open(path/'calques'/f'{key}.png'))
        assert np.array_equal(loaded[masks[key],:3],a[masks[key]])
        assert set(np.unique(loaded[:,:,3])).issubset({0,255})
    rebuilt=Image.new('RGBA',(256,256))
    for key in ORDER:
        crop=Image.open(path/entries[key]['crop']).convert('RGBA')
        rebuilt.alpha_composite(crop,tuple(entries[key]['paste_at']))
    assert rebuilt.getchannel('A').getextrema()==(255,255)
    diff=int(np.any(np.array(rebuilt.convert('RGB'))!=a,axis=2).sum())
    assert diff==0
    rebuilt.convert('RGB').save(path/'recomposition.png')
    support=Image.new('RGBA',(256,256))
    for key in ORDER:
        if key not in TRAITS:
            support.alpha_composite(pieces[key])
    support.save(path/'support-traits.png')
    # Convenience groups: derived from the two layers, not extra partition parts.
    for group,keys in [('paire-yeux',('oeil-g','oeil-d')),('paire-sourcils',('sourcil-g','sourcil-d'))]:
        layer=Image.new('RGBA',(256,256))
        for key in keys:layer.alpha_composite(pieces[key])
        box=layer.getbbox(); layer.save(path/'calques'/f'{group}.png')
        layer.crop(box).save(path/'elements'/f'{group}.png')
        entries[group]={'layer':f'calques/{group}.png','crop':f'elements/{group}.png',
                        'source_box':list(box),'paste_at':list(box[:2]),'size':[box[2]-box[0],box[3]-box[1]],
                        'derived_from':list(keys),'included_in_recomposition':False}
    meta={'id':name,'age_class':record['age_class'],'sex':record['sex'],'size':[256,256],
          'source':'portrait.png',
          'origin':f'../../../generations-v1/portraits/{name}.png',
          'support_without_six_traits':'support-traits.png',
          'source_sha256':hashlib.sha256((path/'portrait.png').read_bytes()).hexdigest(),
          'axes':'x right, y down; g/d refer to image sides',
          'layer_order':ORDER,'parts':entries,
          'verification':{'different_pixels':diff,'partition_exactly_once':True,'binary_alpha':True}}
    (path/'manifest.json').write_text(json.dumps(meta,indent=2,ensure_ascii=False)+'\n')
    board([(LABELS[key],pieces[key]) for key in ORDER],4,
          f'{AGE_LABELS[index//2]} · {record["sex"]} · 13 pièces',
          'Pixels sources intacts · position originale · damier = transparence',path/'planche-pieces.png')
    return meta,pieces,im


def main():
    OUT.mkdir(parents=True,exist_ok=True)
    records=json.loads((SOURCE/'prompts.json').read_text())['portraits']
    all_meta=[];all_pieces=[];portraits=[]
    for i,record in enumerate(records):
        meta,pieces,im=extract(i,record)
        all_meta.append(meta);all_pieces.append(pieces);portraits.append(im)
    board([(f'{r["id"]} · 256 × 256',im) for r,im in zip(records,portraits)],5,
          'Dix portraits sources · cinq classes d’âge × deux sexes',
          'Réduction au plus proche voisin ; aucune retouche des traits.',OUT/'planche-portraits.png')
    board([(f'{r["id"]} · 0 pixel différent',Image.open(OUT/r['age_class']/r['sex']/'recomposition.png'))
           for r in records],5,'Dix recompositions vérifiées',
          'Assemblages à partir des PNG découpés et des coordonnées des manifestes.',OUT/'planche-recompositions.png')
    # Compact comparison: each row is one bank; each column one reusable trait.
    headers=['Portrait','Sourcil G','Sourcil D','Œil G','Œil D','Nez','Bouche']
    widths=[160,112,112,104,104,96,128]; total=sum(widths)+32
    catalog=Image.new('RGB',(total,10*170+88),'#19212d'); d=ImageDraw.Draw(catalog)
    d.text((16,10),'Catalogue des traits · dix banques',font=TITLE,fill='white')
    x=16
    for label,w in zip(headers,widths):d.text((x,50),label,font=FONT,fill='#c4d2e5');x+=w
    for i,(record,meta,im) in enumerate(zip(records,all_meta,portraits)):
        y=82+i*170;x=16
        catalog.paste(im.resize((128,128),Image.Resampling.NEAREST),(x,y))
        d.text((x,y+132),AGE_LABELS[i//2],font=FONT,fill='white')
        d.text((x,y+148),record['sex'],font=FONT,fill='#c4d2e5');x+=widths[0]
        for key,w in zip(TRAITS,widths[1:]):
            p=OUT/record['age_class']/record['sex']/meta['parts'][key]['crop']
            part=Image.open(p).convert('RGBA');part=part.resize((part.width*2,part.height*2),Image.Resampling.NEAREST)
            cell=checker((w-8,128));cell.paste(part,((cell.width-part.width)//2,(128-part.height)//2),part)
            catalog.paste(cell,(x,y));x+=w
    catalog.save(OUT/'catalogue-traits.png')
    # Index by both category and part kind, without duplicating the part assets.
    index={'version':1,'portrait_size':[256,256],'classes':['enfant','adolescent','adulte','age-mur','age'],
           'sexes':['feminin','masculin'],'bank_count':len(records),'partition_parts_per_bank':len(ORDER),
           'banks':[],'by_part':{key:[] for key in ORDER+['paire-yeux','paire-sourcils']}}
    for record,meta in zip(records,all_meta):
        rel=f'{record["age_class"]}/{record["sex"]}'
        index['banks'].append({'id':record['id'],'age_class':record['age_class'],'sex':record['sex'],
                               'manifest':f'{rel}/manifest.json','sheet':f'{rel}/planche-pieces.png'})
        for key,entry in meta['parts'].items():
            index['by_part'][key].append({'bank':record['id'],'file':f'{rel}/{entry["crop"]}'})
    (OUT/'index.json').write_text(json.dumps(index,indent=2,ensure_ascii=False)+'\n')
    # Exhaust all 10x10 donor/recipient pairs for each of the six fixed windows.
    combinations=0
    for receiver,record in enumerate(records):
        for donor in range(len(records)):
            for key in TRAITS:
                box=all_meta[receiver]['parts'][key]['source_box']
                donor_box=all_meta[donor]['parts'][key]['source_box']
                patch=all_pieces[donor][key].crop(donor_box)
                assert patch.size==(box[2]-box[0],box[3]-box[1])
                assert patch.getchannel('A').getextrema()==(255,255)
                actual=portraits[receiver].copy();actual.paste(patch.convert('RGB'),box[:2])
                expected=portraits[receiver].copy();expected.paste(portraits[donor].crop(donor_box),box[:2])
                assert np.array_equal(np.array(actual),np.array(expected))
                combinations+=1
    report={'portraits':10,'partition_parts':130,'derived_pairs':20,'derived_supports':10,
            'recompositions':[{'id':m['id'],**m['verification']} for m in all_meta],
            'fixed_window_swaps_verified':combinations,
            'colour_matching_or_seamless_structural_interchangeability_claimed':False}
    (OUT/'verification.json').write_text(json.dumps(report,indent=2)+'\n')
    # A downloadable, self-contained archive includes original 256px references.
    archive=OUT.parent/'element-banks-v1.zip'
    with zipfile.ZipFile(archive,'w',compression=zipfile.ZIP_DEFLATED) as z:
        for file in sorted(OUT.rglob('*')):
            if file.is_file():z.write(file,file.relative_to(OUT.parent))
    print(json.dumps({'banks':10,'partition_parts':130,'derived_pairs':20,
                      'recomposition_different_pixels':0,'swaps_verified':combinations,'archive':str(archive)}))


if __name__ == '__main__':
    main()
