"""Loopback-only speech bridge: local Whisper, Google free-tier TTS models, local fallback."""
from __future__ import annotations
import hashlib, io, json, logging, os, re, secrets, struct, threading, time, urllib.request, wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT=Path(__file__).resolve().parent
os.environ.setdefault('HF_HOME',str(ROOT/'models/huggingface'))
os.environ.setdefault('HF_HUB_DISABLE_SYMLINKS_WARNING','1')
PRIVATE=ROOT/'.secrets';PRIVATE.mkdir(exist_ok=True)
TOKEN_FILE=PRIVATE/'bridge-token'
if not TOKEN_FILE.exists():TOKEN_FILE.write_text(secrets.token_urlsafe(32))
TOKEN=TOKEN_FILE.read_text().strip()
if not TOKEN:raise RuntimeError('Bridge token file is empty')
CONFIG_FILE=ROOT/'voice-config.json'
DEFAULT={'provider':'google','google_model':'gemini-3.8-flash-tts','google_voice':'Achird','local_fallback':False,'russian_model':'v5_ru','russian_speaker':'eugene','english_model':'v3_en','english_speaker':'en_0','whisper_model':'small','threads':4,'port':8766,'chat_model':'gemini-3.1-flash-lite','simple_chat_model':'gemini-3.1-flash-lite','search_model':'gemini-2.5-flash'}
if not CONFIG_FILE.exists():CONFIG_FILE.write_text(json.dumps(DEFAULT,indent=2))
CONFIG=DEFAULT|json.loads(CONFIG_FILE.read_text())
LOCK=threading.Lock();STT_LOCK=threading.Lock();MODELS={};WHISPER=None;GOOGLE_SPEECH=None
(ROOT/'models').mkdir(exist_ok=True);(ROOT/'cache').mkdir(exist_ok=True)
import lmstudio_bridge
LOCAL_MODELS=lmstudio_bridge.LocalModels(ROOT)
logging.basicConfig(level=logging.INFO,format='%(asctime)s %(levelname)s %(message)s')

def google_json(model:str,body:dict,max_seconds=85)->dict:
    import google_retry
    return google_retry.generate(model,body,(PRIVATE/'google-api-key').read_text().strip(),max_seconds=max_seconds)


def google_chat(request:dict)->dict:
    request=LOCAL_MODELS.snapshot(request)
    local=LOCAL_MODELS.chat(request)
    if local is not None:return local
    import cloud_chat
    return cloud_chat.generate(request,CONFIG,google_json)

def google_search(query:str,language:str='ru')->dict:
    if not isinstance(language,str) or language not in ('en','ru'):raise ValueError('Language must be en or ru')
    instruction=('Найди в Google ответ на вопрос о Minecraft/модах. Дай краткое фактическое резюме по-русски и ссылки на источники. Предпочитай официальную документацию модов. Различай версии. Запрос: ' if language=='ru' else 'Search Google for the following Minecraft/modding question. Give a concise factual English summary and source URLs. Prefer official mod documentation. Distinguish versions. Query: ')
    result=google_json(CONFIG['search_model'],{'contents':[{'parts':[{'text':instruction+query}]}],'tools':[{'google_search':{}}],'generationConfig':{'maxOutputTokens':2048}})
    candidate=result.get('candidates',[{}])[0];text=''.join(p.get('text','') for p in candidate.get('content',{}).get('parts',[]))
    metadata=candidate.get('groundingMetadata',{});sources=[c['web'] for c in metadata.get('groundingChunks',[]) if 'web' in c]
    if not sources:raise RuntimeError('Google search returned no grounding sources; refusing to label model memory as search')
    return {'summary':text,'sources':sources,'model':CONFIG['search_model'],'google_search_queries':metadata.get('webSearchQueries',[])}

def wav_bytes(pcm:bytes,rate:int)->bytes:
    out=io.BytesIO()
    with wave.open(out,'wb') as wav:wav.setnchannels(1);wav.setsampwidth(2);wav.setframerate(rate);wav.writeframes(pcm)
    return out.getvalue()

def google_tts(text:str,emotion="neutral",config=None)->bytes:
    config=CONFIG if config is None else config
    import gemini_speech
    global GOOGLE_SPEECH
    if GOOGLE_SPEECH is None:
        if not LOCK.acquire(timeout=5):raise RuntimeError('Speech initialization is busy; text reply remains available')
        try:
            if GOOGLE_SPEECH is None:
                GOOGLE_SPEECH=gemini_speech.SpeechQueue(ROOT/'cache/google-tts-quota.json',PRIVATE/'google-api-key')
        finally:LOCK.release()
    # SpeechQueue owns admission and its 180-second deadline, including lock waits.
    return GOOGLE_SPEECH.generate(text,config|{"_emotion":emotion})


