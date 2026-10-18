"""UDP IPv4/IPv6 loopback validation; rate-limit only the disposable probe UID."""
import json
from pathlib import Path
import shlex
import time
from device import adb,root

project=Path(__file__).resolve().parents[1]
def rpc(value): return json.loads(root('/data/adb/fuxicontrol/fuxictl rpc '+shlex.quote(json.dumps(value))))
original=rpc({'command':'config.get'})
name='dev.local.fuxicontrol.probe'
config=json.loads(json.dumps(original))
if config['mode']!='enforce': raise RuntimeError('Network test requires enforce mode')
pid=root('CLASSPATH=/data/adb/fuxicontrol/controller.apk app_process /system/bin dev.local.fuxicontrol.service.DatagramFixture >/data/adb/fuxicontrol/datagram-fixture.log 2>&1 </dev/null & echo $!').strip()
try:
    for mode,ipv6,bps in [('upload',False,512000),('download',True,2000000)]:
        config['apps'][name]={'autostart':'allow','background':'normal','network':'allow','uploadBps':bps if mode=='upload' else 0,'downloadBps':bps if mode=='download' else 0,'burstBytes':65536}
        rpc({'command':'config.replace','config':config,'expectedRevision':rpc({'command':'config.get'})['revision']})
        uid=rpc({'command':'app.get','package':name})['effective']['uid']
        before=rpc({'command':'network.stats','uid':uid})
        adb('shell','am','force-stop',name)
        root('rm -f /data/user/0/'+name+'/files/datagram.json')
        adb('shell','am','start','-W','-n',name+'/.Main','--es','udp',mode,'--ez','ipv6',str(ipv6).lower())
        time.sleep(22)
        result=json.loads(root('cat /data/user/0/'+name+'/files/datagram.json'))
        after=rpc({'command':'network.stats','uid':uid})
        old=next((e for e in before.get('counters',[]) if e['direction']==mode),{'passedBytes':0,'droppedPackets':0})
        counter=next(e for e in after['counters'] if e['direction']==mode)
        passed=counter['passedBytes']-old['passedBytes'];dropped=counter['droppedPackets']-old['droppedPackets']
        # Include the configured initial burst and a small launch/collection timing margin.
        if passed>bps*24/8+65536 or dropped<=0: raise RuntimeError('Policer ceiling/drop accounting failed')
        print(json.dumps({'case':result,'passedWireBytes':passed,'droppedPackets':dropped}),flush=True)
        (project/'private'/('udp-'+mode+'.json')).write_text(json.dumps({'result':result,'before':before,'after':after},indent=2))
finally:
    rpc({'command':'config.replace','config':original})
    if 'dev.local.fuxicontrol.service.DatagramFixture' in root('cat /proc/'+pid+'/cmdline'): root('kill '+pid)
    adb('shell','am','force-stop',name)
    print('Restored original policy and Android network entry points',flush=True)
