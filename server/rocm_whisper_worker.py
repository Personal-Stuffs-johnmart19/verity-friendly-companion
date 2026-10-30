"""Resident, local-only ROCm Whisper worker; stdout is UTF-8 JSON-lines IPC."""
import argparse
import base64
import binascii
import contextlib
import io
import json
import os
from pathlib import Path
import sys
import time
import wave

MAX_AUDIO = 12 * 1024 * 1024
MAX_LINE = ((MAX_AUDIO + 2) // 3) * 4 + 16384


def prepare_audio(pcm, channels, rate):
    import numpy as np
    from scipy.signal import resample_poly
    from math import gcd
    audio=np.frombuffer(pcm,dtype='<i2').astype(np.float32)/32768.0
    if channels==2:audio=audio.reshape(-1,2).mean(axis=1)
    if rate!=16000:
        divisor=gcd(rate,16000)
        audio=resample_poly(audio,16000//divisor,rate//divisor).astype(np.float32)
    return audio


def decode_request(request):
    if not isinstance(request, dict):
        raise ValueError('Invalid request')
    language = request.get('language', 'auto')
    beam = request.get('beam_size', 5)
    prompt = request.get('prompt', '')
    encoded = request.get('audio')
    if language not in ('auto', 'ru', 'en'):
        raise ValueError('Invalid language')
    if isinstance(beam, bool) or not isinstance(beam, int) or not 1 <= beam <= 8:
        raise ValueError('Invalid beam size')
    if not isinstance(prompt, str) or len(prompt) > 2048:
        raise ValueError('Invalid prompt')
    if not isinstance(encoded, str) or len(encoded) > ((MAX_AUDIO + 2) // 3) * 4:
        raise ValueError('Invalid audio')
    try:
        audio = base64.b64decode(encoded, validate=True)
        if not 0 < len(audio) <= MAX_AUDIO:
            raise ValueError('Invalid audio')
        with wave.open(io.BytesIO(audio), 'rb') as wav:
            channels, rate, frames = wav.getnchannels(), wav.getframerate(), wav.getnframes()
            if (wav.getcomptype() != 'NONE' or wav.getsampwidth() != 2
                    or channels not in (1, 2) or not 8000 <= rate <= 96000
                    or not 0 < frames <= rate * 30):
                raise ValueError('Invalid PCM WAV')
            pcm = wav.readframes(frames)
            if len(pcm) != frames * channels * 2:
                raise ValueError('Truncated PCM WAV')
    except (wave.Error, EOFError, binascii.Error, UnicodeError):
        raise ValueError('Invalid PCM WAV') from None
    return pcm, channels, rate, language, beam, prompt


def emit(value):
    line = json.dumps(value, ensure_ascii=False, separators=(',', ':'))
    if len(line.encode('utf-8')) > 32767:
        line = '{"error":"Recognition output exceeded limit"}'
    sys.__stdout__.write(line + '\n')
    sys.__stdout__.flush()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--model', required=True)
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding='utf-8')
    os.environ['HF_HUB_OFFLINE'] = '1'
    os.environ['TRANSFORMERS_OFFLINE'] = '1'
    os.environ['HF_HUB_DISABLE_TELEMETRY'] = '1'
    started = time.perf_counter()
    try:
        with contextlib.redirect_stdout(sys.stderr):
            import numpy as np
            import torch
            from transformers import WhisperForConditionalGeneration, WhisperProcessor
            from transformers.utils import logging as transformers_logging
            transformers_logging.set_verbosity_error()
            transformers_logging.disable_progress_bar()
            torch.set_num_threads(2)
            if not torch.version.hip or not torch.cuda.is_available():
                raise RuntimeError('ROCm GPU unavailable')
            model_dir = Path(args.model).resolve(strict=True)
            if not (model_dir / 'model.safetensors').is_file():
                raise RuntimeError('Local safetensors model required')
            processor = WhisperProcessor.from_pretrained(
                str(model_dir), local_files_only=True, trust_remote_code=False)
            model = WhisperForConditionalGeneration.from_pretrained(
                str(model_dir), local_files_only=True, trust_remote_code=False,
                use_safetensors=True, dtype=torch.float16).to('cuda').eval()
            model.generation_config.language = None
            model.generation_config.task = 'transcribe'
            model.generation_config.forced_decoder_ids = None
            torch.cuda.synchronize()
            free_bytes, total_bytes = torch.cuda.mem_get_info()
        emit(dict(ready=True, device=torch.cuda.get_device_name(0), backend='rocm',
                  load_seconds=round(time.perf_counter() - started, 3),
                  vram_allocated_bytes=torch.cuda.memory_allocated(),
                  vram_reserved_bytes=torch.cuda.memory_reserved(),
                  vram_free_bytes=free_bytes, vram_total_bytes=total_bytes))
    except Exception:
        emit({'ready': False, 'error': 'ROCm Whisper initialization failed'})
        return 1
    while True:
        raw = sys.stdin.buffer.readline(MAX_LINE + 1)
        if not raw:
            return 0
        if len(raw) > MAX_LINE:
            emit({'error': 'Recognition request exceeded limit'})
            return 1
        started = time.perf_counter()
        try:
            pcm, channels, rate, language, beam, prompt = decode_request(json.loads(raw))
            with contextlib.redirect_stdout(sys.stderr), torch.inference_mode():
                audio = prepare_audio(pcm,channels,rate)
                duration = len(audio) / 16000
                if float(np.sqrt(np.mean(audio * audio))) < 1e-4:
                    emit(dict(text='', language=language, audio_seconds=round(duration, 3),
                              inference_seconds=0.0,
                              elapsed_seconds=round(time.perf_counter() - started, 3)))
                    continue
                # Keep short command boundaries away from the recording edges.
                if len(audio) <= 16000 * 29:
                    audio = np.pad(audio, (6400, 6400))
                inputs = processor(audio, sampling_rate=16000, return_tensors='pt',
                                   return_attention_mask=True)
                features = inputs.input_features.to('cuda', dtype=torch.float16)
                mask = inputs.attention_mask.to('cuda')
                prompt_ids = None
                if prompt.strip():
                    prompt_ids = processor.get_prompt_ids(prompt, return_tensors='pt')[:200].to('cuda')
                prompt_length = 0 if prompt_ids is None else prompt_ids.numel()
                token_budget = min(256, model.config.max_target_positions - prompt_length - 8)
                prepared = time.perf_counter()
                tokens = model.generate(
                    features, attention_mask=mask, language=None if language == 'auto' else language,
                    task='transcribe', num_beams=beam, do_sample=False,
                    condition_on_prev_tokens=False, prompt_ids=prompt_ids,
                    max_new_tokens=token_budget, return_timestamps=False)
                torch.cuda.synchronize()
                generated = time.perf_counter()
                text = processor.batch_decode(tokens, skip_special_tokens=True)[0].strip()
                detected = language
                special = processor.tokenizer.convert_ids_to_tokens(tokens[0].tolist())
                for token in special:
                    if token in ('<|ru|>', '<|en|>'):
                        detected = token[2:-2]
                        break
            emit(dict(text=text, language=detected, audio_seconds=round(duration, 3),
                      prepare_seconds=round(prepared - started, 3),
                      inference_seconds=round(generated - prepared, 3),
                      elapsed_seconds=round(time.perf_counter() - started, 3),
                      vram_allocated_bytes=torch.cuda.memory_allocated(),
                      vram_reserved_bytes=torch.cuda.memory_reserved()))
        except (ValueError, TypeError, KeyError, UnicodeError):
            emit({'error': 'Invalid recognition request'})
        except Exception:
            emit({'error': 'ROCm speech recognition failed'})


if __name__ == '__main__':
    raise SystemExit(main())
