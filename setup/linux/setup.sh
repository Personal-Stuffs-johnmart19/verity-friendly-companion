#!/usr/bin/env bash
# Linux source/bundled installer. Windows users use setup.cmd.
set -euo pipefail
umask 077
VFC_ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
if [[ $VFC_ROOT == */setup/linux && -f $VFC_ROOT/../../build.gradle ]]; then VFC_ROOT=$(cd -- "$VFC_ROOT/../.." && pwd -P); fi
VFC_HELPERS=$VFC_ROOT/setup/linux
[[ -f $VFC_HELPERS/linux-common.sh ]] || VFC_HELPERS=$VFC_ROOT
[[ -f $VFC_HELPERS/linux-common.sh ]] || { printf 'Missing Linux setup helpers. Use the complete source/setup package.\n' >&2; exit 1; }
# shellcheck source=setup/linux/linux-common.sh
source "$VFC_HELPERS/linux-common.sh"
# shellcheck source=setup/linux/linux-bootstrap.sh
source "$VFC_HELPERS/linux-bootstrap.sh"
# shellcheck source=setup/linux/linux-detect.sh
source "$VFC_HELPERS/linux-detect.sh"
VFC_HELPER=$VFC_HELPERS/LinuxSetup.java
VFC_JAVA_HOME=''; VFC_CACHE=${XDG_CACHE_HOME:-$HOME/.cache}/verity-friendly-companion
VFC_SERVER_ROOT=''; VFC_CLIENT_ROOT=''; VFC_MINECRAFT_ROOT=''; VFC_ADDON=''; VFC_EXPORT=''; VFC_LOADER=''
VFC_PROFILE=basic; VFC_STT=keep; VFC_TTS=keep; VFC_PYTHON=''; VFC_VENV=''; VFC_DEPENDENCY_MODE=auto
VFC_DRY_RUN=0; VFC_CHECK=0; VFC_OFFLINE=0; VFC_PORTABLE_JDK=0; VFC_BUILD_ONLY=0; VFC_NONINTERACTIVE=0; VFC_PACKAGE=0
VFC_DOWNLOAD_MODELS=0; VFC_LARGE_MODELS=0; VFC_USE_EXISTING_VENV=0; VFC_INSTALL_PYTHON_PACKAGES=0; VFC_LIST=0
VFC_CPU_FALLBACK=1; VFC_REFERENCE_CONFIG=''; VFC_UV=''; VFC_NATIVE_ARCHIVE=''
vfc_usage() {
  cat <<'EOF'
Usage: bash setup.sh [options]
No arguments: interactive basic-JAR/advanced-backend wizard (terminal only).
  --build-only                       Build source JAR; never change a game installation
  --addon-jar PATH                   Use a supplied/bundled addon instead of compiling
  --export-directory PATH            Copy standalone addon JAR here
  --server-root PATH                 Install/repair a compatible NeoForge server
  --standalone-server PATH           Alias for an explicitly selected new server root
  --client-root PATH                 Minecraft/CurseForge instance containing mods/
  --minecraft-root PATH              Initialized launcher root with versions/ and libraries/
  --loader-version 21.1.N            Explicit compatible loader (N >= 250); no silent upgrade
  --java-home PATH                   Complete working Java 21 JDK; bundled JREs are rejected
  --cache-directory PATH             Private user-owned Linux cache; never system installs
  --profile basic|advanced           Basic requires no Python, models or native speech engine
  --stt keep|disabled|google|native-cpu|vulkan|native-cuda|cpu|cuda|rocm
  --tts keep|disabled|google|silero|drama3
  --venv-path PATH --use-existing-venv --python-executable PATH
  --install-python-packages --dependency-mode auto|locked|compatible|existing
                                     Linux auto uses the bounded compatible wheel resolver
  --download-models --download-large-models --reference-config PATH
  --no-cpu-fallback                  Do not prepare the optional native CPU fallback
  --offline --non-interactive --use-portable-jdk
  --dry-run | --plan                 JSON plan; no downloads/writes/process installation
  --check | --check-only             Read-only actual prerequisites/dependency checks
  --list-candidates                  Read-only bounded standard-path discovery
  --package-release                 Package source using tools/release/package-release.sh
EOF
}
had_arguments=$#
while (( $# )); do
  case $1 in
    --help|-h) vfc_usage; exit 0;;
    --build-only) VFC_BUILD_ONLY=1;; --non-interactive) VFC_NONINTERACTIVE=1;;
    --offline) VFC_OFFLINE=1;; --dry-run|--plan) VFC_DRY_RUN=1;; --check|--check-only) VFC_CHECK=1;;
    --list-candidates) VFC_LIST=1;; --use-portable-jdk) VFC_PORTABLE_JDK=1;; --package-release) VFC_PACKAGE=1;;
    --download-models) VFC_DOWNLOAD_MODELS=1;; --skip-model-downloads) VFC_DOWNLOAD_MODELS=0;; --download-large-models) VFC_LARGE_MODELS=1;;
    --use-existing-venv) VFC_USE_EXISTING_VENV=1;; --install-python-packages) VFC_INSTALL_PYTHON_PACKAGES=1;; --no-cpu-fallback) VFC_CPU_FALLBACK=0;;
    --server-root|--standalone-server|--client-root|--minecraft-root|--java-home|--cache-directory|--addon-jar|--export-directory|--profile|--stt|--tts|--venv-path|--python-executable|--dependency-mode|--reference-config|--loader-version|--uv-executable|--native-archive)
      option=$1; shift; (( $# )) || vfc_die "Missing value for $option"
      case $option in
        --server-root|--standalone-server) VFC_SERVER_ROOT=$1;; --client-root) VFC_CLIENT_ROOT=$1;; --minecraft-root) VFC_MINECRAFT_ROOT=$1;;
        --java-home) VFC_JAVA_HOME=$1;; --cache-directory) VFC_CACHE=$1;; --addon-jar) VFC_ADDON=$1;; --export-directory) VFC_EXPORT=$1;;
        --profile) VFC_PROFILE=$1;; --stt) VFC_STT=$1;; --tts) VFC_TTS=$1;; --venv-path) VFC_VENV=$1;; --python-executable) VFC_PYTHON=$1;;
        --dependency-mode) VFC_DEPENDENCY_MODE=$1;; --reference-config) VFC_REFERENCE_CONFIG=$1;; --loader-version) VFC_LOADER=$1;; --uv-executable) VFC_UV=$1;; --native-archive) VFC_NATIVE_ARCHIVE=$1;;
      esac;;
    *) vfc_die "Unknown option: $1. Use --help.";;
  esac
  shift