def local_model(lang:str):
    import torch
    if lang not in MODELS:
        model_id=CONFIG['russian_model'] if lang=='ru' else CONFIG['english_model']
        target=ROOT/'models'/f'{model_id}.pt'
        if not target.exists():
            url=f'https://models.silero.ai/models/tts/{lang}/{model_id}.pt'
            tmp=target.with_suffix('.download');urllib.request.urlretrieve(url,tmp);tmp.replace(target)
        torch.set_num_threads(CONFIG['threads'])
        MODELS[lang]=torch.package.PackageImporter(str(target)).load_pickle('tts_models','model')
        MODELS[lang].to('cpu')
    return MODELS[lang]

def local_tts(text:str)->bytes:
    import torch,numpy as np
    # Select a native voice for the utterance language.
    lang='ru' if re.search('[А-Яа-яЁё]',text) else 'en'
    speaker=CONFIG['russian_speaker'] if lang=='ru' else CONFIG['english_speaker']
    chunks=[p.strip() for p in re.split(r'(?<=[.!?])\s+',text) if p.strip()]
    pieces=[]
    with torch.inference_mode():
        model=local_model(lang)
        for chunk in chunks:
            audio=model.apply_tts(text=chunk,speaker=speaker,sample_rate=24000)
            pieces.append((audio.clamp(-1,1).numpy()*32767).astype('<i2').tobytes())
    return wav_bytes(b''.join(pieces),24000)

def read_cached_audio(path):
    import drama_speech
    if not path.exists():return None
    try:
        if path.stat().st_size>6_100_000:raise ValueError('Oversized cache')
        audio=path.read_bytes()
        next(drama_speech.completed_wav_chunks(audio))
        return audio
    except (OSError,ValueError,EOFError,wave.Error,StopIteration):
        logging.warning('Ignoring invalid speech cache; using normal provider quota checks')
        return None

def save_cached_audio(path,audio):
    import drama_speech
    next(drama_speech.completed_wav_chunks(audio))
    temporary=path.with_name(path.name+'.'+secrets.token_hex(8)+'.tmp')
    created=False
    try:
        with temporary.open('xb') as target:
            created=True
            target.write(audio)
        temporary.replace(path)
    except OSError:
        # Cache storage is optional; a full disk must not discard validated audio.
        logging.warning('Unable to store speech cache')
    finally:
        if created:
            try:temporary.unlink(missing_ok=True)
            except OSError:pass

def tts(text:str,emotion="neutral",voice=None)->tuple[bytes,str]:
    import gemini_speech
    config=dict(CONFIG)
    if voice is not None:
        if voice not in gemini_speech.VOICES:raise ValueError("Unsupported TTS voice")
        config["google_voice"]=voice
    emotion=gemini_speech.normalize_emotion(emotion)
    text=re.sub(r'https?://\S+','',text);text=re.sub(r'[`*_#]','',text).strip()
    if not text:return wav_bytes(b'',24000),'empty'
    if len(text)>6000:raise ValueError('TTS text too long; split response into paragraphs')
    digest=gemini_speech.cache_digest(config,text,emotion);path=ROOT/'cache'/f'{digest}.wav'
    cached=read_cached_audio(path)
    if cached is not None:return cached,'cache'
    provider=config['provider']
    if provider=='google':
        try:
            audio=google_tts(text,emotion,config)
            save_cached_audio(path,audio)
            return audio,provider
        except Exception as exc:
            logging.warning('Google speech failed (%s); local fallback enabled=%s',type(exc).__name__,config['local_fallback'])
            if not config['local_fallback']:raise
            provider='silero'
    # Serialize local model use with a bounded admission wait. Cloud requests never
    # wait here before their own queue deadline starts.
    if not LOCK.acquire(timeout=15):raise RuntimeError('Local speech is busy; text reply remains available')
    try:
        cached=read_cached_audio(path)
        if cached is not None:return cached,'cache'
        if provider=='drama3':
            import drama_speech
            audio=drama_speech.wav_bytes(b''.join(drama_speech.generate(text,config.get('drama3_directory',str(ROOT.parent/'verity-tts')))))
        else:provider='silero';audio=local_tts(text)
        save_cached_audio(path,audio);return audio,provider
    finally:LOCK.release()

