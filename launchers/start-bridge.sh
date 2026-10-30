#!/usr/bin/env bash
# Optional advanced adapter only. Cloud/basic Minecraft needs no adapter.
set -euo pipefail
umask 077
launcher_directory=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
helper=$launcher_directory/linux-launch.sh
[[ -f $helper ]] || helper=$launcher_directory/../setup/linux/linux-launch.sh
source "$helper"
vfc_launch_init "$@"
trap vfc_launch_cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
vfc_adapter_start
if [[ -n $VFC_ADAPTER_PID ]]; then wait "$VFC_ADAPTER_PID"; VFC_ADAPTER_PID=''; fi
