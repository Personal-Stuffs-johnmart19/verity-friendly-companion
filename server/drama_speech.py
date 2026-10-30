"""Loopback-only Drama 3 SSE reader. No credentials or cloud requests."""
import base64
import io
import json
import re
import urllib.request
import wave
from pathlib import Path

MAX_PCM = 6_000_000
RATE = 44100


def wav_bytes(pcm):
    if not isinstance(pcm, bytes) or not pcm or len(pcm)%2 or len(pcm)>MAX_PCM:
        raise ValueError('Invalid PCM16 audio')
    out = io.BytesIO()
    with wave.open(out, 'wb') as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(RATE)
        wav.writeframes(pcm)
    return out.getvalue()


def completed_wav_chunks(audio):
    """Packetize completed audio without resampling, dropping or changing samples."""
    with wave.open(io.BytesIO(audio), 'rb') as source:
        params=source.getparams()
        if params.nchannels!=1 or params.sampwidth!=2 or not 8000<=params.framerate<=48000:
            raise ValueError('Unsupported completed voice format')
        expected=params.nframes*2
        if not 0<expected<=MAX_PCM:
            raise ValueError('Invalid completed voice length')
        # Validate all frames before publishing the first packet.
        complete=source.readframes(params.nframes)
        if len(complete)!=expected:
            raise ValueError('Truncated completed voice audio')
        for offset in range(0,len(complete),32768):
            out=io.BytesIO()
            with wave.open(out,'wb') as target:
                target.setparams(params)
                target.writeframes(complete[offset:offset+32768])
            yield out.getvalue()


def read_sse(response):
    """Reject incomplete/error/oversized streams rather than caching partial speech."""
    total = 0
    done = False
    while True:
        line = response.readline(524289)
        if not line:
            break
        if len(line) > 524288:
            raise ValueError('Oversized native voice event')
        if not line.startswith(b'data:'):
            continue
        data = line[5:].strip()
        if data == b'[DONE]':
            break
        event = json.loads(data)
        if not isinstance(event, dict):
            raise ValueError('Invalid native voice event')
        kind = event.get('type')
        if kind == 'speech.audio.delta':
            if done:
                raise ValueError('Audio received after completion')
            pcm = base64.b64decode(event['audio'], validate=True)
            if len(pcm) % 2:
                raise ValueError('Invalid PCM16 frame boundary')
            total += len(pcm)
            if total > MAX_PCM:
                raise ValueError('Voice response exceeds stream budget')
            if pcm:
                yield pcm
        elif kind == 'speech.audio.done':
            if done:
                raise ValueError('Duplicate native voice completion')
            done = True
        elif kind in {'error', 'speech.error'} or 'error' in event:
            raise RuntimeError('Native Drama 3 stream failed')
    if not done or not total:
        raise RuntimeError('Incomplete native Drama 3 stream')


def generate(text, directory):
    root = Path(directory)
    language = 'ru' if re.search('[А-Яа-яЁё]', text) else 'en'
    references = json.loads((root/'voices/reference-text.json').read_text(encoding='utf-8'))
    body = dict(model='verity-drama3', input=text,
                voice_ref=str((root/'voices'/f'verity-{language}.wav').resolve()),
                reference_text=references[language], response_format='pcm',
                stream_format='sse', max_tokens=1024, seed=1234,
                options=dict(temperature=0.8, top_p=0.9, top_k=4097))
    request = urllib.request.Request('http://127.0.0.1:8773/v1/audio/speech',
        data=json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
    # Explicit loopback connection: proxy environment settings cannot forward speech.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(request, timeout=120) as response:
        yield from read_sse(response)
