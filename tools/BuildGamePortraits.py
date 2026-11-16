"""Build the versioned runtime portrait bank from the six-age identity sheets.

Pillow + NumPy; generation is a separate, documented step. Registration uses
nearest-neighbour pixel sampling. Each family has its own generated portraits.
All runtime layers use the SAME binary partition; composition never rescales.
"""
from pathlib import Path
import json
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
ART = ROOT/'assets/portraits/bank-v1'
OUT = ROOT/'client/src/main/resources/city/client/portraits/bank-v1'
AGES = ['child','teen','adult','mature','senior','elder']
PARTS = ['frame','face','ears','brows','eyes','nose','mouth']
EYES = [
    [105,100,99,100,100,100], [108,103,103,103,103,103],
    [114,108,107,107,107,107], [113,102,102,102,102,102],
    [112,107,106,106,106,106], [110,105,104,104,104,104],
    [109,106,106,105,106,106], [109,106,104,104,104,105],
    [102,100,99,99,100,100], [120,109,108,106,106,106],
]



def polygon(points):
    image=Image.new('L',(256,256));ImageDraw.Draw(image).polygon(points,fill=255)
    return np.array(image)>0


def partition():
    occupied=np.zeros((256,256),bool);masks={}
    def take(name,mask):
        nonlocal occupied
        masks[name]=mask & ~occupied;occupied|=mask
    def rectangles(boxes):
        mask=np.zeros_like(occupied)
        for x0,y0,x1,y1 in boxes:mask[y0:y1,x0:x1]=True
        return mask
    take('brows',rectangles([(78,94,122,108),(134,94,178,108)]))
    take('eyes',rectangles([(80,108,122,132),(134,108,176,132)]))
    take('nose',rectangles([(110,132,146,155)]))
    take('mouth',rectangles([(102,155,154,180)]))
    # Outer ear/head contours stay with the frame; inner ear variations cannot
    # accidentally import another hairstyle (e.g. the pigtail behind an ear).
    take('ears',rectangles([(68,116,80,146),(176,116,188,146)]))
    take('face',polygon([(84,92),(172,92),(172,145),(163,174),(145,186),
                         (111,186),(93,174),(84,145)]))
    take('frame',~occupied)
    assert np.all(sum(m.astype(int) for m in masks.values())==1)
    return masks


def register(raw,eye):
    a=np.array(raw).astype(float)
    # Refine the approximate iris row using local whites/iris contrast; the
    # age-sheet anchor limits the search so silver eyebrows cannot win.
    eye_pixels=np.concatenate((a[90:132,89:120],a[90:132,136:167]),axis=1)
    white=(eye_pixels[:,:,2]>eye_pixels[:,:,0]*.58)&(eye_pixels[:,:,1]>eye_pixels[:,:,0]*.7)&(eye_pixels.min(axis=2)>85)
    lum=eye_pixels[:,:,0]*.3+eye_pixels[:,:,1]*.59+eye_pixels[:,:,2]*.11
    contrast=np.quantile(lum,.9,axis=1)-np.quantile(lum,.1,axis=1)
    score=white.sum(axis=1)*contrast*np.exp(-.5*((np.arange(90,132)-eye)/5)**2)
    eye=int(np.argmax(np.convolve(score,[1,2,1],mode='same'))+90) if score.max()>0 else eye
    mouth_top=eye+29
    mouth=a[mouth_top:eye+50,117:139]
    darkness=(mouth[:,:,0]*.3+mouth[:,:,1]*.59+mouth[:,:,2]*.11).mean(axis=1)
    lip=int(np.argmin(np.convolve(darkness,[1,2,1],mode='valid'))+mouth_top+1)
    source_rows=np.rint(np.interp(np.arange(256),[0,116,167,195,255],
                                 [0,eye,lip,min(lip+29,211),255])).astype(int)
    return Image.fromarray(np.array(raw)[source_rows]),{'eye_source_y':eye,'lip_source_y':lip}


