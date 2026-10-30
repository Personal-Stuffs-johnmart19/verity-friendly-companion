# Dependency installation and checks

The cloud-only profile runs inside the addon JAR with the normal game
dependencies; follow [CLOUD.md](CLOUD.md). Python, installer scripts, service ZIPs,
Visual C++ and CUDA are not cloud prerequisites.

The optional Windows installers check the dependency chain for the selected configuration.
They install missing supported software/assets, preserve existing data, and return
a nonzero result when dependencies or required owner actions remain incomplete.
They do not silently replace incompatible modpacks, load models, or restart PCs.

| Component | Automatic installation/check |
| --- | --- |
| Java 21 | Existing JDK detection or pinned Temurin 21.0.13+9 archive with SHA256 |
| Gradle/build libraries | Source bootstrap installs pinned Gradle and resolves the matching NeoForge compile dependencies |
| Minecraft/NeoForge server | Official NeoForge 21.1.250 installer downloads server libraries into a fresh server directory; missing libraries in compatible installations are repaired at the existing loader version |
| Client loader | Checks instance metadata and actual NeoForge files; official-launcher installs support `-MinecraftRoot` |
| Original Verity | Official CurseForge 3.4.1 download, pinned SHA256; original JAR remains unmodified |
| GeckoLib | Official NeoForge 1.21.1 / 4.9.3 download, pinned SHA256 |
| Addon | Bundled in setup ZIPs, or compiled from source; identical JAR on both sides |
| Visual C++ x64 DLLs | Checked on the server; missing runtime uses Microsoft's signed installer, with Windows elevation when required |
| Python (optional) | Existing service venvs on Python 3.10+ are accepted subject to compatible wheels/import checks; new environments default to 3.12. `-PythonVersion` or `-PythonExecutable` selects another interpreter. Native cloud/LM Studio/Qwen/Drama 3 profiles skip Python. |
| Python packages | Reproducible hash-locked 3.12 baseline, version-compatible resolver or check-only existing packages; see dependency modes below |
| Whisper CPU | Selected public model/tokenizer/config files downloaded and verified before first use; small is about 0.45 GiB |
| Native Google chat/TTS | Java HTTP only; hidden server-only key prompt and explicit free-project confirmation. No Python/model required. |
| Native cloud transcription (opt-in) | Same server key; separate explicit microphone-upload consent required |
| Native LM Studio | Direct loopback HTTP, dynamic inventory and model controls; no Python |
| Native web search | Public search results; no paid Gemini 3 grounding tool |

Minecraft EULA acceptance, account authentication, API-key creation and Windows
administrator/driver approvals require the owner. Setup prints the specific
remaining action instead of bypassing those requirements. No cloud synthesis or
chat quota is spent to validate dependencies.

## Optional local backends

Use `-OptionalBackends` to open the explicit local-backend chooser. Rerun the chooser with:

```powershell
.\verity-service\setup-server.cmd -OptionalBackends
```