done
[[ $(uname -s) == Linux ]] || vfc_die 'This entry point is for Linux. On Windows run setup.cmd.'
for tool in realpath readlink timeout stat id sha256sum grep sed dirname mktemp find tar unzip cmp date awk df ln cp mv; do vfc_require "$tool"; done
[[ $VFC_PROFILE == basic || $VFC_PROFILE == advanced ]] || vfc_die 'Profile must be basic or advanced.'
case $VFC_STT in keep|disabled|google|native-cpu|vulkan|native-cuda|cpu|cuda|rocm) ;; *) vfc_die 'Unsupported STT backend.';; esac
case $VFC_TTS in keep|disabled|google|silero|drama3) ;; *) vfc_die 'Unsupported TTS backend.';; esac
case $VFC_DEPENDENCY_MODE in auto|locked|compatible|existing) ;; *) vfc_die 'Unsupported Python dependency mode.';; esac
[[ -z $VFC_SERVER_ROOT || -z $VFC_CLIENT_ROOT ]] || vfc_die 'Select one target per invocation.'
[[ -z $VFC_LOADER || $VFC_LOADER =~ ^21\.1\.[0-9]{3,6}$ ]] || vfc_die 'Invalid loader version.'
VFC_CACHE=$(vfc_path "$VFC_CACHE")
for variable in VFC_SERVER_ROOT VFC_CLIENT_ROOT VFC_MINECRAFT_ROOT VFC_JAVA_HOME VFC_ADDON VFC_EXPORT VFC_VENV VFC_REFERENCE_CONFIG VFC_NATIVE_ARCHIVE VFC_UV; do
  if [[ -n ${!variable} ]]; then normalized=$(vfc_path "${!variable}") || exit 1; printf -v "$variable" '%s' "$normalized"; fi
