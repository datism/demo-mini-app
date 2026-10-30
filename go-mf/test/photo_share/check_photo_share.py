#!/usr/bin/env python3
"""Two-UE browser regression, using Chrome CDP and websocket-client (no npm install).

Run: python3 go-mf/test/photo_share/check_photo_share.py
Exercises the actual shipped page with two native-bridge stand-ins. It does not
replace Android device tests for URI import, SCTP transfer, camera or audio.
"""
import base64
import functools
import http.server
import json
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import threading
import time
import urllib.request
import zlib

import websocket

ROOT = Path(__file__).resolve().parents[3]
PAGE = '/go-mf/static/apps/photo-share/index.html'


def png(width, height):
    def chunk(name, body):
        return struct.pack('>I', len(body)) + name + body + struct.pack('>I', zlib.crc32(name + body) & 0xffffffff)
    pixels = (b'\0' + bytes([245, 245, 245]) * width) * height
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(pixels)) + chunk(b'IEND', b'')


class Handler(http.server.SimpleHTTPRequestHandler):
    fixtures = {'/portrait.png': png(1080, 1920), '/landscape.png': png(1920, 1080), '/thumb.png': png(144, 256)}

    def do_GET(self):
        body = self.fixtures.get(self.path)
        if body:
            self.send_response(200)
            self.send_header('Content-Type', 'video/webm' if self.path.endswith('.webm') else 'image/png')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            super().do_GET()

    def log_message(self, *args):
        pass


class Tab:
    def __init__(self, port, width, height, dpr):
        request = urllib.request.Request(f'http://127.0.0.1:{port}/json/new?about:blank', method='PUT')
        with urllib.request.urlopen(request) as response:
            target = json.load(response)
        self.ws = websocket.create_connection(target['webSocketDebuggerUrl'], suppress_origin=True, timeout=15)
        self.counter = 0
        self.call('Page.enable')
        self.call('Runtime.enable')
        self.call('Emulation.setDeviceMetricsOverride', dict(width=width, height=height, deviceScaleFactor=dpr, mobile=True))
        self.call('Page.addScriptToEvaluateOnNewDocument', {'source': '''
            window.__sent = []; window.__errors = [];
            window.addEventListener('error', e => __errors.push(e.message));
            window.AndroidBridge = {
              send: (stream, text) => __sent.push(JSON.parse(text)),
              sendPhoto: () => JSON.stringify({ok:true, chunks:1, bytes:10}),
              pickMedia: () => { window.__picked = true; }
            };
        '''})

    def call(self, method, params=None):
        self.counter += 1
        ident = self.counter
        self.ws.send(json.dumps(dict(id=ident, method=method, params=params or {})))
        while True:
            reply = json.loads(self.ws.recv())
            if reply.get('id') == ident:
                if 'error' in reply:
                    raise AssertionError(reply['error'])
                return reply.get('result', {})

    def js(self, expr):
        result = self.call('Runtime.evaluate', dict(expression=expr, returnByValue=True, awaitPromise=True))
        if 'exceptionDetails' in result:
            raise AssertionError(result['exceptionDetails'])
        return result.get('result', {}).get('value')

    def receive(self, msg):
        self.js('window.receiveData(' + json.dumps(json.dumps(msg)) + ')')

    def wait(self, expression, timeout=8):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self.js(expression):
                return
            time.sleep(.04)
        raise AssertionError('Timed out: ' + expression + ' ' + str(self.js("({current:state.current,video:$('sharedVideo').currentSrc,error:$('sharedVideo').error && $('sharedVideo').error.message,network:$('sharedVideo').networkState})")))

    def navigate(self, url):
        self.call('Page.navigate', {'url': url})
        self.wait("document.readyState === 'complete' && typeof window.onNativeMediaImported === 'function'")

    def mouse(self, kind, x, y):
        self.call('Input.dispatchMouseEvent', dict(type=kind, x=x, y=y, button='left', buttons=0 if kind == 'mouseReleased' else 1, clickCount=1))

    def close(self):
        self.ws.close()


def pump(a, b):
    for _ in range(8):
        sent = a.js('__sent.splice(0)')
        replies = b.js('__sent.splice(0)')
        for message in sent:
            b.receive(message)
        for message in replies:
            a.receive(message)
        if not sent and not replies:
            return


def geometry(tab):
    return tab.js('''(() => { const r = $('photoImg').getBoundingClientRect(), c = $('drawCanvas');
      return {w:r.width,h:r.height,cw:c.width,ch:c.height,ratio:r.width/r.height,url:state.photos[state.current].url}; })()''')


