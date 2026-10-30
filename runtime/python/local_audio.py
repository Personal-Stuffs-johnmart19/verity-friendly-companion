"""Python-specific local engines only. No cloud clients, keys, chat or web tools."""
from pathlib import Path
import io,json,re,threading,wave,importlib.util,unicodedata
from speech_input import model_directory

ROOT=Path(__file__).resolve().parent
DEFAULT={'provider':'disabled','stt_engine':'disabled','threads':4,'whisper_model':'small','russian_model':'v5_ru','russian_speaker':'eugene','english_model':'v3_en','english_speaker':'en_0'}
_settings=json.loads((ROOT/'voice-config.json').read_text(encoding='utf-8-sig'))
if 'stt_engine' not in _settings:
    _server=ROOT.parent/'config/verity-companion-server.json'
    if _server.is_file() and json.loads(_server.read_text(encoding='utf-8-sig')).get('runtime','bridge')=='bridge':_settings['stt_engine']='faster-whisper'
CONFIG=DEFAULT|_settings
def read_settings():
    with (ROOT/'voice-config.json').open('rb') as source:
        raw=source.read(65537)
    if len(raw)>65536:raise ValueError('Audio settings exceed size limit')
    settings=json.loads(raw.decode('utf-8-sig'))
    if not isinstance(settings,dict):raise ValueError('Invalid audio settings')
    config=DEFAULT|settings
    if 'stt_engine' not in settings:config['stt_engine']=CONFIG.get('stt_engine','disabled')
    if config.get('stt_engine')=='cpu':config['stt_engine']='faster-whisper'
    if config.get('stt_engine') not in ('disabled','google','faster-whisper','cuda','rocm','vulkan','native-cuda','native-cpu'):raise ValueError('Invalid recognition backend')
    if config.get('provider') not in ('disabled','google','silero','drama3'):raise ValueError('Invalid speech provider')
    return config
MODELS={};WHISPER=None
LOCK=threading.RLock();STT_LOCK=threading.RLock()
MAX_AUDIO=6_000_000

def wav_bytes(pcm,rate):
    if not pcm or len(pcm)>MAX_AUDIO or len(pcm)%2:raise ValueError('Invalid complete local PCM')
    result=io.BytesIO()
    with wave.open(result,'wb') as output:
        output.setnchannels(1);output.setsampwidth(2);output.setframerate(rate);output.writeframes(pcm)
    return result.getvalue()

def local_model(lang:str):
    import torch
    if lang not in MODELS:
        model_id=CONFIG['russian_model'] if lang=='ru' else CONFIG['english_model']
        target=ROOT/'models'/f'{model_id}.pt'
        if not target.is_file():raise RuntimeError('Prepared Silero weights missing; run server setup before speech')
        torch.set_num_threads(CONFIG['threads'])
        MODELS[lang]=torch.package.PackageImporter(str(target)).load_pickle('tts_models','model')
        MODELS[lang].to('cpu')
    return MODELS[lang]

def local_tts(text:str,language=None,config=None)->bytes:
    from speech_input import normalize_language
    config=CONFIG if config is None else config
    lang=normalize_language(config.get('tts_language','auto') if language is None else language)
    if lang=='auto':
        if any(c.isalpha() and not (unicodedata.name(c,'').startswith('LATIN ') or 'А'<=c<='я' or c in 'Ёё') for c in text):
            raise ValueError('Automatic local TTS cannot use an English voice for this script; select a compatible provider/language')
        lang='ru' if re.search('[А-Яа-яЁё]',text) else 'en'
    if lang not in ('ru','en'):raise ValueError('Prepared Silero voices support Russian and English only')
    import torch,numpy as np
    speaker=CONFIG['russian_speaker'] if lang=='ru' else CONFIG['english_speaker']
    chunks=[p.strip() for p in re.split(r'(?<=[.!?])\s+',text) if p.strip()]
    pieces=[]
    with torch.inference_mode():
        model=local_model(lang)
        for chunk in chunks:
            audio=model.apply_tts(text=chunk,speaker=speaker,sample_rate=24000)
            pieces.append((audio.clamp(-1,1).numpy()*32767).astype('<i2').tobytes())
            if sum(map(len,pieces))>MAX_AUDIO:raise ValueError('Complete local speech exceeds playback budget')
    return wav_bytes(b''.join(pieces),24000)

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

