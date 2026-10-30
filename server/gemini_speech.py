"""Server-only Gemini TTS. Fixed free-tier model allowlist; no paid fallback.

Billing status belongs to the project and must be confirmed by its owner.
This module cannot prevent charges if billing is subsequently enabled externally.
"""
import base64
import hashlib
import datetime as dt
import io
import json
import logging
import math
import threading
import time
import socket
import urllib.error
import urllib.request
import wave
from pathlib import Path

MODELS = ('gemini-3.8-flash-tts', 'gemini-3.8-flash-lite-tts',
          'gemini-3.1-flash-tts-preview', 'gemini-2.5-flash-preview-tts')

VOICES = frozenset('Zephyr Puck Charon Kore Fenrir Leda Orus Aoede Callirrhoe Autonoe Enceladus Iapetus Umbriel Algieba Despina Erinome Algenib Rasalgethi Laomedeia Achernar Alnilam Schedar Gacrux Pulcherrima Achird Zubenelgenubi Vindemiatrix Sadachbia Sadaltager Sulafat'.split())

STYLES = {'neutral':'', 'happy':'warm, cheerful and friendly',
          'excited':'enthusiastic, lively, naturally excited',
          'curious':'curious, interested, conversational',
          'reassuring':'calm, warm and reassuring',
          'sad':'gently sad and empathetic, conversational',
          'serious':'serious, composed and clear',
          'annoyed':'mildly annoyed, restrained, no shouting',
          'amused':'lightly amused, smiling conversational delivery',
          'laughing':'laughing with warm natural amusement, keep words intelligible',
          'mischievous':'playfully mischievous, a teasing smile, no sinister effects',
          'smug':'playfully self-satisfied, understated confidence',
          'deadpan':'dry deadpan humor, even understated delivery',
          'sarcastic':'gentle dry irony, lightly teasing, no cruelty',
          'embarrassed':'slightly embarrassed, hesitant but clear',
          'surprised':'naturally surprised, brief bright emphasis',
          'confused':'mildly puzzled, thoughtful questioning intonation',
          'worried':'concerned and attentive, steady clear speech',
          'afraid':'slightly apprehensive, restrained, no screaming',
          'disgusted':'mild distaste, restrained conversational delivery',
          'disappointed':'gently disappointed, subdued and conversational',
          'tired':'slightly weary, relaxed but intelligible',
          'proud':'warmly proud, confident and pleased',
          'affectionate':'warm, tender and caring, conversational',
          'thoughtful':'reflective and considered, natural pauses',
          'skeptical':'mildly doubtful, dry questioning intonation',
          'angry':'restrained frustration, firm and clear, no shouting'}

# Older TTS models receive only the established eight delivery instructions.
# These are fixed presentation mappings, never text supplied by the caller.
LEGACY_EMOTIONS = {
    'neutral':'neutral','happy':'happy','excited':'excited','curious':'curious',
    'reassuring':'reassuring','sad':'sad','serious':'serious','annoyed':'annoyed',
    'amused':'happy','laughing':'excited','mischievous':'happy','smug':'happy',
    'deadpan':'serious','sarcastic':'serious','embarrassed':'sad','surprised':'excited',
    'confused':'curious','worried':'serious','afraid':'serious','disgusted':'annoyed',
    'disappointed':'sad','tired':'neutral','proud':'happy','affectionate':'reassuring',
    'thoughtful':'curious','skeptical':'curious','angry':'annoyed'}

def normalize_emotion(value):
    if not isinstance(value,str) or value not in STYLES:
        raise ValueError('Unsupported voice emotion')
    return value


def cache_digest(config, text, emotion):
    normalize_emotion(emotion)
    # Preserve approved neutral cache, isolate other delivery variants structurally.
    keyed=config if emotion=='neutral' else config|{'_voice_emotion':emotion}
    return hashlib.sha256(('exact-text-v2'+json.dumps(keyed,sort_keys=True)+text).encode()).hexdigest()


