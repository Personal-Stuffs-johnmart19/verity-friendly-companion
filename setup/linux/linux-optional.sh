#!/usr/bin/env bash
# Advanced only. Every model/package path is explicit; nothing is inferred from GPU availability.
vfc_optional_check() {
  local root=$1 voice=$1/verity-service/voice-config.json stt tts python environment field file
  stt=$(vfc_helper get "$voice" stt_engine); tts=$(vfc_helper get "$voice" provider)
  [[ $VFC_STT == keep || $VFC_STT == "$stt" ]] || vfc_die 'Selected STT backend is not active/prepared in voice-config.'
  [[ $VFC_TTS == keep || $VFC_TTS == "$tts" ]] || vfc_die 'Selected TTS backend is not active/prepared in voice-config.'
  case $stt in
    native-cpu|vulkan|native-cuda)
      for field in stt_native_executable stt_native_model; do file=$(vfc_helper get "$voice" "$field"); [[ -n $file && -f $file && -s $file ]] || vfc_die "Native prerequisite missing: $field"; done;;
  esac
  if [[ $tts == drama3 ]]; then
    file=$(vfc_helper get "$voice" drama3_directory); [[ -f $file/server.json && -f $file/voices/reference-text.json && -f $file/voices/verity-ru.wav && -f $file/voices/verity-en.wav ]] || vfc_die 'Drama3 configuration/reference assets missing.'
  fi
  python=$(vfc_helper get "$voice" python_executable)
  if [[ $stt == cpu || $stt == faster-whisper || $stt == cuda || $stt == rocm || $tts == silero || -n $python ]]; then
    [[ -n $python && -x $python ]] || vfc_die 'Prepared Python executable missing.'
    environment=$(dirname -- "$(dirname -- "$python")")
    "$python" -B "$root/verity-service/python_environment.py" --venv-root "$environment" --inspect >/dev/null || vfc_die 'Prepared isolated Python environment is invalid.'
  fi
  vfc_note 'PASS optional configured executable/environment paths. Package availability, GPU drivers and model inference were not tested.'
}
vfc_prepare_uv() {
  local triple sha name archive version
  if [[ -n $VFC_UV ]]; then [[ -x $VFC_UV ]] || vfc_die 'Selected uv executable is unavailable.'; return; fi
  case $(uname -m) in
    x86_64|amd64) triple=x86_64-unknown-linux-gnu; sha=f29c3e94ffabd7e6f3ba521dba169a9b7ec12100bf3257087f0f24f6123f3645;;
    aarch64|arm64) triple=aarch64-unknown-linux-gnu; sha=229e194255c7958d86d56e8790d969a6f74736592133f0dfd0076f754c642c1d;;
    *) vfc_die 'No pinned uv/Python bootstrap for this Linux architecture; supply an existing isolated environment/toolchain.';;
  esac
  VFC_UV=$VFC_CACHE/uv-0.13.6/uv-$triple/uv
  if [[ ! -x $VFC_UV ]]; then
    name=uv-$triple.tar.gz; archive=$(vfc_download "https://github.com/astral-sh/uv/releases/download/0.13.6/$name" "$name" "$sha")
    vfc_extract "$archive" "$VFC_CACHE/uv-0.13.6"
  fi
  [[ -x $VFC_UV ]] || vfc_die 'Pinned uv executable missing.'
  version=$("$VFC_UV" --version); [[ $version == 'uv 0.13.6'* ]] || vfc_die 'Pinned uv bootstrap failed.'
}
vfc_native_runtime() {
  local backend=$1 runtime=$2 name sha archive
  local -a executables=()
  [[ $(uname -m) == x86_64 || $(uname -m) == amd64 ]] || vfc_die 'audio.cpp v0.9.3 has no pinned Linux ARM64 binary here. Use a framework CPU backend or supply a separately maintained native runtime manually.'
  case $backend in
    cpu) name=audio-v0.9.3-bin-ubuntu-x64-cpu-portable.tar.gz; sha=02156f7a7bd9924902cec26be35087d3806c7e348be344b1ec28cac0c1af3929;;
    vulkan) name=audio-v0.9.3-bin-ubuntu-x64-vulkan-portable.tar.gz; sha=10ac6b5cb72bc9266e5a600407dbcc2a3eaf87139a3e01c064bf3035d11f81b8;;
    *) vfc_die 'A general-purpose pinned native CUDA Linux binary is unavailable: upstream v0.9.3 offers a Colab-specific build only. Select framework cuda, native-cpu or vulkan; no silent backend substitution.';;
  esac
  if [[ -e $runtime ]]; then vfc_helper native-check "$runtime" "$backend" "$sha"; return; fi
  if [[ -n $VFC_NATIVE_ARCHIVE ]]; then
    [[ $(vfc_hash "$VFC_NATIVE_ARCHIVE") == "$sha" ]] || vfc_die 'Explicit native archive does not match the selected pinned Linux/backend release.'
    archive=$VFC_NATIVE_ARCHIVE
  else archive=$(vfc_download "https://github.com/0xShug0/audio.cpp/releases/download/v0.9.3/$name" "$name" "$sha"); fi
  vfc_extract "$archive" "$runtime"
  mapfile -d '' -t executables < <(find "$runtime" -type f -name audiocpp_server -print0)
  (( ${#executables[@]} == 1 )) || vfc_die 'Native archive must contain exactly one audiocpp_server.'
  vfc_helper native-record "$runtime" "$backend" "$sha" "${executables[0]}"
  vfc_helper native-check "$runtime" "$backend" "$sha"
}
vfc_model() {
  local directory=$1 repo=$2 revision=$3 name=$4 metadata identity sha bytes file partial available answer
  [[ $revision =~ ^[0-9a-f]{40}$ && $name != /* && $name != *..* && $name != *$'\n'* ]] || vfc_die 'Invalid pinned model identity.'
  file=$directory/$name
  if [[ -e $file ]]; then vfc_helper model-check "$directory" "$repo" "$revision" "$name"; return; fi
  (( VFC_DOWNLOAD_MODELS )) || return 3
  (( ! VFC_OFFLINE )) || vfc_die "Offline model missing: $file"
  vfc_require curl; vfc_no_symlink_path "$file"; mkdir -p -- "$directory"
  metadata=$(mktemp "$directory/.model-metadata.XXXXXXXX")
  curl --fail --silent --show-error --location --proto '=https' --proto-redir '=https' --max-filesize 8388608 --connect-timeout 20 --max-time 90 --output "$metadata" -- "https://huggingface.co/api/models/$repo/revision/$revision?blobs=true" || vfc_die 'Pinned model metadata unavailable.'
  identity=$(vfc_helper model-meta "$metadata" "$name" "$revision"); rm -f -- "$metadata"; read -r sha bytes <<< "$identity"
  [[ $sha =~ ^[0-9a-f]{64}$ && $bytes =~ ^[0-9]+$ ]] || vfc_die 'Model integrity metadata missing.'
  vfc_note "Selected model $repo/$name: $bytes bytes; revision $revision. Preparation only, no inference."
  if (( bytes > 2147483648 && ! VFC_LARGE_MODELS )); then
    if (( ! VFC_NONINTERACTIVE )) && [[ -t 0 ]]; then printf 'Download this multi-GiB model? [y/N]: ' >&2; read -r answer; [[ $answer == [yY] ]] || vfc_die 'Large model download declined; active settings preserved.'
    else vfc_die 'Model exceeds 2 GiB; explicit --download-large-models is required.'; fi
  fi
  available=$(df -Pk -- "$directory" | awk 'NR==2 {print $4}')
  [[ $available =~ ^[0-9]+$ ]] && (( available*1024 >= bytes*2 )) || vfc_die 'Insufficient free space for verified model preparation.'
  mkdir -p -- "$(dirname -- "$file")"; partial=$(mktemp "$(dirname -- "$file")/.model-download.XXXXXXXX")
  curl --fail --silent --show-error --location --proto '=https' --proto-redir '=https' --connect-timeout 20 --max-time 86400 --output "$partial" -- "https://huggingface.co/$repo/resolve/$revision/$name?download=true" || { rm -f -- "$partial"; vfc_die 'Model download failed; existing files preserved.'; }
  [[ $(stat -c '%s' -- "$partial") == "$bytes" && $(vfc_hash "$partial") == "$sha" ]] || { rm -f -- "$partial"; vfc_die 'Model checksum/size mismatch.'; }
  # Hardlink publication is no-clobber even if another process creates the target.
  ln -- "$partial" "$file" || vfc_die 'Model destination appeared during download; it was not overwritten.'
  rm -f -- "$partial"; vfc_helper model-record "$directory" "$repo" "$revision" "$name" "$sha" "$bytes"
  printf '%s\n' "$file"
}
vfc_python_files() {
  local service=$1 source directory name
  if [[ -d $VFC_ROOT/setup/python ]]; then
    for directory in "$VFC_ROOT/setup/python" "$VFC_ROOT/runtime/python"; do
      for source in "$directory"/*.py "$directory"/requirements*.txt; do [[ ! -f $source ]] || vfc_copy_public "$(basename -- "$source")" "$service"; done
    done
  else
    for source in "$VFC_ROOT"/*.py "$VFC_ROOT"/requirements*.txt; do [[ ! -f $source ]] || vfc_copy_public "$(basename -- "$source")" "$service"; done
  fi
  for name in python_environment.py python_dependencies.py backend_setup.py voice_server.py; do [[ -f $service/$name ]] || vfc_die "Optional Python code missing: $name"; done
}
vfc_python_environment() {
  local service=$1 stt=$2 tts=$3 selection environment mode=$VFC_DEPENDENCY_MODE
  local -a arguments uv_arguments
  vfc_prepare_uv; vfc_python_files "$service"
  environment=${VFC_VENV:-$service/.venv}; vfc_no_symlink_path "$environment"
  if [[ -x $environment/bin/python && $environment != "$service/.venv" && $VFC_USE_EXISTING_VENV != 1 ]]; then vfc_die 'An existing custom venv requires --use-existing-venv; no packages were changed.'; fi
  if (( VFC_USE_EXISTING_VENV )); then [[ -f $environment/pyvenv.cfg && -x $environment/bin/python ]] || vfc_die 'Explicit reuse requires an existing isolated Python venv (3.10+).'
  elif [[ ! -x $environment/bin/python ]]; then
    [[ ! -e $environment || -z $(find "$environment" -mindepth 1 -maxdepth 1 -print -quit) ]] || vfc_die 'Venv destination is not empty; select --use-existing-venv or a new path.'
    selection=${VFC_PYTHON:-3.12}; uv_arguments=(--no-config); (( ! VFC_OFFLINE )) || uv_arguments+=(--offline)
    UV_CACHE_DIR=$VFC_CACHE/uv-cache UV_PYTHON_INSTALL_DIR=$VFC_CACHE/python UV_PYTHON_BIN_DIR=$VFC_CACHE/python-bin "$VFC_UV" "${uv_arguments[@]}" venv --python "$selection" "$environment" --no-progress
  fi
  VFC_SELECTED_PYTHON=$environment/bin/python
  "$VFC_SELECTED_PYTHON" -B "$service/python_environment.py" --venv-root "$environment" --inspect >/dev/null || vfc_die 'Selected venv is not isolated Python 3.10+; no packages were changed.'
  if (( VFC_USE_EXISTING_VENV && ! VFC_INSTALL_PYTHON_PACKAGES )); then mode=existing; fi
  if [[ $mode == auto ]]; then mode=compatible; fi
  if [[ $mode == locked ]]; then vfc_note 'Explicit locked mode requires the hashed CPython 3.12 baseline. Linux wheel resolution has not been validated here; resolution failure never falls back silently.'; fi
  if [[ $mode != existing ]]; then vfc_not_running "$environment"; fi
  arguments=(--venv-root "$environment" --service "$service" --uv "$VFC_UV" --mode "$mode" --stt "$stt" --tts "$tts")
  (( ! VFC_USE_EXISTING_VENV )) || arguments+=(--reused)
  (( ! VFC_INSTALL_PYTHON_PACKAGES )) || arguments+=(--install-packages)
  (( ! VFC_OFFLINE )) || arguments+=(--offline)
  (( ! VFC_CPU_FALLBACK )) || arguments+=(--cpu-fallback)
  UV_CACHE_DIR=$VFC_CACHE/uv-cache "$VFC_SELECTED_PYTHON" -B "$service/python_environment.py" "${arguments[@]}" >/dev/null || vfc_die 'Selected environment needs dependency repair. Reused environments remain unchanged unless package installation was explicitly allowed.'
  VFC_EFFECTIVE_PYTHON_MODE=$mode
}
vfc_optional() (
  set -euo pipefail
  local root=$1 service=$1/verity-service voice=$1/verity-service/voice-config.json previous committed=0 stt tts python_stt python_tts native='' model='' cpu='' drama='' drama_exe='' installed='' name
  local -a backend_arguments
  previous=$(mktemp "$service/.previous-audio-settings.XXXXXXXX"); cp -- "$voice" "$previous"
  trap 'if (( ! committed )); then mv -fT -- "$previous" "$voice"; else rm -f -- "$previous"; fi' EXIT
  stt=$VFC_STT; tts=$VFC_TTS
  [[ $stt != keep ]] || stt=$(vfc_helper get "$voice" stt_engine)
  [[ $tts != keep ]] || tts=$(vfc_helper get "$voice" provider)
  [[ -n $stt ]] || stt=disabled; [[ -n $tts ]] || tts=google
  case $stt in disabled|google|native-cpu|vulkan|native-cuda|cpu|faster-whisper|cuda|rocm) ;; *) vfc_die 'Unsupported selected recognition backend.';; esac
  case $tts in disabled|google|silero|drama3) ;; *) vfc_die 'Unsupported selected speech backend.';; esac
  if [[ $VFC_STT == native-cuda ]]; then vfc_die 'Linux native-cuda automatic installation is unavailable: upstream binary is Colab-specific. Select framework cuda or Vulkan; existing settings preserved.'; fi
  VFC_SELECTED_PYTHON=''
  if [[ $VFC_STT == cpu || $VFC_STT == cuda || $VFC_STT == rocm || $VFC_TTS == silero || -n $VFC_VENV || -n $VFC_PYTHON || $VFC_USE_EXISTING_VENV == 1 ]]; then
    python_stt=$stt; case $python_stt in native-cpu|native-cuda|vulkan|google) python_stt=disabled;; esac
    python_tts=$tts; [[ $python_tts != drama3 ]] || python_tts=google
    vfc_python_environment "$service" "$python_stt" "$python_tts"
    backend_arguments=(--prepare --packages-only --uv "$VFC_UV" --stt "$python_stt" --tts "$python_tts" --dependency-mode "$VFC_EFFECTIVE_PYTHON_MODE" --cpu-fallback "$([[ $VFC_CPU_FALLBACK == 1 ]] && printf on || printf off)")
    (( ! VFC_OFFLINE )) || backend_arguments+=(--offline)
    UV_CACHE_DIR=$VFC_CACHE/uv-cache "$VFC_SELECTED_PYTHON" -B "$service/backend_setup.py" "${backend_arguments[@]}"
    vfc_helper set "$voice" python_executable "$VFC_SELECTED_PYTHON"
    if (( VFC_DOWNLOAD_MODELS )); then
      backend_arguments=(--prepare --uv "$VFC_UV" --stt "$VFC_STT" --tts "$VFC_TTS" --dependency-mode "$VFC_EFFECTIVE_PYTHON_MODE" --cpu-fallback "$([[ $VFC_CPU_FALLBACK == 1 ]] && printf on || printf off)")
      (( ! VFC_OFFLINE )) || backend_arguments+=(--offline); (( ! VFC_LARGE_MODELS )) || backend_arguments+=(--allow-large)
      [[ -z $VFC_REFERENCE_CONFIG ]] || backend_arguments+=(--reference-config "$VFC_REFERENCE_CONFIG")
      UV_CACHE_DIR=$VFC_CACHE/uv-cache "$VFC_SELECTED_PYTHON" -B "$service/backend_setup.py" "${backend_arguments[@]}"
      case $stt in cpu|faster-whisper|cuda|rocm) installed=$stt;; esac
      [[ $tts != silero ]] || installed=${installed:+$installed,}silero
    fi
  fi
  if [[ $VFC_STT != keep && ( $stt == native-cpu || $stt == vulkan ) ]]; then
    name=cpu; [[ $stt != vulkan ]] || name=vulkan
    native=$(vfc_native_runtime "$name" "$service/native-asr/$name/runtime")
    if (( VFC_DOWNLOAD_MODELS )); then
      model=$(vfc_model "$service/models/native-asr" audio-cpp/audio.cpp-gguf 874fcb41ececda20bcbc0782d6b4ea7f7d069ea8 Qwen3-ASR-0.6B-GGUF/qwen3-asr-0.6b-f16.gguf)
      if (( VFC_CPU_FALLBACK )) && [[ $stt != native-cpu ]]; then cpu=$(vfc_native_runtime cpu "$service/native-asr/cpu/runtime"); fi
      vfc_helper set "$voice" stt_engine "$stt" stt_native_backend "$name" stt_native_primary_backend "$name" stt_native_executable "$native" stt_native_model "$model"
      [[ -z $cpu ]] || vfc_helper set "$voice" stt_native_cpu_executable "$cpu"
      vfc_helper set-bool "$voice" stt_cpu_fallback "$([[ $VFC_CPU_FALLBACK == 1 ]] && printf true || printf false)"
      installed=${installed:+$installed,}$stt
    fi
  elif [[ $stt == google || $stt == disabled ]]; then vfc_helper set "$voice" stt_engine "$stt"; fi
  if [[ $VFC_TTS == drama3 ]]; then
    drama=$root/verity-tts; drama_exe=$(vfc_native_runtime vulkan "$drama/runtime")
    if (( VFC_DOWNLOAD_MODELS )); then
      if [[ ! -f $drama/server.json ]]; then model=$(vfc_model "$drama/models/drama3-q4" Jmica/Drama-3-GGUF 17a5b40c5dc2c2be986f5371a7e650108b828e29 drama-3-backbone-8bit-experts-Q4_K_S.gguf); fi
      vfc_helper references "$drama" "$VFC_REFERENCE_CONFIG"
      vfc_helper drama "$drama" "$drama_exe" "$model"
      vfc_helper set "$voice" provider drama3 drama3_directory "$drama" drama3_executable "$drama_exe"
      installed=${installed:+$installed,}drama3
    fi
  elif [[ $tts == google || $tts == disabled ]]; then vfc_helper set "$voice" provider "$tts"; fi
  vfc_helper bootstrap "$root" advanced "$VFC_JAVA_HOME" "$installed" "$VFC_SELECTED_PYTHON" server
  # Read by the EXIT trap after this function returns.
  # shellcheck disable=SC2034
  committed=1
  if (( ! VFC_DOWNLOAD_MODELS )); then vfc_note 'Model downloads deferred. Existing local audio settings were retained; selected local backends are not declared ready. Rerun with --download-models to activate them.'; fi
  vfc_note 'Advanced preparation finished. No model was loaded or warmed; driver/model quality remains untested.'
)
