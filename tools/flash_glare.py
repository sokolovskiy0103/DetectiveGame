import sys
import numpy as np
from PIL import Image
src,out,cx,cy=sys.argv[1],sys.argv[2],float(sys.argv[3]),float(sys.argv[4])
a=np.asarray(Image.open(src).convert('RGB')).astype(np.float32)/255
h,w,_=a.shape
y,x=np.mgrid[0:h,0:w].astype(np.float32)
dx,dy=(x-cx),(y-cy)
r=np.sqrt((dx/1.0)**2+(dy/0.95)**2)
core=np.clip(1.9*np.exp(-(r/128)**2.0),0,1)  # засвіт із плавним спадом
halo=np.exp(-(r/300)**2)*0.28           # слабке гало
ang=np.arctan2(dy,dx)
rays=(0.5+0.5*np.cos(ang*6+0.4))**24*np.exp(-r/170)*0.35  # промені на склі
g=np.clip(core+halo+rays,0,1)[...,None]
tint=np.array([1.0,0.97,0.92],dtype=np.float32)
o=1-(1-a)*(1-g*tint)
Image.fromarray((np.clip(o,0,1)*255+.5).astype(np.uint8)).save(out)
