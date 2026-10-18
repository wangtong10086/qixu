"""Assemble unmodified device screenshots for side-by-side review (requires Pillow)."""
from PIL import Image,ImageDraw,ImageFont
from pathlib import Path
root=Path(__file__).resolve().parents[1]/'docs/compose-previews'
font=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',20)
for variant in 'ABC':
    canvas=Image.new('RGB',(1296,750),(243,237,247));draw=ImageDraw.Draw(canvas)
    for i,key in enumerate(['overview','apps','detail','template']):
        im=Image.open(root/f'{variant}-{key}.png');im.thumbnail((324,720));canvas.paste(im,(i*324,30));draw.text((i*324+12,5),f'{variant} / {key}',fill='#6750A4',font=font)
    canvas.save(root/f'{variant}-contact.png')
canvas=Image.new('RGB',(1080,830),(243,237,247));draw=ImageDraw.Draw(canvas)
for i,v in enumerate('ABC'):
    im=Image.open(root/f'{v}-overview.png');im.thumbnail((360,800));canvas.paste(im,(i*360,30));draw.text((i*360+12,5),f'{v} / Compose',fill='#6750A4',font=font)
canvas.save(root/'overview-comparison.png')
