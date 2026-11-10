from PIL import Image
import numpy as np
# Interior horizontal glazing bars, avoiding vertical jambs.
features={'fire':[(285,323,545,570),(379,414,583,608),(471,505,622,648),(552,587,663,691)],'school':[(280,346,530,565),(500,565,632,665),(280,320,692,722),(520,572,794,826)]}
for name,fs in features.items():
 a=np.asarray(Image.open('assets/sprites/civic/'+name+'-v5.png').convert('RGB')).mean(axis=2)
 for x1,x2,y1,y2 in fs:
  xs=np.arange(x1,x2); cx=(x1+x2)/2; scores=[]
  for m in np.arange(.2,.61,.005):
   for cy in np.arange(y1,y2,.5):
    ys=np.rint(cy+m*(xs-cx)).astype(int)
    # Dark mullion with bright glass on both sides.
    v=(a[ys-5,xs]+a[ys+5,xs])/2-a[ys,xs]
    scores.append((np.mean(v),m,cy))
  score, slope, cy = max(scores)
  assert abs(slope - .465) <= .02, (name, x1, slope)
  print(f"{name} window {x1}:{x2}: slope={slope:.3f}")

for name in features:
 old=np.asarray(Image.open('assets/sprites/civic/'+name+'-v4.png'))
 new=np.asarray(Image.open('assets/sprites/civic/'+name+'-v5.png'))
 assert np.array_equal(old[:,:,3],new[:,:,3]), name + ' alpha changed'
 assert np.array_equal(old[:,640:],new[:,640:]), name + ' right facade changed'
print('Facade checks passed')
