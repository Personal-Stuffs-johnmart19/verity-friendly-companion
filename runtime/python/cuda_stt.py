"""Lazy resident Faster Whisper CUDA adapter; only the owned model is released."""
import io
from pathlib import Path
import wave


class CudaWorker:
    def __init__(self, root, config):
        from cuda_dependencies import probe
        supported = probe()
        if not supported:
            raise RuntimeError('No supported CUDA compute type')
        from speech_input import model_directory
        from faster_whisper import WhisperModel
        # Setup resolves/downloads assets. Recognition must work offline.
        model = model_directory(config)
        if not model.is_dir():
            raise RuntimeError('Prepared local CUDA Whisper model missing')
        compute = config.get('stt_cuda_compute_type', 'float16')
        if compute not in ('float16', 'int8_float16', 'int8', 'float32'):
            raise ValueError('Unsupported CUDA compute type')
        if compute not in supported:
            compute = 'float32' if 'float32' in supported else supported[0]
        self.model = WhisperModel(str(Path(model)), device='cuda', compute_type=compute,
                                  cpu_threads=config.get('threads', 4), local_files_only=True)

    def transcribe(self, audio, config):
        import numpy as np
        from scipy.signal import resample_poly
        from math import gcd
        from speech_input import decode_options, accepted_text
        with wave.open(io.BytesIO(audio), 'rb') as source:
            channels, rate, frames = source.getnchannels(), source.getframerate(), source.getnframes()
            if source.getsampwidth() != 2 or channels not in (1, 2) or not 8000 <= rate <= 96000 or not 0 < frames <= rate * 30:
                raise ValueError('Expected up to 30 seconds of PCM16 WAV')
            pcm = source.readframes(frames)
            if len(pcm) != frames * channels * 2:
                raise ValueError('Truncated PCM WAV')
        samples = np.frombuffer(pcm, dtype='<i2').astype(np.float32) / 32768
        if channels > 1:
            samples = samples.reshape(-1, channels).mean(axis=1)
        if rate != 16000:
            divisor = gcd(rate, 16000)
            samples = resample_poly(samples, 16000 // divisor, rate // divisor).astype(np.float32)
        segments, info = self.model.transcribe(samples, **decode_options(config))
        return {'text': accepted_text(segments), 'language': info.language}

    def close(self):
        self.model = None