def pacific_day(now):
    # US DST boundaries expressed in UTC, independent of Windows tzdata packages.
    utc = dt.datetime.fromtimestamp(now, dt.timezone.utc)
    year = utc.year
    march = dt.datetime(year, 3, 1, 10, tzinfo=dt.timezone.utc)
    start = march + dt.timedelta(days=(6-march.weekday()) % 7+7)
    november = dt.datetime(year, 11, 1, 9, tzinfo=dt.timezone.utc)
    end = november + dt.timedelta(days=(6-november.weekday()) % 7)
    return (utc-dt.timedelta(hours=7 if start <= utc < end else 8)).date().isoformat()


class QuotaError(RuntimeError):
    def __init__(self, daily=False, retry=62):
        super().__init__('Gemini TTS daily quota' if daily else 'Gemini TTS minute quota')
        self.daily, self.retry = daily, retry


class TransientError(RuntimeError):
    pass


def quota_error(payload, retry_after=None):
    error = payload.get('error', {}) if isinstance(payload, dict) else {}
    if not isinstance(error, dict):
        error = {}
    details = error.get('details', [])
    details = [d for d in details if isinstance(d, dict)] if isinstance(details, list) else []
    identifiers = [str(v.get('quotaId', '')) for d in details
                   for v in (d.get('violations') if isinstance(d.get('violations'), list) else [])
                   if isinstance(v, dict)]
    label = ' '.join(identifiers)+str(error.get('type', ''))+str(error.get('message', ''))
    label = label.lower()
    daily = any(word in label for word in ('perday', 'per day', 'daily'))
    if not daily and not any(word in label for word in ('perminute', 'per minute', 'rate_limit')):
        daily = 'quota_exceeded' in label
    delay = 62.0
    for value in [retry_after]+[d.get('retryDelay') for d in details]:
        try:
            seconds = float(str(value).rstrip('s'))
            if math.isfinite(seconds):
                delay = max(delay, seconds+1)
        except (TypeError, ValueError):
            pass
    return QuotaError(daily, min(delay, 3600))


class ModelUnavailableError(RuntimeError):
    pass