def stream_tts(text,emotion="neutral",voice=None):
    import gemini_speech
    config=dict(CONFIG)
    if voice is not None:
        if voice not in gemini_speech.VOICES:raise ValueError("Unsupported TTS voice")
        config["google_voice"]=voice
    emotion=gemini_speech.normalize_emotion(emotion)
    import drama_speech
    text=re.sub(r'https?://\S+','',text);text=re.sub(r'[`*_#]','',text).strip()
    if not text or len(text)>6000:raise ValueError('Invalid voice text length')
    if config['provider']!='drama3':
        yield from drama_speech.completed_wav_chunks(tts(text,emotion,voice)[0]);return
    if config.get('playback_mode','complete')=='complete':
        # Complete synthesis and validation before publishing the first frame.
        # A live SSE stream may run slower than playback and cause audible gaps.
        audio,provider=tts(text,emotion,voice)
        yield from drama_speech.completed_wav_chunks(audio)
        return
    digest=gemini_speech.cache_digest(config,text,emotion)
    path=ROOT/'cache'/f'{digest}.wav'
    if not LOCK.acquire(timeout=15):raise RuntimeError('Local speech is busy; text reply remains available')
    try:
        cached=read_cached_audio(path)
        if cached is not None:
            yield from drama_speech.completed_wav_chunks(cached)
            return
        pcm=[];pending=bytearray()
        for chunk in drama_speech.generate(text,config.get('drama3_directory',str(ROOT.parent/'verity-tts'))):
            pcm.append(chunk)
            pending.extend(chunk)
            if len(pending)>=13230:
                yield drama_speech.wav_bytes(bytes(pending));pending.clear()
        if pending:yield drama_speech.wav_bytes(bytes(pending))
        save_cached_audio(path,drama_speech.wav_bytes(b''.join(pcm)))
    finally:LOCK.release()

def decode_pcm_wav(audio):
    try:
        with wave.open(io.BytesIO(audio),'rb') as wav:
            channels=wav.getnchannels();rate=wav.getframerate();frames=wav.getnframes()
            if wav.getsampwidth()!=2 or channels not in (1,2) or not 8000<=rate<=96000 or not 0<frames<=rate*30:
                raise ValueError('Expected up to 30 seconds of PCM16 WAV')
            pcm=wav.readframes(frames)
            if len(pcm)!=frames*channels*2:raise ValueError('Truncated PCM WAV')
            return pcm,channels,rate
    except (wave.Error,EOFError):raise ValueError('Invalid PCM WAV') from None

