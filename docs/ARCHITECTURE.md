# Source and runtime architecture

The Minecraft addon under `src/main/java` owns dialogue, provider routing, permissions,
gameplay and player connections. Credentials, models and speech processing stay on
the server. Clients install the same addon JAR, original Verity and GeckoLib.

| Source directory | Responsibility |
| --- | --- |
| `src/main/java`, `src/main/resources` | NeoForge addon and native Java providers |
| `config/` | Public configuration examples; no live settings |
| `runtime/python/` | Optional Faster Whisper, ROCm and Silero speech worker |
| `setup/python/` | Optional Python dependency/model provisioning, readiness checks and requirements manifests |
| `setup/` | Shared source-to-installed-service mapping |
| `setup/windows/` | Windows installers, discovery, dependency and native asset preparation helpers |
| `setup/linux/` | Bash discovery/bootstrap/install helpers and the dependency-free Java metadata helper |
| `launchers/` | Server and optional speech process launchers |
| `tools/build/` | Source build entry points and the optional legacy installed-library build |
| `tools/release/` | Source-only setup archive packaging and checksums |
| `docs/` | Installation, dependency and build instructions |

Root Windows `setup.cmd` and Linux `setup.sh` are installer entry
points. Source compilation is explicit (`-BuildOnly` on Windows, `tools/build/build.sh` on
Linux). Entry points resolve paths against their own locations. Basic mode exports
or installs the addon and required game mods; advanced mode prepares only selected
local backends. Neither mode starts Minecraft or performs inference.
`setup/source-layout.ps1` declares the source-to-install mapping: installers and
packaging enumerate the same files and reject duplicate installed filenames.
Public shell helpers and `LinuxSetup.java` are flattened alongside Windows
helpers. Optional ZIPs keep a `docs/` directory so relative guide links work;
shell text is packaged with LF line endings. Source-only build and packaging
tools are excluded from these runtime setup archives.

The repository root keeps the README, license, project/security/change notes,
standard Gradle files and one setup launcher per operating system. The command
reference lives only in [COMMANDS.md](COMMANDS.md). Generated `build/`, `dist/`
and `.gradle/` directories are ignored by Git; local diagnostic leftovers stay
under `build/local-artifacts/`.

Installed `verity-service/` keeps its flat filenames. `voice-config.json`,
`chat-routing.json`, `.secrets/`, `.venv/`, native runtimes and models keep their
existing paths. Drama 3 assets remain in the configured `verity-tts` directory.
Source organization never migrates private runtime data or renames credentials.

Cloud providers and LM Studio use Java HTTP. The cloud-only addon creates its
server settings/private credential directory itself and needs no service ZIP,
Python or setup script; see [CLOUD.md](CLOUD.md). Native Qwen ASR and Drama 3 use
Java-managed audio.cpp processes; their platform setup helpers use HTTPS metadata,
pinned revisions and SHA256 verification, without Python. Optional Faster Whisper,
ROCm and Silero require the Python speech worker. Native audio does not load a
model or generate speech during setup. Reference WAVs are validated and preserved
without resampling; unsupported formats produce an actionable preparation error.

The generated server `config/verity-installation.json` records the chosen profile
and prepared backends. It is advisory state, excluded from source distributions
and setup archives. Runtime choices come from bounded metadata/readiness checks,
not that manifest alone. Command suggestions use a cached capability snapshot;
refreshing does not run inference or download assets. Private settings, credentials,
worlds and selected Python environments retain their existing paths.
