#!/usr/bin/env bash
set -euo pipefail
umask 077
launcher_directory=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
helper=$launcher_directory/linux-launch.sh
[[ -f $helper ]] || helper=$launcher_directory/../setup/linux/linux-launch.sh
source "$helper"
vfc_launch_init "$@"
arguments=$(vfc_helper server-args "$VFC_SERVER_ROOT" '')
vfc_no_symlink_path "$VFC_SERVER_ROOT/user_jvm_args.txt"
trap vfc_launch_cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
vfc_adapter_start
if (( VFC_CHECK )); then vfc_note 'PASS prepared NeoForge/Minecraft server arguments. No server started.'; exit 0; fi
cd -- "$VFC_SERVER_ROOT"
java_arguments=()
[[ ! -f user_jvm_args.txt ]] || java_arguments+=('@user_jvm_args.txt')
"$VFC_JAVA_HOME/bin/java" "${java_arguments[@]}" "@$arguments" nogui &
VFC_GAME_PID=$!
wait "$VFC_GAME_PID"
VFC_GAME_PID=''