| Option | Installation behavior |
| --- | --- |
| LM Studio chat | Can install LM Studio via official WinGet packaging; missing WinGet is provisioned from Microsoft's signed App Installer distribution. Model search/download uses `lms get` and your explicit model/quantization choice. Existing models are retained; no model is loaded automatically. LM Studio may require its normal first-run initialization. |
| Legacy `cuda` recognition (Python) | Faster Whisper with CUDA 12/cuBLAS and cuDNN 9. Reuses usable libraries or installs NVIDIA's Python runtime wheels in the service venv; never replaces the GPU driver or system CUDA toolkit. Older drivers/libraries can use Vulkan instead. `-VulkanFallback` explicitly prepares that extra local GPU fallback. |
| Native CPU recognition (`native-cpu`) | audio.cpp CPU portable binary and the same pinned multilingual Qwen3-ASR F16 model. No GPU driver, Python or PyTorch is required. CPU speed depends on the host. |
| Native CUDA recognition (`native-cuda`, Windows x64) | audio.cpp NVIDIA CUDA portable binary and the same Qwen3-ASR F16 model. No Python/cuDNN wheels or system toolkit modifications; a compatible NVIDIA driver is required. |
| Native Vulkan recognition (`vulkan`) | audio.cpp 0.9.3 and multilingual Qwen3-ASR 0.6B F16 (~1.8 GiB model). Intended for AMD, Intel and NVIDIA with a working Vulkan driver, independently of PyTorch/ROCm/CUDA Python builds. Java owns its local process, starts it on the first voice request and unloads it when all players leave or the server stops; no Python bridge is required. No recording is saved by this adapter. Hardware/model quality still needs testing on the target machine. |
| ROCm Whisper | Reuses a supplied compatible Python environment without modifying it, or installs isolated AMD ROCm 10.1.0 / PyTorch 2.14.0 packages from AMD's stable index, selecting only the detected GFX device kernels and Whisper-small safetensors. The managed profile matches the versions/provenance inspected in a working RX 7900 XTX (`gfx1100`) environment. A clean installation installs the ROCm SDK core/libraries, matching device SDK and Torch kernel packages plus Whisper/Transformers dependencies. The actual Whisper classes and audio feature extraction are checked without loading a speech model. Unsupported targets/drivers are reported; no display driver is silently replaced. |
| Silero RU/EN | Installs CPU PyTorch 2.9.1 and the configured official RU/EN model packages. Upstream model-server failures stop that optional setup rather than activating a broken provider. |
| Drama 3 | Installs pinned audio.cpp 0.9.3 Windows Vulkan portable binaries and the full Q4 model. The roughly 23 GiB model requires explicit large-download confirmation. Setup accepts your own PCM16 mono/stereo RU/EN WAV recordings (1–30 seconds, 8–192 kHz) and exact transcripts, copies WAVs unchanged, preserves existing references and disables expert pinning. MP3/OGG conversion is an explicit optional Python/PyAV preparation step; it is not required at runtime. Vulkan driver support is required. |

Example unattended selections (credentials/reference files must already exist):

```powershell
.\verity-service\setup-server.cmd -SttBackend cpu -TtsBackend google -NonInteractive
.\verity-service\setup-server.cmd -SttBackend cuda -VulkanFallback -DependencyMode compatible
.\verity-service\setup-server.cmd -SttBackend vulkan -CpuFallback off
.\verity-service\setup-server.cmd -SttBackend rocm -RocmPython 'C:\ML\rocm\Scripts\python.exe'
.\verity-service\setup-server.cmd -TtsBackend drama3 -DownloadLargeModels -ReferenceConfig 'C:\private\references.json'
```

`-SttBackend auto` selects native CUDA when Windows reports an NVIDIA adapter and Vulkan
otherwise; explicit choices override this detection. It does not guarantee a
working driver. Vulkan fallback assets are downloaded only when that backend or
`-VulkanFallback` is selected. CUDA/ROCm failure tries the prepared Vulkan backend,
then CPU if `stt_cpu_fallback` is true (default). Use `-CpuFallback off` to forbid
CPU fallback or `-CpuFallback on` to enable it; `keep` preserves the setting.
These local fallback paths never switch to cloud
or download models during a voice request. Unavailable GPU backends have a bounded
retry cooldown. TTS remains a separate choice.

## Why there is a requirements lock

`setup/python/requirements-lock.txt` records the tested CPython 3.12 package versions,
including transitive dependencies, and permitted SHA256 download hashes. It is a
reproducibility/integrity baseline, not a GPU-vendor requirement or a dependency
for native cloud/LM Studio/Qwen/Drama 3 profiles. It contains no API keys or model weights.

The installer accepts four `-DependencyMode` choices:

| Mode | Behavior |
| --- | --- |
| `auto` (default) | Uses the hash lock on CPython 3.12; uses the bounded compatible resolver on other Python 3.10+ versions. Prints the selected mode. |
| `locked` | Explicitly requires the tested CPython 3.12 hash lock; never silently relaxes it after a failure. |
| `compatible` | Resolves selected-profile requirements within `requirements-compatible.txt` ranges using official PyPI wheels. Versions/checksums differ from the baseline; package/import checks still run. |
| `existing` | Does not install/change Python packages. Checks the service venv's dependency graph and selected native imports. Missing optional packages are reported or an already prepared local fallback is used. Model/native-tool preparation remains a separate selected setup action. |

Python packages always target the selected isolated environment, never a global
interpreter. The default service environment remains `verity-service/.venv`.
`-VenvPath` selects a different directory; the selected interpreter is recorded
as the absolute `python_executable` in server `voice-config.json`. The separately
configured ROCm worker interpreter remains `stt_rocm_python`.