def run(a, b, origin, out):
    passed = 0
    def check(name, condition):
        nonlocal passed
        assert condition, name
        passed += 1
        print('PASS', name, flush=True)

    assets = [dict(id='portrait', mime='image/jpeg', w=1080, h=1920, bytes=10, url=origin+'/portrait.png', thumb=''),
              dict(id='landscape', mime='image/jpeg', w=1920, h=1080, bytes=10, url=origin+'/landscape.png', thumb='')]
    a.js('window.onNativeMediaImported(' + json.dumps(assets) + ')')
    sent = a.js('__sent')
    check('offer precedes navigation', sent[0]['t'] == 'offer' and sent[1]['t'] == 'goto')
    pump(a, b)
    for asset in assets:
        b.receive(dict(asset, t='stored'))
    pump(a, b)
    a.wait("$('photoImg').complete && $('photoImg').naturalWidth === 1080")
    b.wait("$('photoImg').complete && $('photoImg').naturalWidth === 1080")
    check('portrait aspect fits both UEs', all(abs(geometry(tab)['ratio'] - 1080/1920) < .005 for tab in [a, b]))
    check('DPR canvas dimensions match CSS pixels', all(abs(geometry(tab)['cw'] / tab.js('devicePixelRatio') - geometry(tab)['w']) <= 1 for tab in [a,b]))
    for idx in range(8):
        ident = 'landscape' if idx % 2 == 0 else 'portrait'
        a.js('gotoPhoto(' + json.dumps(ident) + ', true)'); pump(a, b)
        for tab in [a, b]:
            tab.wait("$('photoImg').complete && $('photoImg').naturalWidth === " + ('1920' if ident == 'landscape' else '1080'))
        expected = 1920/1080 if ident == 'landscape' else 1080/1920
        check('navigation preserves full asset and aspect ' + str(idx), all(abs(geometry(tab)['ratio']-expected) < .01 and geometry(tab)['url'].endswith(ident+'.png') for tab in [a,b]))
    a.js("window.__staleLoad = $('photoImg').onload; gotoPhoto('landscape', true)")
    a.wait("$('photoImg').complete && $('photoImg').naturalWidth === 1920")
    a.js('__staleLoad()')
    check('late image load cannot overwrite current geometry', abs(geometry(a)['ratio'] - 1920/1080) < .01)
    pump(a,b)

    # Navigation that races ahead of the offer must be replayed once metadata arrives.
    b.receive(dict(t='goto', id='late', seq=100, actor='remote'))
    b.receive(dict(t='offer', photos=[dict(id='late', mime='image/jpeg', w=1080,h=1920,bytes=10)]))
    check('navigation before offer is retained', b.js("state.current === 'late'"))
    a.js("gotoPhoto('portrait', true)"); pump(a,b)
    # Reconcile independently selected assets: Lamport sequence and actor order converge.
    a.js("gotoPhoto('portrait', true)"); b.js("gotoPhoto('landscape', true)"); pump(a,b)
    check('simultaneous selections converge', a.js('state.current') == b.js('state.current'))
    a.js("gotoPhoto('portrait',true)"); pump(a,b)
    a.wait("$('photoImg').complete && $('photoImg').naturalWidth === 1080")
    b.wait("$('photoImg').complete && $('photoImg').naturalWidth === 1080")

    # Real pointer capture and high-DPI canvas rendering, not just injected state.
    a.js("$('btnDraw').click()")
    rect = a.js("(() => {const r=$('drawCanvas').getBoundingClientRect(); return {x:r.x,y:r.y,w:r.width,h:r.height};})()")
    x, y, w, h = (rect[k] for k in ['x','y','w','h'])
    a.mouse('mousePressed', x+w*.25, y+h*.25)
    time.sleep(.07)
    a.mouse('mouseMoved', x+w*.75, y+h*.75)
    pump(a,b)
    check('live ink arrives before pointer release', b.js("Object.keys(state.photos.portrait.live).length === 1"))
    a.mouse('mouseReleased', x+w*.75, y+h*.75); pump(a,b)
    check('one completed stroke on both UEs', a.js('state.photos.portrait.strokes.length') == 1 == b.js('state.photos.portrait.strokes.length'))
    check('normalized stroke points are identical', a.js('state.photos.portrait.strokes[0].pts') == b.js('state.photos.portrait.strokes[0].pts'))
    check('no preview left after stroke commit', b.js('Object.keys(state.photos.portrait.live).length === 0'))
    for tab in [a,b]:
        check('stroke rendered at correct center on DPR ' + str(tab.js('devicePixelRatio')), tab.js("(() => { const c=$('drawCanvas'); return c.getContext('2d').getImageData(Math.floor(c.width*.5),Math.floor(c.height*.5),1,1).data[3]>0; })()"))
    a.js("gotoPhoto('landscape', true); gotoPhoto('portrait', true)"); pump(a,b)
    check('annotations survive navigation', a.js('state.photos.portrait.strokes.length') == b.js('state.photos.portrait.strokes.length') == 1)
    a.js("$('btnClearDraw').click()"); pump(a,b)
    check('clear synchronizes', a.js('state.photos.portrait.strokes.length') == b.js('state.photos.portrait.strokes.length') == 0)
    a.js("$('btnDraw').click()")
    check('pen can be enabled again after toggling off', a.js("$('drawBar').style.display === 'flex' && !$('btnDraw').disabled"))
    a.receive(dict(t='stroke',id='portrait',sid='bad',pts=[[2,-1]],color='#ff0000'))
    check('invalid drawing coordinates rejected', a.js('state.photos.portrait.strokes.length === 0'))
    a.js("$('btnPick').click()")
    check('picker uses native async path', a.js('__picked === true'))

    video = dict(id='video',mime='video/webm',w=160,h=90,bytes=100,url=origin+'/clip.webm',thumb='')
    a.js('window.onNativeMediaImported('+json.dumps([video])+')'); pump(a,b)
    b.receive(dict(video,t='stored')); pump(a,b)
    for tab in [a,b]:
        tab.call('Page.bringToFront')
        tab.js("$('sharedVideo').play().catch(e => window.__playError = String(e)); true")
        tab.wait("$('sharedVideo').readyState >= 1", timeout=15)
        check('video metadata loads on UE ' + str(tab.js('devicePixelRatio')), tab.js("$('sharedVideo').videoWidth > 0 && $('sharedVideo').muted && $('drawCanvas').style.display === 'none'"))
        tab.js("$('sharedVideo').currentTime = 0.2")
        tab.wait("$('sharedVideo').currentTime >= 0.19")
        check('video seeks', tab.js("$('sharedVideo').error === null"))
    a.js("gotoPhoto('portrait',true)"); pump(a,b)
    check('return from video restores photo quality', geometry(a)['url'].endswith('/portrait.png') and geometry(b)['url'].endswith('/portrait.png'))
    b.receive(dict(t='stored',id='landscape',mime='image/jpeg',url=origin+'/landscape.png',w=1920,h=1080))
    check('background completion cannot replace current photo', b.js("state.current === 'portrait' && $('loader').style.display === 'none'"))
    for index, tab in enumerate([a,b]):
        tab.wait("$('photoImg').complete")
        shot = tab.call('Page.captureScreenshot', {'format':'png'})
        (out / f'ue-{index+1}.png').write_bytes(base64.b64decode(shot['data']))
        check('no browser script errors UE ' + str(index+1), tab.js('__errors') == [])
        tab.js('window.cleanUpImsDcObjects()')
        check('call teardown clears media UE ' + str(index+1), tab.js("state.closed && state.order.length === 0 && !$('sharedVideo').getAttribute('src')"))
    check('server and bundled pages are identical', (ROOT/'go-mf/static/apps/photo-share/index.html').read_bytes() == (ROOT/'android-client/app/src/main/assets/miniapps/photo-share/index.html').read_bytes())
    print(f'{passed} assertions passed; screenshots: {out}', flush=True)


