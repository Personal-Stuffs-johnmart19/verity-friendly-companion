"""Owned resident speech worker. No shell, no cloud and no GPU allocation at import."""
import base64
import json
import logging
import os
from pathlib import Path
import queue
import subprocess
import threading
import time
import uuid


class GpuWorker:
    REQUEST_TIMEOUT = 90

    def __init__(self, root, config):
        python = (Path(root) / config['stt_rocm_python']).resolve()
        model = (Path(root) / config['stt_rocm_model']).resolve()
        if not python.is_file() or not (model/'model.safetensors').is_file():
            raise RuntimeError('ROCm speech runtime or local model is missing')
        env = dict(os.environ, HF_HUB_OFFLINE='1', TRANSFORMERS_OFFLINE='1',
                   PYTHONIOENCODING='utf-8', TOKENIZERS_PARALLELISM='false')
        self.messages = queue.Queue(maxsize=4)
        self.process = subprocess.Popen([str(python), '-u', str(Path(root)/'rocm_whisper_worker.py'),
                                         '--model', str(model)], stdin=subprocess.PIPE,
                                        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                        creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0),env=env)
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()
        try:
            ready = self._response(90)
            if ready.get('ready') is not True:raise RuntimeError('ROCm speech initialization failed')
        except Exception:
            self.close()
            raise

    def _read(self):
        try:
            while True:
                line = self.process.stdout.readline(32769)
                if not line:break
                if len(line)>32768:raise ValueError('Oversized speech worker response')
                data = json.loads(line)
                if not isinstance(data,dict):raise ValueError('Invalid worker response')
                self.messages.put_nowait(data)
        except Exception:pass
        finally:
            try:self.messages.put_nowait({'error':'worker stopped'})
            except queue.Full:pass

    def _response(self, timeout):
        try:result = self.messages.get(timeout=timeout)
        except queue.Empty:raise RuntimeError('Speech worker timed out') from None
        if 'error' in result:raise RuntimeError('Speech worker failed')
        return result

    def transcribe(self, audio, config):
        from speech_input import decode_options
        options = decode_options(config)
        request = dict(audio=base64.b64encode(audio).decode('ascii'),
                       language=options['language'] or 'auto',beam_size=options['beam_size'],
                       prompt=options['initial_prompt'])
        data = (json.dumps(request,ensure_ascii=False)+'\n').encode('utf-8')
        # A stalled child can stop draining its pipe before response waiting begins.
        # Include delivery in the same deadline; the owner's failure path closes the
        # worker, terminating the child and releasing a blocked writer.
        deadline = time.monotonic() + self.REQUEST_TIMEOUT
        delivered = threading.Event()
        errors = []
        def send():
            try:
                self.process.stdin.write(data)
                self.process.stdin.flush()
            except (OSError, ValueError) as error:
                errors.append(error)
            finally:
                delivered.set()
        threading.Thread(target=send, name='Verity speech request delivery', daemon=True).start()
        if not delivered.wait(max(0, deadline-time.monotonic())):
            raise RuntimeError('Speech worker input timed out')
        if errors:
            raise RuntimeError('Speech worker input failed') from None
        if time.monotonic() >= deadline:
            raise RuntimeError('Speech worker input timed out')
        result = self._response(max(0, deadline-time.monotonic()))
        if time.monotonic() >= deadline:
            raise RuntimeError('Speech worker timed out')
        if not isinstance(result.get('text'),str) or len(result['text'])>6000:
            raise RuntimeError('Invalid speech result')
        return result

    def close(self):
        if self.process.poll() is None:
            self.process.terminate()
            try:self.process.wait(timeout=3)
            except subprocess.TimeoutExpired:self.process.kill();self.process.wait(timeout=3)
        for stream in (self.process.stdin,self.process.stdout):
            try:stream.close()
            except (OSError,ValueError):pass


