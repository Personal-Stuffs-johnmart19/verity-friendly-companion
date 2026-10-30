#!/usr/bin/env bash
# Shared Linux installer primitives. This file is sourced, never evaluated as data.
vfc_die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
vfc_note() { printf '%s\n' "$*" >&2; }
vfc_require() { command -v "$1" >/dev/null 2>&1 || vfc_die "Required tool missing: $1. Install it using your distribution's documented procedure; setup never runs sudo."; }
vfc_no_symlink_path() {
  local path=$1 current=/ segment
  local -a parts
  [[ $path == /* ]] || path=$PWD/$path
  IFS=/ read -r -a parts <<< "$path"
  for segment in "${parts[@]}"; do
    case $segment in ''|.) continue;; ..) current=$(dirname -- "$current");; *) current=${current%/}/$segment;; esac
    [[ ! -L $current ]] || vfc_die "Refusing symbolic-link path component: $current"
  done
}
vfc_path() {
  [[ -n $1 && $1 != *$'\n'* && $1 != *$'\r'* && $1 != *$'\t'* ]] || vfc_die 'Paths must be nonempty and contain no control characters.'
  vfc_no_symlink_path "$1"
  realpath -m -- "$1"
}
vfc_hash() { local result; result=$(sha256sum -- "$1") || return; printf '%s\n' "${result%% *}"; }
vfc_json() { local value=$1; value=${value//\\/\\\\}; value=${value//\"/\\\"}; value=${value//$'\n'/\\n}; value=${value//$'\r'/\\r}; value=${value//$'\t'/\\t}; printf '"%s"' "$value"; }
vfc_cache_trust() {
  local path=$1 owner mode
  vfc_no_symlink_path "$path"; [[ -d $path ]] || vfc_die "Not a cache directory: $path"
  owner=$(stat -c '%u' -- "$path"); mode=$(stat -c '%a' -- "$path")
  [[ $owner == "$(id -u)" ]] && (( (8#$mode & 0022) == 0 )) || vfc_die "Cache must belong to this account and not be group/world writable: $path. Select a private Linux-filesystem cache or repair permissions explicitly."
}
vfc_owned_directory() {
  local path=$1
  vfc_no_symlink_path "$path"
  local existed=0; [[ ! -e $path ]] || existed=1
  mkdir -p -- "$path" || vfc_die "Cannot create private directory: $path"
  [[ -d $path && ! -L $path ]] || vfc_die "Not a private directory: $path"
  if (( ! existed )); then chmod 700 -- "$path" || vfc_die "Cannot restrict directory permissions: $path"; fi
  vfc_cache_trust "$path"
}
vfc_download() {
  local url=$1 name=$2 sha=$3 target temporary actual
  [[ $url == https://* && $sha =~ ^[0-9a-f]{64}$ && $name != */* && $name != .* ]] || vfc_die 'Invalid verified download identity.'
  target=$VFC_CACHE/archives/$name
  vfc_no_symlink_path "$target"
  if [[ -f $target && ! -L $target ]]; then
    actual=$(vfc_hash "$target")
    [[ $actual == "$sha" ]] || vfc_die "Cached checksum mismatch: $target; existing file preserved."
    printf '%s\n' "$target"; return
  fi
  [[ ! -e $target && ! -L $target ]] || vfc_die "Unsafe download target: $target"
  (( ! VFC_OFFLINE )) || vfc_die "Offline prerequisite missing: $name"
  (( ! VFC_DRY_RUN && ! VFC_CHECK )) || vfc_die "Would download $name; no changes in this mode."
  vfc_require curl; vfc_owned_directory "$VFC_CACHE"; vfc_owned_directory "$VFC_CACHE/archives"
  temporary=$(mktemp "$VFC_CACHE/archives/.download.XXXXXXXX") || vfc_die 'Cannot create private download file.'
  vfc_note "Downloading verified tool/dependency: $name"
  if ! curl --fail --silent --show-error --location --proto '=https' --proto-redir '=https' --tlsv1.2 --connect-timeout 20 --max-time 1800 --retry 2 --output "$temporary" -- "$url"; then
    rm -f -- "$temporary"; vfc_die "Download failed: $name; no existing file replaced."
  fi
  actual=$(vfc_hash "$temporary")
  if [[ $actual != "$sha" ]]; then rm -f -- "$temporary"; vfc_die "SHA256 mismatch: $name; refusing extraction/execution."; fi
  chmod 600 -- "$temporary"; mv -n -- "$temporary" "$target" || vfc_die 'Cannot publish verified download.'
  [[ $(vfc_hash "$target") == "$sha" ]] || vfc_die 'Concurrent download produced an unexpected file.'
  printf '%s\n' "$target"
}
vfc_remove_stage() {
  local stage=$1 canonical parent
  [[ -d $stage && ! -L $stage && -f $stage/.verity-owned-stage ]] || return 1
  canonical=$(realpath -- "$stage"); parent=$(realpath -- "$VFC_CACHE")
  [[ $canonical == "$parent"/.extract.* && ${canonical#"$parent"/} != */* ]] || return 1
  rm -rf -- "$canonical"
}
vfc_extract() {
  local archive=$1 destination=$2 stage listing entry link resolved cache_root line member target kind
  local -a links=()
  vfc_no_symlink_path "$destination"
  [[ ! -e $destination && ! -L $destination ]] || vfc_die "Extraction destination already exists; preserved: $destination"
  vfc_owned_directory "$VFC_CACHE"; stage=$(mktemp -d "$VFC_CACHE/.extract.XXXXXXXX") || vfc_die 'Cannot create extraction directory.'
  printf 'owned\n' > "$stage/.verity-owned-stage"; listing=$stage/.archive-members
  if [[ $archive == *.zip ]]; then unzip -Z1 "$archive" > "$listing" || vfc_die 'Invalid ZIP archive.'
  elif [[ $archive == *.tar.gz || $archive == *.tgz ]]; then tar -tzf "$archive" > "$listing" || vfc_die 'Invalid tar archive.'
  else vfc_die 'Unsupported verified archive format.'; fi
  while IFS= read -r entry; do
    [[ -n $entry && $entry != /* && $entry =~ ^[A-Za-z0-9_./+\ @=-]+$ ]] || vfc_die 'Unsafe archive member.'
    case "/$entry/" in */../*) vfc_die 'Archive traversal rejected.';; esac
  done < "$listing"
  # Reject dangerous links BEFORE extraction, including writes beneath a link member.
  if [[ $archive == *.zip ]]; then
    if unzip -Z -l "$archive" | grep -q '^l'; then vfc_die 'ZIP symbolic links are not allowed.'; fi
  else
    while IFS= read -r line; do
      kind=${line:0:1}; case $kind in -|d) continue;; l|h) ;; *) vfc_die 'Unsupported tar member type.';; esac
      [[ $line =~ ^[^[:space:]]+[[:space:]]+[^[:space:]]+[[:space:]]+[0-9]+[[:space:]]+[0-9-]+[[:space:]]+[0-9:]+[[:space:]]+(.*)$ ]] || vfc_die 'Unrecognized tar link metadata.'
      member=${BASH_REMATCH[1]}
      if [[ $kind == l ]]; then [[ $member == *' -> '* ]] || vfc_die 'Invalid tar link.'; target=${member#*' -> '}; member=${member%%' -> '*}
      else [[ $member == *' link to '* ]] || vfc_die 'Invalid tar hardlink.'; target=${member#*' link to '}; member=${member%%' link to '*}; fi
      [[ $target != /* && $target =~ ^[A-Za-z0-9_./+\ @=-]+$ ]] || vfc_die 'Unsafe tar link target.'
      if [[ $kind == l ]]; then resolved=$(realpath -m -- "$stage/$(dirname -- "$member")/$target"); else resolved=$(realpath -m -- "$stage/$target"); fi
      [[ $resolved == "$stage"/* ]] || vfc_die 'Tar link target escapes extraction root.'
      links+=("$(realpath -m -- "$stage/$member")")
    done < <(tar --list --verbose --gzip --file "$archive" --quoting-style=literal)
    while IFS= read -r entry; do
      resolved=$(realpath -m -- "$stage/$entry")
      for link in "${links[@]}"; do [[ $resolved != "$link"/* ]] || vfc_die 'Archive writes through a link member.'; done
    done < "$listing"
  fi
  if [[ $archive == *.zip ]]; then unzip -q "$archive" -d "$stage" || vfc_die 'ZIP extraction failed.'
  else tar --extract --gzip --file "$archive" --directory "$stage" --no-same-owner --no-same-permissions || vfc_die 'Tar extraction failed.'; fi
  cache_root=$(realpath -- "$stage")
  while IFS= read -r -d '' link; do
    resolved=$(realpath -m -- "$link")
    [[ $resolved == "$cache_root"/* ]] || vfc_die 'Archive symbolic link escapes its private extraction directory.'
  done < <(find "$stage" -type l -print0)
  rm -f -- "$listing"; mkdir -p -- "$(dirname -- "$destination")"
  mv -- "$stage" "$destination" || vfc_die 'Cannot publish extracted tool.'
}
vfc_helper() { "$VFC_JAVA_HOME/bin/java" "$VFC_HELPER" "$@"; }
vfc_source_file() {
  local name=$1 directory
  for directory in "$VFC_ROOT" "$VFC_ROOT/config" "$VFC_ROOT/runtime/python" "$VFC_ROOT/setup/python" "$VFC_ROOT/launchers" "$VFC_ROOT/setup/linux"; do
    if [[ -f $directory/$name && ! -L $directory/$name ]]; then printf '%s\n' "$directory/$name"; return; fi
  done
  return 1
}