def main():
    browser = shutil.which('google-chrome') or shutil.which('chromium')
    if not browser:
        raise SystemExit('Chrome/Chromium is required')
    Handler.fixtures['/clip.webm'] = subprocess.check_output(['ffmpeg','-v','error','-f','lavfi','-i','color=c=blue:s=160x90:d=1','-an','-c:v','libvpx','-f','webm','pipe:1'])
    server = http.server.ThreadingHTTPServer(('127.0.0.1',0),functools.partial(Handler,directory=str(ROOT)))
    threading.Thread(target=server.serve_forever,daemon=True).start()
    origin = f'http://127.0.0.1:{server.server_port}'
    out = Path(tempfile.mkdtemp(prefix='photo-share-proof-'))
    with tempfile.TemporaryDirectory(prefix='photo-share-chrome-') as profile:
        chrome = subprocess.Popen([browser,'--headless','--no-sandbox','--disable-gpu','--remote-debugging-port=0',
            '--remote-allow-origins=*','--no-first-run','--no-default-browser-check','--autoplay-policy=no-user-gesture-required','--user-data-dir='+profile,'about:blank'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        tabs=[]
        try:
            port_file=Path(profile)/'DevToolsActivePort'
            deadline=time.monotonic()+15
            while not port_file.exists():
                if time.monotonic()>deadline: raise RuntimeError('Chrome did not start')
                time.sleep(.05)
            port=int(port_file.read_text().splitlines()[0])
            tabs=[Tab(port,390,650,3),Tab(port,430,720,2)]
            for tab in tabs: tab.navigate(origin+PAGE)
            run(*tabs,origin,out)
        finally:
            for tab in tabs: tab.close()
            chrome.terminate(); chrome.wait(timeout=10)
            server.shutdown();server.server_close()


if __name__=='__main__':
    main()
