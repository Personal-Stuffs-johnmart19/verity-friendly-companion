# Building and packaging

## Clean Windows installation

Clone the repository or extract GitHub's source ZIP anywhere, including a path
with spaces. Run `setup.cmd` to open the optional installer wizard, or pass `-BuildOnly`
for a source build without the wizard. Absolute entry-point paths work from any
caller directory. Windows PowerShell 5.1 is sufficient; Git and administrator
access are not required for the portable source build.

```powershell
.\setup.cmd -BuildOnly
# Also package the server service and generate release checksums:
.\setup.cmd -BuildOnly -PackageRelease
# Explicit server/client provisioning (does not launch Minecraft):
.\setup.cmd -ServerRoot 'C:\path\to\minecraft-server' -OptionalBackends
.\setup.cmd -ClientRoot 'C:\path\to\client-instance'
```

The script detects a Java 21 JDK from `JAVA_HOME` or `javac`. If missing, it installs
a portable Temurin 21.0.13+9 JDK on Windows x64. It also installs Gradle 9.2.1.
Both archives have pinned SHA256 hashes checked before extraction/execution.
No persistent PATH/JAVA_HOME changes, registry edits, game installation, API keys
or running Minecraft server are needed. Original Verity is required to **play**,
but is not needed to compile this addon. Runtime setup downloads original Verity
from its official file; no third-party mod is bundled or privately patched.

The default tools/dependency cache is `%LOCALAPPDATA%\VerityFriendlyCompanion\build-tools`.
Initial downloads include Java (when needed), Gradle and NeoForge/Minecraft build
dependencies; allow several GB of space and a longer first build. Later builds
reuse the cache. Interruptions or checksum failures stop with an error; retry
after resolving the network/cache problem. It does not launch Minecraft or models.

```powershell
.\setup.cmd -BuildOnly -JavaHome 'C:\path\to\jdk21'
.\setup.cmd -BuildOnly -UsePortableJdk -CacheDirectory 'D:\build cache'
.\setup.cmd -BuildOnly -Offline
```

`-UsePortableJdk` ignores automatic system-JDK discovery; an explicit `-JavaHome`
still takes priority. Automatic JDK download is Windows x64 only; other Windows
architectures require an explicit compatible JDK. `-Offline` refuses missing
prerequisites/dependencies instead of downloading. A successful online build must
populate the same cache first. The script restores process-local JAVA_HOME and
JAVA_TOOL_OPTIONS on exit. It uses a short user-owned socket directory at
`%USERPROFILE%\.vfc-jtmp` to avoid JDK Windows socket-path failures. For an unusually
long profile path, use `-SocketDirectory 'C:\short\writable-path'`.

The NeoForge ModDevGradle build pins plugin 2.0.148 / NeoForge 21.1.250, fetches
matching compile dependencies and writes `dist/verity-friendly-companion-<version>.jar`.
Version comes from `neoforge.mods.toml`. Only addon classes/runtime resources and
the license are packaged; dependencies are not embedded. Jar ordering/timestamps
are normalized. Existing compiler deprecation/unchecked warnings stay visible.

Text resources and the embedded license are normalized to LF during packaging,
with UTF-8 filtering; Windows CRLF and LF source checkouts produce the same JAR.
For older published artifacts, use their original release checksums when
verifying downloads; rebuilding with changed packaging rules can produce a
different checksum even when compiled classes are identical.

Validated on Windows with a fresh portable JDK/tools cache, a source folder with
spaces and a different caller working directory. A second fresh source folder
built offline using the populated cache and produced a byte-identical JAR.
Both builds passed release packaging validation. Missing offline prerequisites
and a corrupted cached archive were also verified to fail before execution.

## Linux and existing Gradle

On Linux, `bash setup.sh` opens the basic/advanced installer. Use
`bash tools/build/build.sh` or `bash setup.sh --build-only` for compilation without an
installation target. Entry points resolve their own directory, so an absolute
script path works from another working directory.

```sh
bash /path/to/source/setup.sh --list-candidates
bash /path/to/source/setup.sh --plan --profile basic --export-directory /path/to/export
bash /path/to/source/tools/build/build.sh --java-home /path/to/jdk21
bash /path/to/source/tools/build/build.sh --offline
bash /path/to/source/tools/release/package-release.sh --java-home /path/to/jdk21
```