def main():
    OUT.mkdir(parents=True,exist_ok=True)
    (ART/'registered').mkdir(exist_ok=True)
    (ART/'families').mkdir(exist_ok=True)
    records=json.loads((ART/'sources.json').read_text())['records']
    masks=partition();manifest={'version':1,'families':3,'variants':5,'ages':AGES,'parts':PARTS,'identities':[]}
    font=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',13)
    previews=[Image.new('RGB',(1536,2760),'#19212d') for _ in range(3)]
    for i,record in enumerate(records):
        registration=[]
        for family in range(3):
            sheet=Image.open(ART/'family-originals'/f'{family}-{record["id"]}.png').convert('RGB')
            w,h=sheet.size
            for age_index,age in enumerate(AGES):
                col,row=age_index%3,age_index//3
                raw=sheet.crop((round(col*w/3),round(row*h/2),round((col+1)*w/3),round((row+1)*h/2)))
                raw=raw.resize((256,256),Image.Resampling.NEAREST)
                image,landmarks=register(raw,EYES[i][age_index])
                registration.append({'family':family,'age':age,**landmarks})
                a=np.array(image)
                output=OUT/f'family-{family}'/record['sex']/age;output.mkdir(parents=True,exist_ok=True)
                reconstructed=Image.new('RGBA',(256,256))
                for part in PARTS:
                    rgba=np.zeros((256,256,4),np.uint8);mask=masks[part]
                    rgba[mask,:3]=a[mask];rgba[mask,3]=255
                    layer=Image.fromarray(rgba)
                    layer.save(output/f'{part}-{record["variant"]}.png')
                    reconstructed.alpha_composite(layer)
                assert np.array_equal(np.array(reconstructed.convert('RGB')),a)
                assert reconstructed.getchannel('A').getextrema()==(255,255)
                image.save(ART/'families'/f'{family}-{record["id"]}-{age}.png')
                previews[family].paste(image,(age_index*256,i*276))
                ImageDraw.Draw(previews[family]).text((age_index*256+6,i*276+258),f'{record["id"]} / {age}',font=font,fill='white')
        manifest['identities'].append({'id':record['id'],'sex':record['sex'],'variant':record['variant'],'registration':registration})
    for family,preview in enumerate(previews):preview.save(ART/f'family-{family}-all-ages.png')
    # Show one stable mixed recipe per sex/family through all six ages.
    mixed=Image.new('RGB',(1536,6*276),'#19212d');recipes=[]
    for family in range(3):
        for sex_index,sex in enumerate(['female','male']):
            row=family*2+sex_index
            selection=[1,3,0,2,4,1,3] if sex=='female' else [1,0,3,4,2,3,0]
            for age_index,age in enumerate(AGES):
                assembled=Image.new('RGBA',(256,256))
                for part,variant in zip(PARTS,selection):
                    assembled.alpha_composite(Image.open(OUT/f'family-{family}'/sex/age/f'{part}-{variant}.png'))
                assert assembled.getchannel('A').getextrema()==(255,255)
                mixed.paste(assembled.convert('RGB'),(age_index*256,row*276))
                ImageDraw.Draw(mixed).text((age_index*256+6,row*276+258),f'Famille {family+1} / {sex} / {age}',font=font,fill='white')
            recipes.append({'family':family,'sex':sex,'variants':dict(zip(PARTS,selection))})
    mixed.save(ART/'mixed-aging-preview.png')
    (ART/'mixed-recipes.json').write_text(json.dumps(recipes,indent=2)+'\n')
    (ART/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    (OUT/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    count=len(list(OUT.rglob('*.png')))
    assert count==3*2*6*5*7
    (ART/'verification.json').write_text(json.dumps({'portraits':180,'layers':count,'identical_recompositions':180,
        'shared_binary_partition':True,'families':3,'sexes':2,'ages':6,'variants_per_part_and_sex':5},indent=2)+'\n')
    print(f'{count} runtime layers; 180 exact registered/family recompositions; previews in {ART}')


if __name__=='__main__':main()