done
if [[ -d $VFC_CACHE ]]; then vfc_cache_trust "$VFC_CACHE"; fi
vfc_find_jdk
vfc_detect
if (( VFC_LIST )); then vfc_show_candidates; exit 0; fi
if (( ! had_arguments )); then
  [[ -t 0 && -t 1 ]] || { vfc_usage; vfc_die 'No interactive terminal. Pass --build-only or an explicit install target with --non-interactive.'; }
  vfc_show_candidates
  printf '\n1) Basic: JAR/cloud only, no Python or speech models\n2) Advanced: explicitly select optional local speech backends\nProfile [1]: ' >&2
  read -r answer; [[ $answer != 2 ]] || VFC_PROFILE=advanced
  printf '\n1) Standalone addon JAR\n2) Install an existing client/server candidate\n3) New standalone NeoForge server\nAction [1]: ' >&2
  read -r answer
  case $answer in
    2) printf 'Candidate number: ' >&2; read -r answer; [[ $answer =~ ^[0-9]+$ ]] && (( answer>=1 && answer<=${#VFC_CANDIDATE_ROOTS[@]} )) || vfc_die 'Select a displayed candidate.'; index=$((answer-1)); if [[ ${VFC_CANDIDATE_KINDS[index]} == server ]]; then VFC_SERVER_ROOT=${VFC_CANDIDATE_ROOTS[index]}; else VFC_CLIENT_ROOT=${VFC_CANDIDATE_ROOTS[index]}; fi;;
    3) printf 'New standalone server directory: ' >&2; read -r answer; VFC_SERVER_ROOT=$(vfc_path "$answer");;
    ''|1) VFC_BUILD_ONLY=1; printf 'Optional standalone JAR export directory (Enter: dist/): ' >&2; read -r answer; [[ -z $answer ]] || VFC_EXPORT=$(vfc_path "$answer");;
    *) vfc_die 'Invalid action.';;
  esac
  if [[ $VFC_PROFILE == advanced ]]; then
    [[ -n $VFC_SERVER_ROOT ]] || vfc_die 'Advanced speech backends are server-only. Select a server target.'
    printf 'STT [disabled/google/native-cpu/vulkan/native-cuda/cpu/cuda/rocm, default keep]: ' >&2; read -r answer; VFC_STT=${answer:-keep}
    printf 'TTS [google/silero/drama3/disabled, default keep]: ' >&2; read -r answer; VFC_TTS=${answer:-keep}
    environment_default=0
    if [[ $VFC_STT == cpu || $VFC_STT == cuda || $VFC_STT == rocm || $VFC_TTS == silero ]]; then environment_default=1; fi
    printf 'Python environment: 0) none (native/cloud only), 1) managed new, 2) custom new, 3) reuse isolated venv [%s]: ' "$environment_default" >&2; read -r answer
    case ${answer:-$environment_default} in
      0) [[ $environment_default == 0 ]] || vfc_die 'The selected framework backend requires a Python environment.';; 1) VFC_VENV=$VFC_SERVER_ROOT/verity-service/.venv;;
      2|3) [[ $answer != 3 ]] || VFC_USE_EXISTING_VENV=1; printf 'Isolated venv directory: ' >&2; read -r answer; VFC_VENV=$(vfc_path "$answer");;
      *) vfc_die 'Invalid Python environment choice.';;
    esac
    if (( VFC_USE_EXISTING_VENV )); then printf 'Allow package changes in this reused environment? [y/N]: ' >&2; read -r answer; [[ $answer != [yY] ]] || VFC_INSTALL_PYTHON_PACKAGES=1; fi
    printf 'Download selected models now? Large downloads may be several GiB [y/N]: ' >&2; read -r answer; [[ $answer != [yY] ]] || VFC_DOWNLOAD_MODELS=1
  fi
  vfc_note "Plan: profile=$VFC_PROFILE; server=${VFC_SERVER_ROOT:-none}; client=${VFC_CLIENT_ROOT:-none}; export=${VFC_EXPORT:-dist/}; STT=$VFC_STT; TTS=$VFC_TTS; venv=${VFC_VENV:-none}; model_downloads=$VFC_DOWNLOAD_MODELS. No game or model will start."
  printf 'Proceed with this installation/build plan? [y/N]: ' >&2; read -r answer; [[ $answer == [yY] ]] || vfc_die 'Cancelled; no files or downloads changed.'
