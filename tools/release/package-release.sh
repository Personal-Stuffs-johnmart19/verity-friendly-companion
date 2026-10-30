#!/usr/bin/env bash
# Optional public setup ZIPs; no PowerShell, Python, model or credential inputs.
set -euo pipefail
umask 077
VFC_ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)
helpers=$VFC_ROOT/setup/linux; [[ -f $helpers/linux-common.sh ]] || helpers=$VFC_ROOT
source "$helpers/linux-common.sh"
source "$helpers/linux-bootstrap.sh"
# Variables consumed by sourced common/bootstrap helpers.
# shellcheck disable=SC2034
VFC_HELPER=$helpers/LinuxSetup.java
VFC_JAVA_HOME=''; VFC_ADDON=''; output=$VFC_ROOT/dist
# shellcheck disable=SC2034
VFC_PORTABLE_JDK=0
# shellcheck disable=SC2034
VFC_CACHE=${XDG_CACHE_HOME:-$HOME/.cache}/verity-friendly-companion
while (( $# )); do
  case $1 in
    --java-home|--addon-jar|--output-directory) option=$1; shift; (( $# )) || vfc_die 'Missing package option value.'; case $option in --java-home) VFC_JAVA_HOME=$(vfc_path "$1");; --addon-jar) VFC_ADDON=$(vfc_path "$1");; *) output=$(vfc_path "$1");; esac;;
    --help|-h) printf 'Usage: bash package-release.sh --java-home JDK21 [--addon-jar PATH] [--output-directory PATH]\n'; exit 0;;
    *) vfc_die "Unknown package option: $1";;
  esac; shift
done
[[ -f $VFC_ROOT/src/main/resources/META-INF/neoforge.mods.toml ]] || vfc_die 'Packaging requires the complete source tree. Installed setup ZIPs already contain the addon.'
vfc_find_jdk; [[ -n $VFC_JAVA_HOME ]] || vfc_die 'Packaging requires an existing complete Java 21 JDK.'
version=$(sed -n 's/^version="\([A-Za-z0-9._-]*\)"$/\1/p' "$VFC_ROOT/src/main/resources/META-INF/neoforge.mods.toml")
[[ -n $version ]] || vfc_die 'Missing version metadata.'
VFC_ADDON=${VFC_ADDON:-$VFC_ROOT/dist/verity-friendly-companion-$version.jar}
vfc_no_symlink_path "$VFC_ADDON"; [[ $(vfc_helper addon-version "$VFC_ADDON") == "$version" ]] || vfc_die 'Addon/version mismatch.'
entries=$(unzip -Z1 "$VFC_ADDON")
if grep -Eq '(^|/)(tests?|diagnostics|\.secrets|models|__pycache__)/|^varmite/|(^|/)[^/]*(Test|Harness|Agent)\$?[^/]*\.class$|\.(py|wav|mp3|ogg|gguf|safetensors)$' <<< "$entries"; then vfc_die 'Unexpected private/test/model/original-mod content in addon.'; fi
for name in META-INF/neoforge.mods.toml META-INF/coremods.json META-INF/LICENSE.txt dev/johnmart19/veritycompanion/CompanionAddon.class; do grep -Fxq "$name" <<< "$entries" || vfc_die "Missing addon entry: $name"; done
cmp -s <(unzip -p "$VFC_ADDON" META-INF/neoforge.mods.toml | tr -d '\r') <(tr -d '\r' < "$VFC_ROOT/src/main/resources/META-INF/neoforge.mods.toml") || vfc_die 'Addon metadata differs from source.'
vfc_no_symlink_path "$output"; mkdir -p -- "$output"
stage=$(mktemp -d "$output/.verity-package.XXXXXXXX")
trap 'if [[ -d $stage && ! -L $stage && ${stage%/*} == "$output" && ${stage##*/} == .verity-package.* ]]; then rm -rf -- "$stage"; fi' EXIT
server=$stage/server/verity-service; client=$stage/client/verity-client-setup
mkdir -p -- "$server/addon" "$client/addon"
declare -A names=()
copy_public() {
  local source=$1 destination=$2 name=${1##*/}
  vfc_no_symlink_path "$source"
  if [[ $name == *.sh ]]; then sed 's/\r$//' "$source" > "$destination/$name"; else cp -- "$source" "$destination/$name"; fi
}
eligible() { case $1 in *.py|*.ps1|*.cmd|*.sh|requirements*.txt|*.example.json|LinuxSetup.java) return 0;; *) return 1;; esac; }
for directory in config runtime/python setup setup/python setup/linux setup/windows launchers; do
  [[ -d $VFC_ROOT/$directory ]] || continue
  vfc_no_symlink_path "$VFC_ROOT/$directory"
  for file in "$VFC_ROOT/$directory"/*; do
    [[ ! -L $file ]] || vfc_die 'Linked source file refused.'
    [[ -f $file ]] || continue
    name=${file##*/}; eligible "$name" || continue
    [[ ! ${names[$name]+yes} ]] || vfc_die "Duplicate flat service filename: $name"
    names[$name]=$file
  done
