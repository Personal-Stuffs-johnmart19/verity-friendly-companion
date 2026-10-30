#!/usr/bin/env bash
# Source checkout entry point; installer implementation lives under setup/linux.
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
exec bash "$root/setup/linux/setup.sh" "$@"
