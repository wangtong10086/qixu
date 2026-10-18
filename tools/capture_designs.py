"""Capture the isolated Compose design app; never operates the controller service."""
import argparse
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
import time

parser=argparse.ArgumentParser()
parser.add_argument('--serial',required=True)
args=parser.parse_args()
project=Path(__file__).resolve().parents[1]
out=project/'docs'/'compose-previews';out.mkdir(exist_ok=True)
pkg='dev.local.fuxicontrol.design'
def adb(*values,binary=False):
    return subprocess.check_output(['adb','-s',args.serial,*values],text=not binary)
for variant in ['A','B','C']:
    for key,page in [('overview','概览'),('apps','应用'),('detail','应用策略'),('template','默认模板')]:
        adb('shell','am','start','-S','-W','-n',pkg+'/.DesignActivity','--es','variant',variant,'--es','page',page)
        time.sleep(1)
        remote='/data/local/tmp/fuxi-design.xml'
        adb('shell','uiautomator','dump','--compressed',remote)
        tree=ET.fromstring(adb('shell','cat',remote))
        if not any(n.get('package')==pkg for n in tree.iter('node')):
            raise RuntimeError('Design app is not visible; unlock the device before capturing')
        (out/f'{variant}-{key}.png').write_bytes(adb('exec-out','screencap','-p',binary=True))
        print('Captured',variant,page,flush=True)
adb('shell','rm','/data/local/tmp/fuxi-design.xml')