def transcribe_cpu(audio:bytes,config=None)->dict:
    global WHISPER
    # Validate before importing/loading speech models or considering a download.
    pcm,channels,rate=decode_pcm_wav(audio)
    config=CONFIG if config is None else config
    with STT_LOCK:
        from faster_whisper import WhisperModel
        if WHISPER is None:
            whisper_path=model_directory(config)
            if not whisper_path.is_dir() or not (whisper_path/'model.bin').is_file():
                raise RuntimeError('Prepared local Whisper model missing; run setup before using recognition')
            WHISPER=WhisperModel(str(whisper_path),device='cpu',compute_type='int8',cpu_threads=config['threads'],local_files_only=True)
        # Minecraft sends PCM WAV. Decode explicitly to avoid PyAV version-specific APIs.
        import numpy as np
        from scipy.signal import resample_poly
        from math import gcd
        samples=np.frombuffer(pcm,dtype='<i2').astype(np.float32)/32768
        if channels>1:samples=samples.reshape(-1,channels).mean(axis=1)
        if rate!=16000:
            divisor=gcd(rate,16000);samples=resample_poly(samples,16000//divisor,rate//divisor).astype(np.float32)
        from speech_input import decode_options, accepted_text
        segments,info=WHISPER.transcribe(samples,**decode_options(config))
        text=accepted_text(segments)
        return {'text':text,'language':info.language}

def unload_cpu_stt():
    global WHISPER
    with STT_LOCK:
        WHISPER=None



def readiness(config=None):
    config=CONFIG if config is None else config
    issues=[]
    engine=config.get('stt_engine','disabled')
    if engine in ('faster-whisper','cpu','cuda'):
        for module in ('numpy','scipy','faster_whisper'):
            if importlib.util.find_spec(module) is None:issues.append('Prepared local recognition packages missing; run server setup.')
        directory=model_directory(config)
        if any(not (directory/name).is_file() for name in ('model.bin','config.json','tokenizer.json')):issues.append('Prepared Faster Whisper files missing; run server setup.')
    if engine=='rocm':
        if not (ROOT/config.get('stt_rocm_python','missing')).is_file() or not (ROOT/config.get('stt_rocm_model','missing')/'model.safetensors').is_file():
            issues.append('Prepared ROCm worker/model missing; run server setup.')
    silero=config.get('provider')=='silero' or (config.get('provider')=='google' and config.get('local_fallback',False) and config.get('local_fallback_provider','silero')=='silero')
    if silero:
        if importlib.util.find_spec('torch') is None:issues.append('Silero Torch dependencies missing; run server setup.')
        for key,default in (('russian_model','v5_ru'),('english_model','v3_en')):
            if not (ROOT/'models'/(config.get(key,default)+'.pt')).is_file():issues.append('Prepared Silero voice weights missing; run server setup.')
    return issues

def capabilities(config):
    """Prepared assets and installed modules only; never import/load a model or allocate a GPU."""
    def installed(name):
        try:return importlib.util.find_spec(name) is not None
        except (ImportError,ValueError):return False
    stt=[];tts=[]
    directory=model_directory(config)
    whisper=all(installed(name) for name in ('numpy','scipy','faster_whisper','ctranslate2')) and all((directory/name).is_file() for name in ('model.bin','config.json','tokenizer.json'))
    if whisper:
        stt.append('faster-whisper')
        # Do not mistake CPU wheels for a prepared NVIDIA profile. Driver/library
        # validation is performed by the selected worker at use, not by loading it here.
        if config.get('stt_cuda_compute_type') and any((Path(base)/'nvidia'/package/leaf).is_dir() for base in __import__('sys').path for package in ('cublas',) for leaf in ('bin','lib')):stt.append('cuda')
    if (ROOT/config.get('stt_rocm_python','missing')).is_file() and all((ROOT/config.get('stt_rocm_model','missing')/name).is_file() for name in ('model.safetensors','config.json','preprocessor_config.json')):stt.append('rocm')
    if installed('torch') and installed('numpy') and all((ROOT/'models'/(config.get(key,default)+'.pt')).is_file() for key,default in (('russian_model','v5_ru'),('english_model','v3_en'))):tts.append('silero')
    return {'stt_backends':stt,'tts_providers':tts}