fi
case $VFC_STT in keep|disabled|google|native-cpu|vulkan|native-cuda|cpu|cuda|rocm) ;; *) vfc_die 'Unsupported STT backend.';; esac
case $VFC_TTS in keep|disabled|google|silero|drama3) ;; *) vfc_die 'Unsupported TTS backend.';; esac
if [[ $VFC_STT == native-cuda ]]; then vfc_die 'No general-purpose pinned native CUDA Linux binary is available (upstream v0.9.3 is Colab-specific). Choose framework cuda, native-cpu or vulkan.'; fi
if [[ $(uname -m) != x86_64 && $(uname -m) != amd64 && ( $VFC_STT == native-cpu || $VFC_STT == vulkan || $VFC_TTS == drama3 ) ]]; then vfc_die 'No pinned audio.cpp Linux ARM64 binary is available; select a supported framework backend or maintain a native build manually.'; fi
if [[ $VFC_PROFILE == basic ]]; then
  [[ $VFC_STT == keep && $VFC_TTS == keep && -z $VFC_VENV && -z $VFC_PYTHON && -z $VFC_REFERENCE_CONFIG && -z $VFC_UV && -z $VFC_NATIVE_ARCHIVE && $VFC_USE_EXISTING_VENV == 0 && $VFC_DEPENDENCY_MODE == auto && $VFC_INSTALL_PYTHON_PACKAGES == 0 && $VFC_DOWNLOAD_MODELS == 0 && $VFC_LARGE_MODELS == 0 && $VFC_CPU_FALLBACK == 1 ]] || vfc_die 'Optional backend choices require --profile advanced.'
else
  [[ -n $VFC_SERVER_ROOT && -z $VFC_CLIENT_ROOT && -z $VFC_EXPORT && $VFC_BUILD_ONLY == 0 ]] || vfc_die 'Advanced backends require a server target; client/export/build-only profiles are basic.'
  [[ -f $VFC_ROOT/runtime/python/voice_server.py || -f $VFC_ROOT/voice_server.py ]] || vfc_die 'Advanced setup requires the complete optional server package, not the client bundle.'
  if (( VFC_USE_EXISTING_VENV )); then [[ -n $VFC_VENV ]] || vfc_die '--use-existing-venv requires an explicit --venv-path.'; fi
fi
if (( VFC_BUILD_ONLY )); then [[ -z $VFC_SERVER_ROOT && -z $VFC_CLIENT_ROOT ]] || vfc_die '--build-only never installs into a game target.'; fi
if (( VFC_PACKAGE )) && [[ ! -f $VFC_ROOT/tools/release/package-release.sh ]]; then vfc_die 'Packaging requires the complete source checkout; setup archives contain only runtime installation helpers.'; fi
if (( VFC_DRY_RUN )); then vfc_plan; exit 0; fi
if (( VFC_CHECK )); then [[ -n $VFC_JAVA_HOME ]] || vfc_die 'Read-only check needs an existing complete Java 21 JDK; no tool will be downloaded.'
else vfc_prepare_jdk; fi
# shellcheck source=setup/linux/linux-install.sh
source "$VFC_HELPERS/linux-install.sh"
vfc_select_artifact
if (( VFC_CHECK )); then vfc_check_installation; exit 0; fi
if [[ -n $VFC_SERVER_ROOT ]]; then vfc_install_server
elif [[ -n $VFC_CLIENT_ROOT ]]; then vfc_install_client
else vfc_note "Standalone JAR ready: $VFC_ADDON"; fi
if [[ -n $VFC_EXPORT ]]; then
  vfc_no_symlink_path "$VFC_EXPORT/$(basename -- "$VFC_ADDON")"
  mkdir -p -- "$VFC_EXPORT"
  vfc_helper export "$VFC_ADDON" "$VFC_EXPORT/$(basename -- "$VFC_ADDON")"
fi
if (( VFC_PACKAGE )); then bash "$VFC_ROOT/tools/release/package-release.sh" --java-home "$VFC_JAVA_HOME"; fi
printf '%s  %s\n' "$(vfc_hash "$VFC_ADDON")" "$VFC_ADDON"