done
name=setup.cmd; file=$VFC_ROOT/$name
if [[ -f $file ]]; then
  [[ ! ${names[$name]+yes} ]] || vfc_die "Duplicate root service filename: $name"
  names[$name]=$file
fi
for name in setup.sh linux-common.sh linux-bootstrap.sh linux-detect.sh linux-install.sh linux-optional.sh linux-launch.sh LinuxSetup.java start-server.sh start-bridge.sh setup-client.cmd setup-client.ps1 dependency-tools.ps1 source-layout.ps1; do [[ ${names[$name]+yes} ]] || vfc_die "Incomplete setup source: $name"; done
if [[ ${names[install.ps1]+yes} ]]; then
  for name in setup.cmd setup.ps1 install.cmd windows-environment.ps1 windows-wizard.ps1 windows-basic.ps1; do [[ ${names[$name]+yes} ]] || vfc_die "Incomplete Windows setup source: $name"; done
fi
for name in "${!names[@]}"; do
  copy_public "${names[$name]}" "$server"
  case $name in *.sh|LinuxSetup.java|setup-client.cmd|setup-client.ps1|dependency-tools.ps1|source-layout.ps1|setup.cmd|setup.ps1|install.cmd|install.ps1|windows-*.ps1) copy_public "${names[$name]}" "$client";; esac
done
cp -- "$VFC_ADDON" "$server/addon/"; cp -- "$VFC_ADDON" "$client/addon/"
copy_docs() {
  local destination=$1 entry=$2 document
  mkdir -p -- "$destination/docs"
  for document in "$VFC_ROOT/docs"/*.md; do vfc_no_symlink_path "$document"; cp -- "$document" "$destination/docs/"; done
  for document in LICENSE SECURITY.md; do vfc_no_symlink_path "$VFC_ROOT/$document"; cp -- "$VFC_ROOT/$document" "$destination/"; done
  printf '# %s\n\nSee the [complete guide](docs/%s).\n' "${entry%.md}" "$entry" > "$destination/$entry"
}
copy_docs "$stage/server" SERVER.md; copy_docs "$client" CLIENT.md
for kind in server-service client-setup; do
  name=verity-friendly-companion-$version-$kind.zip
  directory=$stage/server; [[ $kind != client-setup ]] || directory=$stage/client
  "$VFC_JAVA_HOME/bin/jar" --create --file "$stage/$name" --no-manifest --date=2026-01-01T00:00:00Z -C "$directory" .
  vfc_no_symlink_path "$output/$name"
  if [[ -e $output/$name ]]; then cmp -s -- "$stage/$name" "$output/$name" || vfc_die "Different existing archive preserved: $output/$name"; else ln -- "$stage/$name" "$output/$name"; fi
  printf '%s  %s\n' "$(vfc_hash "$output/$name")" "$name"
done
name=verity-friendly-companion-$version.jar
vfc_helper export "$VFC_ADDON" "$output/$name"
checksums=$stage/SHA256SUMS.txt
for name in "verity-friendly-companion-$version.jar" "verity-friendly-companion-$version-client-setup.zip" "verity-friendly-companion-$version-server-service.zip"; do printf '%s  %s\n' "$(vfc_hash "$output/$name")" "$name" >> "$checksums"; done
vfc_no_symlink_path "$output/SHA256SUMS.txt"
if [[ -e $output/SHA256SUMS.txt ]]; then cmp -s -- "$checksums" "$output/SHA256SUMS.txt" || vfc_die 'Different existing checksum manifest preserved.'; else ln -- "$checksums" "$output/SHA256SUMS.txt"; fi