def request_audio(model, voice, text, key, emotion="neutral", timeout=90):
    emotion=normalize_emotion(emotion)
    style=STYLES[emotion if model.startswith('gemini-3.8-') else LEGACY_EMOTIONS[emotion]]
    if model not in MODELS or voice not in VOICES:
        raise ValueError('Unapproved TTS model or voice')
    if model.startswith('gemini-3.8-'):
        endpoint = 'interactions'
        body = {'model': model, 'input': [{'type': 'user_input', 'content': [
            {'type': 'text', 'text': text, 'annotations':[{'type':'speech_metadata','style':style}]}]}], 'response_format': {'type': 'audio'},
            'generation_config': {'speech_config': [{'voice': voice}]}}
    else:
        endpoint = 'models/'+model+':generateContent'
        spoken = ('Synthesize the spoken transcript with this delivery: '+style+'.\nSpoken transcript:\n'+text) if style else text
        body = {'contents': [{'parts': [{'text': spoken}]}], 'generationConfig': {
            'responseModalities': ['AUDIO'], 'speechConfig': {'voiceConfig': {
                'prebuiltVoiceConfig': {'voiceName': voice}}}}}
    request = urllib.request.Request('https://generativelanguage.googleapis.com/v1beta/'+endpoint,
        data=json.dumps(body).encode(), headers={'x-goog-api-key': key, 'Content-Type': 'application/json'})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(request, timeout=min(90, timeout)) as response:
            raw = response.read(9_000_001)
            if len(raw) > 9_000_000:
                raise ValueError('Oversize TTS response')
            result = json.loads(raw)
    except urllib.error.HTTPError as exc:
        try:
            try:
                payload = json.loads(exc.read(65536))
            except (ValueError, UnicodeError):
                payload = {}
            if exc.code == 429:
                raise quota_error(payload, exc.headers.get('Retry-After')) from None
            if exc.code in (408, 500, 502, 503, 504):
                raise TransientError('Temporary Gemini TTS HTTP '+str(exc.code)) from None
            # Only an explicit model-not-found/unsupported diagnostic permits rotation.
            # Generic 400/403/404 can also mean credentials/project/access failure.
            error = payload.get('error', {}) if isinstance(payload, dict) else {}
            message = str(error.get('message', '')).lower() if isinstance(error, dict) else ''
            if exc.code in (400, 404) and 'model' in message and any(
                    marker in message for marker in ('not found', 'not supported', 'unsupported', 'does not exist')):
                raise ModelUnavailableError('Configured Gemini TTS model unavailable') from None
            raise RuntimeError('Gemini TTS HTTP '+str(exc.code)) from None
        finally:
            exc.close()
    except (urllib.error.URLError, TimeoutError, OSError):
        raise TransientError('Temporary Gemini TTS network failure') from None
    if endpoint == 'interactions':
        parts = [p for s in result.get('steps', []) if s.get('type') == 'model_output'
                 for p in s.get('content', []) if p.get('type') == 'audio']
        if not parts:
            raise RuntimeError('Gemini TTS returned no audio')
        if len(parts) != 1:
            raise ValueError('Ambiguous multipart Gemini audio; complete speech required')
        audio = base64.b64decode(parts[-1]['data'], validate=True)
    else:
        parts = [p['inlineData'] for c in result.get('candidates', [])
                 for p in c.get('content', {}).get('parts', []) if 'inlineData' in p]
        if not parts:
            raise RuntimeError('Gemini TTS returned no audio')
        if len(parts) != 1:
            raise ValueError('Ambiguous multipart Gemini audio; complete speech required')
        pcm = base64.b64decode(parts[-1]['data'], validate=True)
        mime=parts[-1].get('mimeType','').lower()
        fields=[part.strip() for part in mime.split(';')]
        params=dict(part.split('=',1) for part in fields[1:] if '=' in part)
        if fields[0] not in ('audio/l16','audio/pcm') or params.get('codec','pcm')!='pcm' or params.get('rate')!='24000' or params.get('channels','1')!='1':
            raise ValueError('Unexpected Gemini PCM format: '+mime[:100])
        if len(pcm)%2:raise ValueError('Invalid Gemini PCM sample length')
        out = io.BytesIO()
        with wave.open(out, 'wb') as wav:
            wav.setparams((1, 2, 24000, 0, 'NONE', 'not compressed'))
            wav.writeframes(pcm)
        audio = out.getvalue()
    with wave.open(io.BytesIO(audio), 'rb') as wav:
        if (wav.getnchannels(), wav.getsampwidth()) != (1, 2) or not 8000 <= wav.getframerate() <= 48000:
            raise ValueError('Invalid Gemini WAV format')
        if not 0 < wav.getnframes()*2 <= 6_000_000:
            raise ValueError('Invalid Gemini WAV length')
        if len(wav.readframes(wav.getnframes())) != wav.getnframes()*2:
            raise ValueError('Truncated Gemini WAV')
    return audio