class SpeechRuntime:
    """Presence leases do not load models. Only microphone requests create a worker."""
    def __init__(self, root, config, worker_factory=GpuWorker, clock=time.monotonic, on_unload=lambda:None,
                 cuda_factory=None):
        self.root,self.config,self.factory,self.clock=root,config,worker_factory,clock
        self.lock=threading.RLock();self.presence_lock=threading.Lock()
        self.closed=threading.Event();self.worker=None;self.cpu_loaded=False;self.session=None;self.players=0
        self.last_seen=clock();self.closed_sessions=set();self.gpu_retry_at=0
        self.revision=-1;self.on_unload=on_unload
        self.cuda_factory=cuda_factory
        self.worker_backend=None;self.retry_at={}

    def session_update(self, session, players, revision=0):
        if not isinstance(session,str) or str(uuid.UUID(session))!=session:
            raise ValueError('Invalid speech session')
        if type(players) is not int or not 0<=players<=1024:raise ValueError('Invalid player count')
        if type(revision) is not int or not 0<=revision<=2**63-1:raise ValueError('Invalid session revision')
        # Publish zero before waiting for inference, so queued audio cannot resurrect it.
        with self.presence_lock:
            if self.closed.is_set() or session in self.closed_sessions:return {'ignored':True}
            if session==self.session and revision<=self.revision:return {'ignored':True}
            changed=bool(self.session and self.session!=session)
            if changed:self.closed_sessions.add(self.session)
            self.session,self.players,self.last_seen,self.revision=session,players,self.clock(),revision
            if len(self.closed_sessions)>32:self.closed_sessions=set(sorted(self.closed_sessions)[:32])
        if changed or players==0:
            with self.lock:
                if changed or self.players==0:self._unload()
        return {'players':self.players,'loaded':self.worker is not None}

    def expire(self):
        with self.lock:
            if (self.worker is not None or self.cpu_loaded) and self.clock()-self.last_seen>150:self._unload()

    def transcribe(self, audio, cpu_fallback,language=None,request_config=None):
        from speech_input import normalize_language
        config=dict(self.config if request_config is None else request_config)
        if language is not None:config['stt_language']=normalize_language(language)
        with self.lock:
            if self.closed.is_set():raise RuntimeError('Speech runtime is closed')
            if config.get('stt_engine') in ('disabled', 'google'):
                raise RuntimeError('Recognition is disabled in the optional local speech bridge')
            if self.session is not None and self.players==0:
                return {'text':'','language':'auto','engine':'idle'}
            self.last_seen=self.clock()
            engine=config.get('stt_engine','faster-whisper')
            if engine not in ('faster-whisper','cpu','cuda','rocm'):
                raise RuntimeError('This backend is owned by the game native runtime')
            # The game owns a prepared Vulkan/native-CPU fallback. Return a
            # framework failure before CPU so Java can apply the configured order.
            fallback=config.get('stt_cpu_fallback',True) and not config.get('stt_vulkan_fallback',False)
            backends=[engine] if engine in ('rocm','cuda') else []
            if not backends and self.worker is not None:self._unload()
            for backend in backends:
                retry=self.gpu_retry_at if backend=='rocm' else self.retry_at.get(backend,0)
                if self.clock()<retry:continue
                try:
                    if self.worker is not None and self.worker_backend!=backend:self._unload()
                    if self.worker is None:
                        if self.cpu_loaded:self._unload()
                        factory=self.factory
                        if backend=='cuda':
                            factory=self.cuda_factory
                            if factory is None:
                                from cuda_stt import CudaWorker
                                factory=CudaWorker
                        self.worker=factory(self.root,config)
                        self.worker_backend=backend
                    result=self.worker.transcribe(audio,config)
                    result['engine']='whisper-rocm' if backend=='rocm' else 'faster-whisper-cuda'
                    return result
                except Exception:
                    self._unload();self.retry_at[backend]=self.clock()+60
                    if backend=='rocm':self.gpu_retry_at=self.retry_at[backend]
                    logging.warning('%s speech unavailable; bounded local fallback',backend)
            if backends and not fallback:raise RuntimeError('GPU speech unavailable; game native fallback may be configured')
            # A failed decode may still have initialized the resident CPU model.
            self.cpu_loaded=True
            result=cpu_fallback(audio);result['engine']='faster-whisper-cpu'
            return result

    def _unload(self):
        worker,self.worker=self.worker,None
        self.worker_backend=None
        self.cpu_loaded=False
        if worker is not None:worker.close()
        self.on_unload()

    def close(self):
        self.closed.set()
        with self.lock:self._unload()
