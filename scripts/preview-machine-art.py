"""Orthographic review sheet rendered from the authored block JSON and textures."""
from pathlib import Path
import json, math
from PIL import Image, ImageDraw, ImageFont, ImageEnhance

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'src/main/resources/assets/nullspace'
IDS = ['ship_computer','computer_terminal','pad_computer','computer_base','station_core',
       'generator','engine','battery','air_supply','local_scanner',
       'camera','monitor','weapon','docking_base','ship_beacon']
sheet = Image.new('RGB',(1250,810),'#252525')
d = ImageDraw.Draw(sheet)
font = ImageFont.truetype('C:/Windows/Fonts/segoeui.ttf',18)
title = ImageFont.truetype('C:/Windows/Fonts/segoeuib.ttf',25)
d.text((26,16),'NULLSPACE / MINECRAFT MACHINE BLOCKS',font=title,fill='#e3e0d5')
d.text((27,53),'Full cubes  /  16px iron and stone  /  clear functional faces and redstone details',font=font,fill='#c1b9a8')
view=[1.,1.,-1.]
def dot(a,b):return sum(x*y for x,y in zip(a,b))
def transform(matrix,v):return [dot(row,v) for row in matrix]
face_indices={'north':[0,1,2,3],'east':[1,5,6,2],'up':[3,2,6,7],
              'south':[5,4,7,6],'west':[4,0,3,7],'down':[4,5,1,0]}
normals={'north':[0,0,-1],'east':[1,0,0],'up':[0,1,0],
         'south':[0,0,1],'west':[-1,0,0],'down':[0,-1,0]}

for i,kind in enumerate(IDS):
    x=(i%5)*250; y=92+(i//5)*235
    d.rounded_rectangle((x+12,y,x+238,y+220),radius=9,fill='#353432',outline='#59564f')
    model=json.loads((ASSETS/f'models/block/{kind}.json').read_text())
    faces=[]
    for element in model['elements']:
        a=element['from'];b=element['to']
        v=[[a[0],a[1],a[2]],[b[0],a[1],a[2]],[b[0],b[1],a[2]],[a[0],b[1],a[2]],
                    [a[0],a[1],b[2]],[b[0],a[1],b[2]],[b[0],b[1],b[2]],[a[0],b[1],b[2]]]
        rotation=[[1,0,0],[0,1,0],[0,0,1]]
        if 'rotation' in element:
            r=element['rotation'];angle=math.radians(r['angle']);c=math.cos(angle);s=math.sin(angle)
            if r['axis']=='z':rotation=[[c,-s,0],[s,c,0],[0,0,1]]
            elif r['axis']=='y':rotation=[[c,0,s],[0,1,0],[-s,0,c]]
            else:rotation=[[1,0,0],[0,c,-s],[0,s,c]]
            origin=r['origin'];v=[[a+b for a,b in zip(transform(rotation,[p[j]-origin[j] for j in range(3)]),origin)] for p in v]
        for side,face in element['faces'].items():
            normal=transform(rotation,normals[side])
            if dot(normal,view)<=.001:continue
            points=[v[j] for j in face_indices[side]]
            texture=model['textures'][face['texture'][1:]].split(':')[1]
            tex=Image.open(ASSETS/f'textures/{texture}.png').convert('RGBA')
            tex=ImageEnhance.Brightness(tex).enhance(.7+.3*max(0,normal[1])+.15*max(0,-normal[2]))
            u0,v0,u1,v1=face['uv']
            if side=='up':uv=[[u0,v0],[u1,v0],[u1,v1],[u0,v1]]
            elif side=='east':uv=[[u1,v1],[u0,v1],[u0,v0],[u1,v0]]
            else:uv=[[u0,v1],[u1,v1],[u1,v0],[u0,v0]]
            turns=face.get('rotation',0)//90
            uv=uv[turns:]+uv[:turns]
            uv=[[u*tex.width/16,v*tex.height/16] for u,v in uv]
            p=[[(q[0]+q[2]-16)*4.2*.866+x+125,(q[0]-q[2])*.5*4.2-q[1]*4.2+y+145] for q in points]
            # All faces are parallelograms under this orthographic projection.
            dx1=p[1][0]-p[0][0];dy1=p[1][1]-p[0][1];dx2=p[2][0]-p[0][0];dy2=p[2][1]-p[0][1]
            determinant=dx1*dy2-dy1*dx2
            if abs(determinant)<1e-7:continue
            coefficients=[]
            for axis in range(2):
                a=((uv[1][axis]-uv[0][axis])*dy2-(uv[2][axis]-uv[0][axis])*dy1)/determinant
                b=(-(uv[1][axis]-uv[0][axis])*dx2+(uv[2][axis]-uv[0][axis])*dx1)/determinant
                coefficients.extend([a,b,uv[0][axis]-a*p[0][0]-b*p[0][1]])
            layer=tex.transform(sheet.size,Image.Transform.AFFINE,coefficients,Image.Resampling.NEAREST)
            mask=Image.new('L',sheet.size);ImageDraw.Draw(mask).polygon([tuple(q) for q in p],fill=255)
            faces.append((dot([sum(q[j] for q in points)/4 for j in range(3)],view),layer,mask))
    for _,layer,mask in sorted(faces,key=lambda f:f[0]):sheet.paste(layer,(0,0),mask)
    label=kind.replace('_',' ').title()
    d=ImageDraw.Draw(sheet)
    d.text((x+24,y+184),label,font=font,fill='#e5eeee')
    if kind=='camera':d.text((x+24,y+204),'Wall mount / recessed lens',font=ImageFont.truetype('C:/Windows/Fonts/segoeui.ttf',12),fill='#c1b9a8')

path=ROOT/'docs/images/machine-designs-industrial.png'
sheet.save(path)
print(path)

# A magnified, nearest-neighbor view of the actual 16x16 materials, without model distortion.
swatches=Image.new('RGB',(1250,660),'#252525')
sd=ImageDraw.Draw(swatches)
sd.text((26,16),'NULLSPACE / 16 x 16 PIXEL ART',font=title,fill='#e5eeee')
for i,kind in enumerate(IDS):
    x=(i%5)*250;y=65+(i//5)*195
    model=json.loads((ASSETS/f'models/block/{kind}.json').read_text())
    material=model['textures']['front'].split(':')[1]
    tile=Image.open(ASSETS/f'textures/{material}.png').convert('RGB')
    swatches.paste(tile.resize((128,128),Image.Resampling.NEAREST),(x+60,y))
    label='Camera lens (4px face)' if kind=='camera' else kind.replace('_',' ').title()
    sd.text((x+24,y+140),label,font=font,fill='#e5eeee')
swatch_path=ROOT/'docs/images/machine-textures-16px.png'
swatches.save(swatch_path)
print(swatch_path)