class SpeechQueue:
    def __init__(self, state_path, key_path, clock=time.time, sleep=time.sleep, transport=request_audio):
        self.path, self.key_path = Path(state_path), Path(key_path)
        self.clock, self.sleep, self.transport = clock, sleep, transport
        self.timer = time.monotonic if clock is time.time else clock
        self.lock = threading.Lock()
        try:
            self.state = json.loads(self.path.read_text(encoding='utf-8')) if self.path.exists() else {}
            if not isinstance(self.state, dict):
                raise ValueError('Invalid quota state')
            for model, slot in self.state.items():
                if not isinstance(slot, dict):
                    raise ValueError('Invalid quota slot')
                dt.date.fromisoformat(slot['day'])
                if type(slot.get('attempts')) is not int or not 0 <= slot['attempts'] <= 10:
                    raise ValueError('Invalid quota attempts')
                next_time = slot.get('next', 0)
                if type(next_time) not in (int, float) or not math.isfinite(next_time) or next_time < 0:
                    raise ValueError('Invalid quota cooldown')
                if 'daily_blocked' in slot and type(slot['daily_blocked']) is not bool:
                    raise ValueError('Invalid quota block')
        except (ValueError, TypeError, KeyError, OSError):
            # Never reset damaged counters: that could bypass the daily free-tier cap.
            raise RuntimeError('Gemini TTS quota state invalid; speech disabled until repaired') from None

    def persist(self):
        temporary = self.path.with_suffix('.tmp')
        temporary.write_text(json.dumps(self.state), encoding='utf-8')
        temporary.replace(self.path)

    def generate(self, text, config, cancelled=None):
        if config.get('google_free_project_confirmed') is not True:
            raise RuntimeError('Confirm a Free Tier project without billing before TTS')
        models = config.get('google_tts_models', list(MODELS))
        if not isinstance(models, (list, tuple)) or not models or any(model not in MODELS for model in models):
            raise ValueError('Only approved free-tier TTS models may be selected')
        if config.get('google_voice', 'Achird') not in VOICES:
            raise ValueError('Unapproved TTS voice')
        normalize_emotion(config.get('_emotion', 'neutral'))
        deadline = self.timer()+180
        def remaining():
            if cancelled and cancelled():
                raise RuntimeError('Gemini TTS request cancelled')
            budget = deadline-self.timer()
            if budget <= 0:
                raise RuntimeError('Gemini TTS response deadline exceeded; text reply remains available')
            return budget
        # Deadline includes time spent waiting for other speech requests.
        while not self.lock.acquire(timeout=min(0.2, remaining())):
            pass
        try:
            remaining()
            for model in models:
                for attempt in range(3):
                    now = self.clock()
                    day = pacific_day(now)
                    slot = self.state.setdefault(model, {})
                    if slot.get('day') != day:
                        slot.clear(); slot.update(day=day, attempts=0, next=0)
                    if slot['attempts'] >= 10 or slot.get('daily_blocked'):
                        logging.info('TTS daily_limit model=%s; trying next free model', model)
                        break
                    wait = max(0, slot.get('next', 0)-now)
                    if wait >= remaining():
                        raise RuntimeError('Gemini TTS queue deadline exceeded; text reply remains available')
                    if wait:
                        logging.info('TTS queued model=%s wait_seconds=%.1f', model, wait)
                        if cancelled:
                            until = self.clock()+wait
                            while self.clock()<until:
                                self.sleep(min(0.2, until-self.clock(), remaining()))
                        else:
                            self.sleep(wait)
                        # Midnight may have passed while waiting.
                        if pacific_day(self.clock()) != day:
                            slot.clear(); slot.update(day=pacific_day(self.clock()), attempts=0, next=0)
                    remaining()
                    slot['attempts'] += 1
                    slot['next'] = self.clock()+22  # <= 3 starts in any 60-second window.
                    self.persist()  # Reserve before the request; persists across bridge restarts.
                    try:
                        args = (model, config.get('google_voice', 'Achird'), text,
                                self.key_path.read_text().strip(), config.get('_emotion','neutral'))
                        audio = (self.transport(*args, timeout=remaining()) if self.transport is request_audio
                                 else self.transport(*args))
                        remaining()
                        logging.info('TTS generated model=%s voice=%s', model, config.get('google_voice','Achird'))
                        return audio
                    except ModelUnavailableError:
                        logging.info('TTS model_unavailable model=%s; trying next approved model', model)
                        break
                    except QuotaError as exc:
                        if exc.daily:
                            slot['daily_blocked'] = True
                            self.persist()
                            break
                        slot['next'] = max(slot['next'], self.clock()+exc.retry)
                        self.persist()
                        logging.info('TTS minute_limit model=%s retry_seconds=%.1f', model, exc.retry)
                        if attempt == 2:
                            raise RuntimeError('Gemini TTS minute quota remains unavailable') from None
                    except TransientError:
                        if attempt == 2:
                            raise RuntimeError('Gemini TTS temporarily unavailable after three attempts') from None
                        slot['next'] = max(slot['next'], self.clock()+30*(attempt+1))
                        self.persist()
                        logging.info('TTS transient_failure model=%s; queued retry', model)
            raise RuntimeError('All configured free TTS daily quotas exhausted or models unavailable; text reply remains available')
        finally:
            self.lock.release()