def transcribe_cpu(audio:bytes)->dict:
    global WHISPER
    # Validate before importing/loading speech models or considering a download.
    pcm,channels,rate=decode_pcm_wav(audio)
    with STT_LOCK:
        from faster_whisper import WhisperModel
        if WHISPER is None:
            WHISPER=WhisperModel(CONFIG['whisper_model'],device='cpu',compute_type='int8',cpu_threads=CONFIG['threads'],download_root=str(ROOT/'models/whisper'))
        # Minecraft sends PCM WAV. Decode explicitly to avoid PyAV version-specific APIs.
        import numpy as np
        from scipy.signal import resample_poly
        from math import gcd
        samples=np.frombuffer(pcm,dtype='<i2').astype(np.float32)/32768
        if channels>1:samples=samples.reshape(-1,channels).mean(axis=1)
        if rate!=16000:
            divisor=gcd(rate,16000);samples=resample_poly(samples,16000//divisor,rate//divisor).astype(np.float32)
        from speech_input import decode_options, accepted_text
        segments,info=WHISPER.transcribe(samples,**decode_options(CONFIG))
        text=accepted_text(segments)
        return {'text':text,'language':info.language}

def unload_cpu_stt():
    global WHISPER
    with STT_LOCK:
        WHISPER=None


def transcribe(audio:bytes)->dict:
    decode_pcm_wav(audio)  # Invalid input must never initialize either model.
    return STT_RUNTIME.transcribe(audio,transcribe_cpu)


def expire_stt():
    while True:
        time.sleep(15)
        STT_RUNTIME.expire()


def validate_payload(path,payload):
    if not isinstance(payload,dict):raise ValueError('Expected JSON object')
    if path in ('/v1/chat/completions','/google-search') and 'language' in payload:
        if not isinstance(payload['language'],str) or payload['language'] not in ('en','ru'):raise ValueError('Invalid language')
    if path in ('/tts','/tts-stream','/google-search'):
        field='query' if path=='/google-search' else 'text'
        value=payload.get(field)
        if not isinstance(value,str) or not value.strip() or len(value)>6000:raise ValueError('Invalid text')
        for field in ('emotion','voice'):
            if field in payload and payload[field] is not None and not isinstance(payload[field],str):raise ValueError('Invalid voice option')
    elif path=='/stt-session':
        import uuid
        session=payload.get('session')
        if not isinstance(session,str) or str(uuid.UUID(session))!=session:raise ValueError('Invalid speech session')
        if type(payload.get('players')) is not int or not 0<=payload['players']<=1024:raise ValueError('Invalid player count')
        if type(payload.get('revision')) is not int or not 0<=payload['revision']<=2**63-1:raise ValueError('Invalid session revision')
    elif path=='/v1/chat/completions':
        messages=payload.get('messages')
        if not isinstance(messages,list) or not 1<=len(messages)<=256:raise ValueError('Invalid messages')
        for message in messages:
            if not isinstance(message,dict) or message.get('role') not in ('system','assistant','user'):raise ValueError('Invalid message')
            if not isinstance(message.get('content'),str) or len(message['content'])>200000:raise ValueError('Invalid message content')
        if 'max_tokens' in payload and (type(payload['max_tokens']) is not int or not 1<=payload['max_tokens']<=8192):raise ValueError('Invalid token limit')
        if 'temperature' in payload and (type(payload['temperature']) not in (int,float) or not 0<=payload['temperature']<=2):raise ValueError('Invalid temperature')
        fmt=payload.get('response_format',{})
        if not isinstance(fmt,dict):raise ValueError('Invalid response format')
        if fmt.get('type')=='json_schema':
            schema=fmt.get('json_schema')
            if not isinstance(schema,dict) or not isinstance(schema.get('schema'),dict):raise ValueError('Invalid response schema')
    elif path=='/lmstudio/select':
        if not isinstance(payload.get('model'),str) or not payload['model'].strip() or len(payload['model'])>256:raise ValueError('Invalid model key')
    elif path=='/lmstudio/settings':
        if payload.get('action') not in ('reasoning','context','temperature','max_tokens','reset','caps','system_prompt','prompt_show','prompt_clear'):raise ValueError('Invalid LLM setting')
        if 'value' in payload and not isinstance(payload['value'],(str,int,float)):raise ValueError('Invalid LLM setting value')
    elif path=='/lmstudio/status':
        job=payload.get('job_id')
        if job is not None and (not isinstance(job,str) or len(job)>128):raise ValueError('Invalid job ID')
    return payload

class Handler(BaseHTTPRequestHandler):
    def setup(self):
        super().setup()
        self.connection.settimeout(15)
    def log_message(self,fmt,*args):pass
    def send(self,status:int,data:bytes,content='application/json',provider=''):
        self.send_response(status);self.send_header('Content-Type',content);self.send_header('Content-Length',str(len(data)))
        if provider:self.send_header('X-Verity-Voice-Provider',provider)
        self.end_headers()
        try:self.wfile.write(data)
        except (BrokenPipeError,ConnectionResetError):pass
    def do_GET(self):
        if self.path=='/health':
            routing=LOCAL_MODELS.status()
            self.send(200,json.dumps({'ready':True,'provider':CONFIG['provider'],'google_model':CONFIG['google_model'],'voice':CONFIG['google_voice'],'playback_mode':CONFIG.get('playback_mode','complete'),'chat_provider':routing.get('provider'),'chat_model':routing.get('model') if routing.get('provider')=='local' else CONFIG['chat_model'],'stt':CONFIG.get('stt_engine','faster-whisper'),'microphone':'push-to-talk only; service does not open microphone'}).encode())
        else:self.send(404,b'{}')
    def do_POST(self):
        if not secrets.compare_digest(self.headers.get('Authorization','').encode(),('Bearer '+TOKEN).encode()):self.send(401,b'{"error":"unauthorized"}');return
        paths={'/lmstudio/settings','/lmstudio/models','/lmstudio/select','/lmstudio/status','/lmstudio/cloud','/tts-stream','/tts','/stt','/stt-session','/v1/chat/completions','/google-search'}
        if self.path not in paths:self.send(404,b'{}');return
        try:
            lengths=self.headers.get_all('Content-Length',[])
            if len(lengths)!=1 or self.headers.get('Transfer-Encoding') or not re.fullmatch(r'[0-9]{1,9}',lengths[0]):raise ValueError('Invalid request framing')
            size=int(lengths[0])
            maximum=12_000_000 if self.path=='/stt' else 1_000_000
            if size<1 or size>maximum:self.send(413,b'{"error":"request too large"}');return
            body=self.rfile.read(size)
            if len(body)!=size:raise ValueError('Incomplete request body')
            payload=None if self.path=='/stt' else validate_payload(self.path,json.loads(body))
            start=time.monotonic()
            if self.path=='/lmstudio/settings':
                try:result=LOCAL_MODELS.configure(payload['action'],payload.get('value'))
                except ValueError as error:result={'error':str(error)}
                self.send(200,json.dumps(result).encode())
            elif self.path=='/lmstudio/models':
                self.send(200,json.dumps({'models':LOCAL_MODELS.models(),'routing':LOCAL_MODELS.status()}).encode())
            elif self.path=='/lmstudio/select':
                self.send(200,json.dumps(LOCAL_MODELS.select(payload['model'])).encode())
            elif self.path=='/lmstudio/status':
                self.send(200,json.dumps(LOCAL_MODELS.status(payload.get('job_id'))).encode())
            elif self.path=='/lmstudio/cloud':
                self.send(200,json.dumps(LOCAL_MODELS.cloud()).encode())
            elif self.path=='/tts-stream':
                text=payload['text']
                if not isinstance(text,str) or not text.strip() or len(text)>6000:raise ValueError('Invalid voice text')
                self.send_response(200);self.send_header('Content-Type','application/x-verity-wav-stream');self.send_header('Connection','close');self.end_headers()
                self.close_connection=True
                first=True
                try:
                    for chunk in stream_tts(text,payload.get("emotion","neutral"),payload.get("voice")):
                        self.wfile.write(struct.pack('>I',len(chunk))+chunk);self.wfile.flush()
                        if first:logging.info('TTS first_audio_seconds=%.2f',time.monotonic()-start);first=False
                    self.wfile.write(b'\0\0\0\0');self.wfile.flush()
                    logging.info('TTS stream_complete_seconds=%.2f',time.monotonic()-start)
                except (BrokenPipeError,ConnectionResetError):pass
                except Exception as exc:logging.error('TTS stream failed: %s',type(exc).__name__)
                return
            elif self.path=='/tts':
                text=payload['text'];audio,provider=tts(text,payload.get('emotion','neutral'),payload.get('voice'));self.send(200,audio,'audio/wav',provider);logging.info('TTS provider=%s chars=%s seconds=%.2f',provider,len(text),time.monotonic()-start)
            elif self.path=='/stt-session':
                result=STT_RUNTIME.session_update(payload['session'],payload['players'],payload['revision']);self.send(200,json.dumps(result).encode())
            elif self.path=='/stt':
                result=transcribe(body);self.send(200,json.dumps(result,ensure_ascii=False).encode());logging.info('STT language=%s chars=%s seconds=%.2f',result['language'],len(result['text']),time.monotonic()-start)
            elif self.path=='/v1/chat/completions':
                result=google_chat(payload);self.send(200,json.dumps(result,ensure_ascii=False).encode())
            elif self.path=='/google-search':
                result=google_search(payload['query'],payload.get('language','ru'));self.send(200,json.dumps(result,ensure_ascii=False).encode())
            else:self.send(404,b'{}')
        except (ValueError,KeyError,TypeError,UnicodeDecodeError):
            self.send(400,b'{"error":"invalid request"}')
        except TimeoutError:
            self.close_connection=True
            self.send(408,b'{"error":"request timed out"}')
        except Exception as exc:
            logging.error('Speech request failed: %s',type(exc).__name__)
            self.send(503,b'{"error":"service unavailable; check bridge diagnostics"}')

from stt_runtime import SpeechRuntime
STT_RUNTIME=SpeechRuntime(ROOT,CONFIG,on_unload=unload_cpu_stt)

if __name__=='__main__':
    logging.info('Voice bridge starting on 127.0.0.1:%s; no microphone opened',CONFIG['port'])
    threading.Thread(target=expire_stt,daemon=True).start()
    try:ThreadingHTTPServer(('127.0.0.1',CONFIG['port']),Handler).serve_forever()
    finally:STT_RUNTIME.close()
