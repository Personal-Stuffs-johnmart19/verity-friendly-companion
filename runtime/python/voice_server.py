"""Authenticated loopback adapter for optional Python audio engines only."""
from __future__ import annotations
import json,logging,re,secrets,threading,time,socket
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
from local_audio import CONFIG,ROOT,local_tts,decode_pcm_wav,transcribe_cpu,unload_cpu_stt,readiness,LOCK,read_settings,capabilities
from stt_runtime import SpeechRuntime
from speech_input import normalize_language

TOKEN=(ROOT/'.secrets/bridge-token').read_text(encoding='utf-8-sig').strip()
if not TOKEN or len(TOKEN)>1024:raise RuntimeError('Run server setup to prepare the private audio token')
STT_RUNTIME=SpeechRuntime(ROOT,CONFIG,on_unload=unload_cpu_stt)
logging.basicConfig(level=logging.INFO,format='%(asctime)s %(levelname)s %(message)s')

def transcribe(audio,language=None):
    decode_pcm_wav(audio)
    config=read_settings()
    selected=normalize_language(config.get('stt_language','auto') if language is None else language)
    request_config=dict(config,stt_language=selected)
    return STT_RUNTIME.transcribe(audio,lambda audio:transcribe_cpu(audio,request_config),language=selected,request_config=request_config)

def tts(text,language=None):
    config=read_settings()
    provider=config.get('provider','disabled')
    allowed=provider=='silero' or (provider=='google' and config.get('local_fallback',False) and config.get('local_fallback_provider','silero')=='silero')
    if not allowed:raise ValueError('No optional Python TTS engine selected')
    text=re.sub(r'https?://\S+','',text);text=re.sub(r'[`*_#]','',text).strip()
    if not text or len(text)>6000:raise ValueError('Invalid local speech text')
    selected=normalize_language(config.get('tts_language','auto') if language is None else language)
    if selected not in ('auto','ru','en'):raise ValueError('Prepared Silero voices support Russian and English only')
    if not LOCK.acquire(timeout=15):raise RuntimeError('Local speech is busy')
    try:return local_tts(text,selected,config)
    finally:LOCK.release()

def validate_payload(path,payload):
    if not isinstance(payload,dict):raise ValueError('Expected JSON object')
    if path=='/tts':
        if not isinstance(payload.get('text'),str) or not payload['text'].strip() or len(payload['text'])>6000:raise ValueError('Invalid local speech text')
        for field in ('emotion','voice'):
            if field in payload and (not isinstance(payload[field],str) or len(payload[field])>64):raise ValueError('Invalid speech option')
        if 'language' in payload:payload['language']=normalize_language(payload['language'])
    elif path=='/stt-session':
        import uuid
        session=payload.get('session')
        if not isinstance(session,str) or str(uuid.UUID(session))!=session:raise ValueError('Invalid speech session')
        if type(payload.get('players')) is not int or not 0<=payload['players']<=1024:raise ValueError('Invalid player count')
        if type(payload.get('revision')) is not int or not 0<=payload['revision']<=2**63-1:raise ValueError('Invalid session revision')
    return payload

class Handler(BaseHTTPRequestHandler):
    def setup(self):
        super().setup();self.connection.settimeout(15)
    def log_message(self,*args):pass
    def send(self,status,data,content='application/json'):
        self.send_response(status);self.send_header('Content-Type',content);self.send_header('Content-Length',str(len(data)));self.end_headers()
        try:self.wfile.write(data)
        except (BrokenPipeError,ConnectionResetError):pass
    def status(self):
        config=read_settings();issues=readiness(config)
        acquired=STT_RUNTIME.lock.acquire(blocking=False)
        if acquired:STT_RUNTIME.lock.release()
        voice_idle=LOCK.acquire(blocking=False)
        if voice_idle:LOCK.release()
        return json.dumps({'ready':True,'runtime':'python-audio-only','dependencies_ready':not issues,'dependency_issues':issues,'setup_issues':[],'busy':not acquired or not voice_idle,**capabilities(config)}).encode()
    def do_GET(self):
        if self.path=='/health':self.send(200,self.status())
        else:self.send(404,b'{}')
    def do_POST(self):
        if not secrets.compare_digest(self.headers.get('Authorization','').encode(),('Bearer '+TOKEN).encode()):self.send(401,b'{"error":"unauthorized"}');return
        if self.path not in ('/audio/status','/tts','/stt','/stt-session'):self.send(404,b'{}');return
        try:
            lengths=self.headers.get_all('Content-Length',[])
            if len(lengths)!=1 or self.headers.get('Transfer-Encoding') or not re.fullmatch(r'[0-9]{1,9}',lengths[0]):raise ValueError('Invalid request framing')
            size=int(lengths[0]);maximum=12_000_000 if self.path=='/stt' else 65536
            if not 0<size<=maximum:self.send(413,b'{"error":"request too large"}');return
            def abort_body():
                try:self.connection.shutdown(socket.SHUT_RDWR)
                except OSError:pass
            deadline=threading.Timer(15,abort_body);deadline.daemon=True;deadline.start()
            try:body=self.rfile.read(size)
            finally:deadline.cancel()
            if len(body)!=size:raise ValueError('Incomplete request body')
            payload=None if self.path=='/stt' else validate_payload(self.path,json.loads(body))
            if self.path=='/audio/status':self.send(200,self.status())
            elif self.path=='/tts':self.send(200,tts(payload['text'],payload.get('language')),'audio/wav')
            elif self.path=='/stt':
                languages=self.headers.get_all('X-Verity-Language',[])
                if len(languages)>1:raise ValueError('Duplicate recognition language')
                self.send(200,json.dumps(transcribe(body,languages[0] if languages else None),ensure_ascii=False).encode())
            else:self.send(200,json.dumps(STT_RUNTIME.session_update(payload['session'],payload['players'],payload['revision'])).encode())
        except (ValueError,KeyError,TypeError,UnicodeDecodeError):self.send(400,b'{"error":"invalid request"}')
        except TimeoutError:self.close_connection=True;self.send(408,b'{"error":"request timed out"}')
        except Exception as error:
            logging.error('Optional audio failed: %s',type(error).__name__);self.send(503,b'{"error":"optional local audio unavailable"}')

def expire_stt():
    while True:time.sleep(15);STT_RUNTIME.expire()

if __name__=='__main__':
    port=CONFIG.get('port',8766)
    if type(port) is not int or not 1024<=port<=65535:raise ValueError('Invalid private audio port')
    threading.Thread(target=expire_stt,daemon=True).start()
    try:ThreadingHTTPServer(('127.0.0.1',port),Handler).serve_forever()
    finally:STT_RUNTIME.close()
