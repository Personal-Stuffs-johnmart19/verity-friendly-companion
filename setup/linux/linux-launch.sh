#!/usr/bin/env bash
# Shared launcher functions. Never downloads, installs packages or warms a model.
vfc_launch_init() {
  VFC_LAUNCH_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[1]}")" && pwd -P)
  VFC_HELPERS=$VFC_LAUNCH_DIR
  [[ -f $VFC_HELPERS/linux-common.sh ]] || VFC_HELPERS=$VFC_LAUNCH_DIR/../setup/linux
  source "$VFC_HELPERS/linux-common.sh"
  source "$VFC_HELPERS/linux-bootstrap.sh"
  # Variables consumed by sourced common/bootstrap helpers.
  # shellcheck disable=SC2034
  VFC_HELPER=$VFC_HELPERS/LinuxSetup.java
  # shellcheck disable=SC2034
  VFC_SERVER_ROOT=''; VFC_JAVA_HOME=''; VFC_CHECK=0
  # shellcheck disable=SC2034
  VFC_PORTABLE_JDK=0
  # shellcheck disable=SC2034
  VFC_CACHE=${XDG_CACHE_HOME:-$HOME/.cache}/verity-friendly-companion
  while (( $# )); do
    case $1 in
      --server-root|--java-home) option=$1; shift; (( $# )) || vfc_die 'Missing launcher option value.'; if [[ $option == --server-root ]]; then VFC_SERVER_ROOT=$(vfc_path "$1"); else VFC_JAVA_HOME=$(vfc_path "$1"); fi;;
      --check|--dry-run) VFC_CHECK=1;;
      --help|-h) printf 'Usage: bash %s [--server-root PATH] [--java-home JDK21] [--check]\n' "${BASH_SOURCE[1]##*/}"; exit 0;;
      *) vfc_die "Unknown launcher option: $1";;
    esac; shift
  done
  if [[ -z $VFC_SERVER_ROOT && ${VFC_LAUNCH_DIR##*/} == verity-service ]]; then VFC_SERVER_ROOT=$(dirname -- "$VFC_LAUNCH_DIR"); fi
  [[ -n $VFC_SERVER_ROOT && -d $VFC_SERVER_ROOT ]] || vfc_die 'Pass --server-root with the prepared server directory.'
  VFC_SERVICE=$VFC_SERVER_ROOT/verity-service; vfc_no_symlink_path "$VFC_SERVICE"
  if [[ -z $VFC_JAVA_HOME && -f $VFC_SERVICE/java-home.txt ]]; then
    vfc_no_symlink_path "$VFC_SERVICE/java-home.txt"
    [[ $(stat -c %s -- "$VFC_SERVICE/java-home.txt") -le 4096 ]] || vfc_die 'Invalid Java home record.'
    IFS= read -r VFC_JAVA_HOME < "$VFC_SERVICE/java-home.txt"
    VFC_JAVA_HOME=$(vfc_path "$VFC_JAVA_HOME")
  fi
  vfc_find_jdk; [[ -n $VFC_JAVA_HOME ]] || vfc_die 'A working Java 21 JDK is required. Run setup first; launchers never download tools.'
  VFC_ADAPTER_PID=''; VFC_GAME_PID=''
}
vfc_owned_stop() {
  local pid=$1 job
  [[ -n $pid ]] || return 0
  while read -r job; do if [[ $job == "$pid" ]]; then kill -TERM -- "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; return; fi; done < <(jobs -pr)
}
vfc_launch_cleanup() { vfc_owned_stop "$VFC_GAME_PID"; vfc_owned_stop "$VFC_ADAPTER_PID"; }
vfc_adapter_status() {
  local token
  IFS= read -r token < "$VFC_TOKEN_FILE"
  printf 'Authorization: Bearer %s\n' "$token" |
    curl --silent --show-error --fail --noproxy '*' --proxy '' --max-redirs 0 --connect-timeout 1 --max-time 3 --max-filesize 1048576 \
      --header @- --header 'Content-Type: application/json' --data '{}' --output "$VFC_STATUS_FILE" -- "http://127.0.0.1:$VFC_ADAPTER_PORT/audio/status" 2>/dev/null &&
    vfc_helper bridge-status "$VFC_STATUS_FILE" >/dev/null 2>&1
}
vfc_adapter_start() {
  local information python environment log attempts
  information=$(vfc_helper bridge-info "$VFC_SERVER_ROOT") || vfc_die 'Optional adapter configuration is unsafe/incomplete; no child was started.'
  [[ -n $information ]] || { vfc_note 'Basic/cloud profile: no Python adapter required.'; return; }
  local -a fields=(); mapfile -t fields <<< "$information"
  (( ${#fields[@]} == 3 )) || vfc_die 'Invalid private adapter metadata.'
  python=${fields[0]}; VFC_ADAPTER_PORT=${fields[1]}; VFC_TOKEN_FILE=${fields[2]}
  environment=$(dirname -- "$(dirname -- "$python")")
  [[ -f $environment/pyvenv.cfg && -f $VFC_SERVICE/python_environment.py && -f $VFC_SERVICE/voice_server.py ]] || vfc_die 'Prepared isolated Python adapter files are missing. Run advanced setup.'
  "$python" -B "$VFC_SERVICE/python_environment.py" --venv-root "$environment" --inspect >/dev/null || vfc_die 'Selected adapter Python is not an isolated compatible venv.'
  if (( VFC_CHECK )); then vfc_note 'PASS advanced idle adapter prerequisites; no child started or model loaded.'; return; fi
  vfc_require curl
  vfc_no_symlink_path "$VFC_SERVICE/logs"; mkdir -p -- "$VFC_SERVICE/logs"
  VFC_STATUS_FILE=$(mktemp "$VFC_SERVICE/logs/.adapter-status.XXXXXXXX")
  if vfc_adapter_status; then rm -f -- "$VFC_STATUS_FILE"; vfc_note 'Using the existing authenticated optional adapter.'; return; fi
  log=$(mktemp "$VFC_SERVICE/logs/adapter.XXXXXXXX.log")
  (cd -- "$VFC_SERVICE" && exec "$python" -B "$VFC_SERVICE/voice_server.py") </dev/null >"$log" 2>&1 &
  VFC_ADAPTER_PID=$!
  for ((attempts=0; attempts<60; attempts++)); do
    if vfc_adapter_status; then rm -f -- "$VFC_STATUS_FILE"; vfc_note 'Optional adapter is idle and ready; no model was warmed.'; return; fi
    if ! kill -0 "$VFC_ADAPTER_PID" 2>/dev/null; then break; fi
    sleep 0.5
  done
  rm -f -- "$VFC_STATUS_FILE"; vfc_owned_stop "$VFC_ADAPTER_PID"; VFC_ADAPTER_PID=''
  vfc_die "Optional adapter did not become ready. Existing listeners were preserved. See $log"
}
