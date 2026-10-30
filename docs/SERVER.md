# Server setup

This guide documents **beta.21**. Older beta.20 installations
must follow the [pinned beta.20 server guide](https://github.com/Personal-Stuffs-johnmart19/verity-friendly-companion/blob/v1.2.0-beta.20/docs/SERVER.md).

## Cloud setup with the addon JAR

For Google chat, transcription and TTS, follow [the JAR-only cloud guide](CLOUD.md).
Install the matching addon, official Verity 3.4.1 and GeckoLib on both sides of
Minecraft 1.21.1 / NeoForge 21.1.250 or newer 21.1.x, using Java 21. Cloud providers
execute in the addon: no Python, setup scripts, service ZIP, Visual C++ runtime,
CUDA or listening speech worker is required.

Start the server and run `/companion setup` as an operator. The addon creates
`verity-service/voice-config.json` and the private `.secrets/` directory; save the
Google key in `verity-service/.secrets/google-api-key`, a server-only text file
without an extension. After checking that the Google project has no billing,
set `google_free_project_confirmed: true` in `voice-config.json`. Clients never
receive this key. Config/key changes are read on new requests. The addon cannot
inspect or change Google's billing settings; quotas still apply.

The default voice remains **Achird**, with model-specific multilingual support.
See [language settings](LANGUAGES.md). Complete audio is
produced before playback frames are sent. Daily quota exhaustion skips that
model; minute limits wait within a bounded request budget. If no free model is
available, the text response remains visible. Model action JSON must be completed
and validated before any tool executes.

Microphone recognition is **disabled on fresh installs**. Choose either:

- Native local recognition: `-SttBackend auto`, `vulkan`, `native-cuda` or `native-cpu`;
  this prepares audio.cpp/Qwen weights without Python. Recordings stay on the server.
- Optional Python recognition: `-SttBackend cpu`, legacy `cuda` or `rocm`.
- Cloud recognition: set `stt_engine: google` and
  `google_audio_transcription_confirmed: true` in server `voice-config.json`, only
  after agreeing to upload recordings to Google and informing players. No script
  or new provider account is needed.

`/llm models`, `/llm local <model>`, `/llm cloud`, `/llm context`, reasoning,
temperature, output tokens and prompt additions work through native Java too.
LM Studio must already expose its loopback Model Server. Optional authentication
uses `verity-service/.secrets/lmstudio-api-key`. Capability settings come from its
live inventory; unsupported reasoning levels are rejected rather than guessed.

For a network-isolated profile set `allow_external_requests: false`, use local
chat, and local/disabled recognition and TTS. This blocks this addon's native
cloud calls and web tools; it is not an operating-system firewall for other mods.

Existing `runtime: bridge` configurations remain accepted as compatibility selectors; dialogue, search and model management now always execute in Java.
To migrate deliberately, stop Minecraft and run setup with
`-RuntimeMode native -UpdateService`; choose recognition/TTS explicitly. Existing
keys, histories, models and settings are preserved. Faster Whisper/ROCm/Silero still need the optional Python speech worker. Qwen ASR and Drama 3 use native processes owned by Java.

## Basic and advanced Windows installation

Run `setup.cmd` from source, or `verity-service/install.cmd`
from the optional server archive. The
interactive wizard discovers known Java/CurseForge locations, presents compatible
candidates and lets you choose a destination; discovery never selects a game
installation silently. A client launcher/account and Minecraft EULA acceptance
remain the host's responsibility.

Basic mode exports the addon JAR or installs the addon and required game mods in
an explicitly chosen compatible installation. It also supports a separately
selected empty standalone NeoForge server directory. It does not install Python,
local speech models or credentials. Existing games must be closed before mod
replacement; previous addon files are backed up. Existing pack metadata, saves,
provider settings and custom launchers are preserved.

Advanced mode is server-only. Choose recognition/TTS backends and, when Python is
needed, a managed/new/reused isolated environment. Model preparation is a separate
choice; declining it leaves local readiness deferred. Drama 3's large weights need
additional explicit consent. No installer launches Minecraft or performs inference. If an advanced
installation retains a prepared `python_executable`, its launcher keeps that
optional speech worker idle so available framework backends can be selected in
game. Selecting cloud/native audio does not uninstall that prepared environment.
Basic/JAR-only installations do not start a Python worker.

```powershell
# Read-only discovery and plan; no downloads or directory creation:
.\setup.cmd -Discover
.\setup.cmd -Plan -Profile basic -ExportDirectory 'C:\Verity\jar'
# Export the addon without installing speech tools:
.\setup.cmd -Profile basic -ExportDirectory 'C:\Verity\jar' -NonInteractive
# Install core dependencies in an explicitly selected new server directory:
.\setup.cmd -Profile basic -ServerRoot 'C:\Servers\Verity' -StandaloneServer
# Advanced selection; model download is still an explicit option:
.\setup.cmd -Profile advanced -ServerRoot 'C:\Servers\Verity' -SttBackend cpu -TtsBackend disabled
```

`config/verity-installation.json` records the selected profile and prepared local
backends. It is advisory, contains no API key and is not a runtime readiness proof.
The running addon rechecks metadata and prepared files. After preparing a backend
or starting its optional worker, use `/llm refresh`; `/tts provider` and `/voice
backend` offer currently available choices to operators. See the
[command guide](COMMANDS.md).

## Linux installation

From source run `bash setup.sh`; from the optional server archive run
`bash verity-service/setup.sh`. Both offer basic JAR/game dependencies and
advanced server-only speech preparation. No setup archive is needed for the
[JAR-only cloud path](CLOUD.md).

```sh
bash setup.sh --list-candidates
bash setup.sh --plan --profile basic --server-root /path/to/server
bash setup.sh --profile basic --addon-jar /path/to/addon.jar --export-directory /path/to/export --non-interactive
bash setup.sh --profile basic --standalone-server /path/to/new-empty-server --non-interactive
bash setup.sh --profile advanced --server-root /path/to/server --stt cpu --tts disabled --venv-path /path/to/venv --use-existing-venv --non-interactive
```

Choose an existing compatible client/server candidate or an empty standalone
server directory explicitly. Existing pack metadata and custom launchers are
preserved. Setup checks actual loader libraries, not only a version label; a
managed client may require repair through its launcher. It never provisions an
account, accepts the EULA or launches Minecraft. The generated server launcher is
`verity-service/start-server.sh`.

Advanced setup leaves model downloads deferred unless `--download-models` is
passed; multi-GiB weights also need `--download-large-models`. Reusing an isolated
Python environment checks installed dependencies by default; modifying its
packages additionally requires `--install-python-packages`. Use `--plan` before
preparation and `--check` for read-only installed dependency checks.
On Linux, `--dependency-mode auto` selects the bounded compatible wheel resolver;
explicit `locked` requires the hashed CPython 3.12 baseline and never silently
falls back. Linux resolution of that lock has not been validated.

Pinned audio.cpp Linux binaries cover x64 CPU and Vulkan. General-purpose native
CUDA preparation is unavailable in this installer; select framework `cuda`, CPU
or Vulkan explicitly. Linux ARM64 needs a separately supported speech backend.
ROCm requires matching Linux/Python/GPU wheel support and is refused when no
supported combination is available. Windows DLLs/wheels are never reused as Linux
runtimes. Driver and system package changes remain an explicit host action;
setup never runs `sudo`. Prepared metadata is not an inference or quality test.

## Advanced backend preparation on Windows x64

Optional recognition supports CPU, NVIDIA CUDA, AMD ROCm and cross-vendor Vulkan.
Python is needed only for Faster Whisper CPU/CUDA, ROCm, Silero and compatibility speech profiles. Native Qwen ASR and Drama 3 use Java-managed audio.cpp processes. New environments
default to 3.12; existing compatible Python 3.10+ service venvs are preserved.
Use `-PythonVersion`, `-PythonExecutable` and `-DependencyMode` to select a setup
policy. See [dependency modes and GPU choices](DEPENDENCIES.md).

From a clone/source ZIP, build and provision a server in one command:

```powershell
.\setup.cmd -ServerRoot 'C:\path\to\minecraft-server' -OptionalBackends
```

For a matching optional local-service bundle, extract the **server-service ZIP** into a new or existing server
root and run `verity-service/setup-server.cmd`. The ZIP includes our universal
addon JAR. No preinstalled Java, Python, Gradle, NeoForge, Verity or GeckoLib is
needed for a fresh supported Windows setup. The scripts download original Verity
3.4.1 and GeckoLib from their official sources, check hashes, install the official
NeoForge 21.1.250 server and its libraries, then configure native providers. A Python environment and speech weights are
installed only for selected Faster Whisper, ROCm or Silero engines. Original Verity is not modified or
redistributed inside the project. A compatible existing pack loader is preserved;
missing libraries can be repaired without silently upgrading the pack.

Java/bootstrap/model downloads are cached per user or under the server service.
For optional Python speech profiles, native Visual C++ DLLs and real
speech-package imports are checked. If a required
Microsoft system runtime is missing, setup offers its signed installer; Windows
administrator approval may be required. No PC/game/model restart is automatic.

The optional chooser offers LM Studio, ROCm recognition, Silero RU/EN and Drama 3.
Large model downloads require explicit confirmation. Unselected backends are not
installed. See [the dependency matrix](DEPENDENCIES.md) for package/hardware
requirements and unattended selections. Setup downloads assets without inference;
model loading remains lazy on the first microphone request.

The Google key prompt hides input. The key is saved only to
**`verity-service/.secrets/google-api-key`**, with the credential directory private
to the Windows account running setup. Run the bridge under that same account.
The host must explicitly confirm a Google project without billing before cloud
chat/search/TTS are enabled. Skipping a required key leaves a clear incomplete
setup result. Never paste credentials into Minecraft or CLI arguments.

```powershell
# Replace a revoked/wrong server key with hidden input:
.\verity-service\setup-server.cmd -ReplaceGoogleKey
# Check all required software/model/configuration dependencies without installing:
.\verity-service\setup-server.cmd -CheckOnly
# Reopen the optional local backend chooser:
.\verity-service\setup-server.cmd -OptionalBackends
# New local chat with voice replies disabled:
.\setup\setup-server.cmd -ServerRoot 'C:\path\to\server' -ChatProvider local -TextOnly
```

`-NonInteractive` never prompts: pre-provision credentials/reference files and
confirm the free project with `-ConfirmFreeProject` only after checking it.
`-Offline` forbids new downloads; complete one online setup first.
`-CacheDirectory` selects a tools cache. `-SkipModelDownloads` deliberately leaves
voice dependencies incomplete and does not report an all-ready result.

Existing settings, keys, models, recordings, conversations, worlds and unrelated
files are preserved. To replace service code explicitly, stop your bridge and use
`-UpdateService`. Stop Minecraft before replacing its loaded addon JAR. Conflicting
JARs and incompatible packs are reported instead of being deleted automatically.
Restart the bridge yourself after changing its cached configuration.

Setup creates **`run-verity-server.cmd`** in the server root, using the configured
Java 21 executable, correctly separated JVM arguments and a short socket path.
Review/accept the Minecraft EULA in `eula.txt` before first launch. That launcher
starts an optional bridge only when the selected profile needs it, then
starts Minecraft when you run it. Clients use the
separate [client installer](CLIENT.md), with no Python, model weights or keys.

## Manual setup / other platforms

Cloud setup on other platforms still uses only the addon and game dependencies;
see [CLOUD.md](CLOUD.md). For optional Python engines, use Python 3.10+ with
available compatible wheels (3.12 is the tested baseline).
Extract the matching server-service ZIP into the Minecraft server
root, producing `verity-service/`, or stage the source files with the declared mapping in `setup/source-layout.ps1`; source folders are not installed verbatim.

```powershell
python -m venv verity-service/.venv
./verity-service/.venv/Scripts/python.exe -m pip install -r verity-service/requirements-compatible.txt
Copy-Item verity-service/voice-config.example.json verity-service/voice-config.json
New-Item -ItemType Directory -Force verity-service/.secrets
```

Save the Google key in `verity-service/.secrets/google-api-key`; never in Git or
on clients. The bridge creates its separate random `bridge-token` on first start.
Copy `verity-companion-server.example.json` into the Minecraft server's
`config/verity-companion-server.json`. Its paths are relative to the server root.

## Recognition

The portable example selects `stt_engine: disabled`. Choose local
`stt_engine: faster-whisper` for small CPU int8 recognition.
The automatic installer pre-downloads weights without loading the model.
Manual setups may download them on first use. CPU inference can be slower than GPU recognition.
For AMD GPU use `stt_engine: rocm`, set `stt_rocm_python` to a separately installed
compatible ROCm environment, and `stt_rocm_model` to local Whisper safetensors,
processor and tokenizer files. That environment needs compatible PyTorch,
Transformers, NumPy and SciPy. Drivers and weights are not included. This backend
was tested on Windows with RX 7900 XTX; other environments are not verified.

`stt_language` accepts `auto`, `ru`, `en`; beam size accepts 1-8.
`stt_cpu_fallback` controls fallback on GPU failure. Model loading is lazy;
workers unload when all players disconnect or their presence lease expires.
The recording HUD shows the input selected by Minecraft. Use M (or its rebound
key) to choose the microphone if the Windows default is unsuitable.

## Optional Python speech worker

Faster Whisper CPU/legacy CUDA, ROCm and Silero require the optional Python
speech worker. It handles local audio only; cloud chat/TTS, LM Studio and web
search run in Java. Native Qwen ASR and Drama 3 do not require this worker.
`local_fallback: true` uses `local_fallback_provider` (`silero` by legacy default,
or `drama3`). Silero needs prepared Python dependencies; Drama 3 uses the native
Java-managed process and its model/reference assets. Existing server-only settings and keys retain their paths.

Start the service before the Minecraft server:

```powershell
./verity-service/start-bridge.ps1 -ServerRoot 'C:/path/to/minecraft-server'
Invoke-RestMethod http://127.0.0.1:8766/health
```

The bridge uses loopback port 8766 by default (or the explicitly configured port). Never expose it with port forwarding or a proxy.
Clients communicate over their authenticated Minecraft connection. The helper
runs the bridge in the background; the host manages its process lifetime. It
does not automatically stop with Minecraft. Stop owned processes before updates.

The worker's `/health` reports process readiness, dependency issues and setup
issues for optional Python audio only. Its authenticated `/audio/status` route
checks the same local audio state. Cloud readiness and private host hints come
from `/companion setup` inside Minecraft; the worker never reads the Google key.
These checks do not spend Google quota or prove provider availability. The
original mod's client-side key configuration is unused; do not configure keys on
clients.

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

## Setup validation

Validated on Windows with fresh service directories, paths containing spaces,
a different caller working directory, an extracted service ZIP, missing-key
handling, key-free local text mode and preservation during repeat setup. A
minimal headless NeoForge 21.1.250 server started with unmodified Verity 3.4.1,
GeckoLib 4.9.3 and this addon; the setup command completed and the server shut
down cleanly. The original Verity JAR stayed unchanged. These checks do not
measure microphone accuracy, audible gameplay or live Google model availability.