For a new empty environment, `-PythonVersion` or `-PythonExecutable` selects its
interpreter. Setup refuses a nonempty directory that is not a valid isolated
venv. `-UseExistingVenv` explicitly reuses an existing environment; by default it
only checks installed packages and imports. Add `-InstallPythonPackages` only
when you also authorize installing dependencies into that reused environment.
It does not replace the environment. When package installation is authorized,
the chosen locked/compatible policy still applies.
The preflight requires Python 3.10+ with system site packages disabled.

```powershell
# New selected environment, optional local engine:
.\verity-service\setup-server.cmd -SttBackend cpu -VenvPath 'D:\Verity\speech-env' -PythonVersion 3.12
# Reuse and check existing packages without installing into it:
.\verity-service\setup-server.cmd -SttBackend cpu -VenvPath 'D:\Verity\speech-env' -UseExistingVenv
# Explicitly allow package installation into that reused environment:
.\verity-service\setup-server.cmd -SttBackend cpu -VenvPath 'D:\Verity\speech-env' -UseExistingVenv -InstallPythonPackages -DependencyMode compatible
```

The shared `setup/python/python_environment.py` helper validates the selected
interpreter and package policy. Framework installation and model preparation are
separate steps. Pure cloud and native Qwen/Drama 3 profiles skip this helper and
need no Python. `-Offline` forbids new downloads. Unsupported OS/Python/GPU wheel
combinations stop with a repair hint; successful dependency checks do not prove
model quality or GPU compatibility. Linux ROCm requires a matching supported
AMD/PyTorch wheel profile or an explicitly supplied compatible environment;
Windows wheels are never treated as Linux packages.

## Models and reference recordings

`ReferenceConfig` is server-only JSON with `ru`/`en` objects containing `audio`
(a local file path) and `text` (its exact transcript). No voice reference is
downloaded or redistributed by the project. New recordings must be 1-30 seconds.
Model assets are downloaded without inference. A failed optional preparation
keeps the previously active configuration. Existing worlds, history, keys and
references are preserved.

`-SkipModelDownloads` explicitly defers voice readiness; it does not produce a
false all-ready result. `-Offline` forbids new network downloads. After one full
online setup, existing selected assets can be checked offline. Use
`backend_setup.py --verify-hashes` for a full model-file checksum pass.

## Sources

Downloads use the official [NeoForge Maven repository](https://maven.neoforged.net/),
[Verity CurseForge file](https://www.curseforge.com/minecraft/mc-mods/verity-je/files/8346795),
[GeckoLib Maven](https://github.com/bernie-g/geckolib),
[uv](https://github.com/astral-sh/uv),
[Microsoft Visual C++ runtime](https://learn.microsoft.com/en-us/cpp/windows/latest-supported-vc-redist),
[LM Studio CLI](https://lmstudio.ai/docs/cli),
[AMD's Windows PyTorch packages](https://github.com/ROCm/TheRock/blob/main/RELEASES.md),
[Faster Whisper's NVIDIA requirements](https://github.com/SYSTRAN/faster-whisper#gpu),
[Silero](https://github.com/snakers4/silero-models) and
[audio.cpp](https://github.com/0xShug0/audio.cpp).
Public model downloads are pinned to a resolved commit and checked against Hub
file metadata; local asset manifests support later offline checks. Native audio
and bootstrap archives use pinned SHA256; Microsoft system installers use
Authenticode/AppX publisher validation. Optional weights keep their upstream
licenses and are not included in release archives.

## Operating system limits

The backend table describes the prepared Windows profiles unless explicitly
stated otherwise. On Linux, native audio.cpp provisioning supports pinned x64
CPU/Vulkan archives; general-purpose `native-cuda` is refused rather than using
the upstream Colab-specific binary. Python CUDA needs compatible Linux
CTranslate2/NVIDIA packages. ROCm needs a supported Linux/Python/GFX wheel match;
Windows ROCm package names or inspected provenance do not establish Linux support.
An existing venv must be isolated and compatible with the selected interpreter
and operating system. `existing` mode checks it without installing packages.

Installer fixtures validate metadata, paths, hashes and dependency decisions.
They do not establish GPU execution, model accuracy, latency or voice quality on
new hardware. Test the explicitly selected backend separately before relying on
it in a running server.