The Linux bootstrap checks a Java 21 JDK and supports pinned portable Java/Gradle
archives. Downloads are verified before extraction or execution. It does not run
`sudo`, change the system Java selection, accept the Minecraft EULA or launch a
game/model. `--offline` requires an already populated cache. `--plan` and
`--list-candidates` do not download or create an installation. See
[server setup](SERVER.md) for backend and platform limits.

With an existing Java 21 JDK and Gradle 9.2.1, any supported system can also run:

```sh
gradle --no-daemon -p /path/to/repository jar
```

Python is not required to compile the JAR or use Java cloud/LM Studio providers.
Optional local speech support depends on the selected operating system/backend;
Faster Whisper, ROCm and Silero use a separately prepared Python environment.

Build tooling follows the official [NeoForge ModDevGradle](https://github.com/neoforged/ModDevGradle)
and [Minecraft 1.21.1 MDK](https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle)
configuration. Portable Java comes from [Eclipse Temurin](https://adoptium.net/);
Gradle comes from [Gradle's distribution service](https://gradle.org/releases/).

## Optional legacy installed-library build

`tools/build/build.ps1` with no client/server parameters delegates to the portable source
build using `-BuildOnly`, without opening the installer wizard.
Existing installations can still use the old compiler path:

```powershell
./tools/build/build.ps1 -ServerRoot 'C:/path/to/server' -ClientRoot 'C:/path/to/minecraft-install' -JavaHome 'C:/path/to/jdk21'
```

Both roots must contain matching `libraries/` directories. This legacy route
does not download dependencies; `ClientRoot` is not the instance directory.

## Release packaging

Cloud-only runtime needs the addon JAR and the normal game dependencies; see
[CLOUD.md](CLOUD.md). The service/client ZIPs below are optional provisioning
bundles, not cloud prerequisites.

Installer, dependency provisioning, documentation and packaging-only updates do
not require a new mod version or release. Validate and commit these changes
separately; users can obtain current setup scripts from the repository. Do not
rebuild or replace published JARs/ZIPs just to refresh an installer. Runtime bridge
changes are service changes and must be reviewed separately from provisioning;
gameplay, protocol and Java runtime changes belong to the mod release process.

```powershell
./tools/release/package-release.ps1 -JavaHome 'C:/path/to/jdk21'
```

Run packaging after the build. It verifies the JAR's metadata, rejects test,
diagnostic, original-Verity, secret and recording entries, then creates a separate
server-service ZIP, client-setup ZIP and SHA256SUMS.txt. The same single universal addon JAR is used
on clients and servers. Python code and configuration examples belong only in the
server-service ZIP. The setup ZIPs bundle our addon JAR for automatic installation and contain no
configured credentials, third-party mod JARs or model weights.

Beta.21 is a beta release; beta.20 remains the historical public baseline.
Future releases/tags require finalized validation. Setup-only updates stay in
source and do not advance the mod version.

Release publication is separate from packaging. Upload the JAR, service ZIP, client-setup ZIP and
checksums with release notes. Keep the beta/prerelease designation until broad
in-game validation warrants a stable release. Local regression harnesses and
historical diagnostics are deliberately not part of the public tree or artifacts.

## Source layout

`config/` contains public examples, `runtime/python/` optional speech runtime, `setup/python/` provisioning code and
requirements, `setup/windows/` and `setup/linux/` platform installers,
`tools/build/` source build entry points, `tools/release/` packaging tools,
and `launchers/` process launchers.
`setup/source-layout.ps1` maps these to the existing flat `verity-service/`
distribution. Installer and packaging use that same mapping; private runtime paths
are unchanged. See [architecture](ARCHITECTURE.md).

Pure native cloud/LM Studio, Qwen ASR (`vulkan`, `native-cuda`, `native-cpu`) and
Drama 3 profiles need no Python at setup or runtime. Faster Whisper (`cpu`, legacy
`cuda`), ROCm and Silero remain optional Python engines. No weights or recordings
are bundled. Native model setup checks pinned HTTPS metadata, sizes and SHA256;
Drama 3's large model needs explicit consent. Fresh reference inputs must be PCM16
mono/stereo WAVs, 1–30 seconds. Copies preserve sample rate and channels.
