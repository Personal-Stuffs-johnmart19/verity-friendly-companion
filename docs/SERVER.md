# Server setup

Use Python 3.12. Extract the server-service release ZIP into the Minecraft server
root, producing `verity-service/`, or copy `server/` there from source.

```powershell
python -m venv verity-service/.venv
./verity-service/.venv/Scripts/python.exe -m pip install -r verity-service/requirements.txt
Copy-Item verity-service/voice-config.example.json verity-service/voice-config.json
New-Item -ItemType Directory -Force verity-service/.secrets
```

Save the Google key in `verity-service/.secrets/google-api-key`; never in Git or
on clients. The bridge creates its separate random `bridge-token` on first start.
Copy `verity-companion-server.example.json` into the Minecraft server's
`config/verity-companion-server.json`. Its paths are relative to the server root.

## Recognition

The portable example selects `stt_engine: faster-whisper`, using small CPU int8.
Weights download on first use. CPU inference can be slower than GPU recognition.
For AMD GPU use `stt_engine: rocm`, set `stt_rocm_python` to a separately installed
compatible ROCm environment, and `stt_rocm_model` to local Whisper safetensors,
processor and tokenizer files. That environment needs compatible PyTorch,
Transformers, NumPy and SciPy. Drivers and weights are not included. This backend
was tested on Windows with RX 7900 XTX; other environments are not verified.

`stt_language` accepts `auto`, `ru`, `en`; beam size accepts 1–8.
`stt_cpu_fallback` controls fallback on GPU failure. Model loading is lazy;
workers unload when all players disconnect or their presence lease expires.
The recording HUD shows the input selected by Minecraft. Use M (or its rebound
key) to choose the microphone if the Windows default is unsuitable.

## Chat and speech

The example selects Gemini chat and Achird voice. Voice is fully generated
before playback. Model names are bridge-supported options, not a guarantee of
current provider availability for your account. Set `google_free_project_confirmed`
to true only after confirming the intended free project without billing.
Daily exhaustion skips to the next supported configured TTS model; minute and
transient errors use bounded retries. Text remains available on TTS failure.

The bridge cannot prevent charges if billing is enabled externally. Free tier
does not mean unlimited use. Keep `local_fallback: false` unless you have installed
its optional backend. Silero needs compatible PyTorch. Drama 3 additionally needs
a separate audio.cpp runtime, weights and `verity-tts/server.json`; none is bundled.

Start the service before the Minecraft server:

```powershell
./verity-service/start-bridge.ps1 -ServerRoot 'C:/path/to/minecraft-server'
Invoke-RestMethod http://127.0.0.1:8766/health
```

Only loopback port 8766 is used. Never expose it with port forwarding or a proxy.
Clients communicate over their authenticated Minecraft connection. The helper
runs the bridge in the background; the host manages its process lifetime. It
does not automatically stop with Minecraft. Stop owned processes before updates.

## Settings and updates

Gameplay/horror settings are under the server's `config/` directory; examples
are provided. Relationships, history and undo journals live in the world's
`verity-companion/` directory. Back these up with your world. When updating,
replace code/JARs and preserve settings, secrets, caches, references and worlds.

LM Studio runs separately on loopback. `/llm models` discovers available models;
`/llm local <model>` selects one. Local chat routing does not make Google TTS or
web search local. Disable TTS and avoid Internet tools if those requests are unwanted.

For failures inspect `service.log`/`service-error.log` and the microphone device
and level. An incorrect transcript is a separate issue from a model choosing
the wrong tool. Do not post keys, raw private transcripts or recordings in issues.
