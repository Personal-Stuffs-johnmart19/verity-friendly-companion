#!/usr/bin/env bash
vfc_select_artifact() {
  local version candidate
  local -a bundled=()
  if [[ -z $VFC_ADDON ]]; then
    if [[ -f $VFC_ROOT/build.gradle ]]; then
      version=$(sed -n 's/^version="\([A-Za-z0-9._-]*\)"$/\1/p' "$VFC_ROOT/src/main/resources/META-INF/neoforge.mods.toml")
      [[ -n $version ]] || vfc_die 'Addon version metadata is missing.'
      VFC_ADDON=$VFC_ROOT/dist/verity-friendly-companion-$version.jar
      if (( ! VFC_CHECK )) && { (( VFC_BUILD_ONLY )) || [[ ! -f $VFC_ADDON ]]; }; then vfc_build; fi
    else
      for candidate in "$VFC_ROOT"/addon/*.jar; do [[ ! -f $candidate ]] || bundled+=("$candidate"); done
      (( ${#bundled[@]} == 1 )) || vfc_die 'The setup ZIP must contain exactly one addon/ JAR, or supply --addon-jar.'
      VFC_ADDON=${bundled[0]}
    fi
  fi
  [[ -f $VFC_ADDON && ! -L $VFC_ADDON ]] || vfc_die "Addon JAR missing: $VFC_ADDON"
  VFC_ADDON_VERSION=$(vfc_helper addon-version "$VFC_ADDON") || vfc_die 'Addon JAR validation failed.'
}
vfc_not_running() {
  local root=$1 process executable current argument count
  for process in /proc/[0-9]*/cwd; do
    current=$(readlink -f -- "$process" 2>/dev/null || true)
    executable=$(readlink -- "${process%/cwd}/exe" 2>/dev/null || true)
    case ${executable##*/} in java|python|python[0-9]*|audiocpp_server) ;; *) continue;; esac
    if [[ $current == "$root" || $current == "$root"/* || $executable == "$root"/* ]]; then vfc_die 'A game/audio process is running from the selected directory. Stop it yourself; setup never restarts it.'; fi
    count=0
    while IFS= read -r -d '' argument; do
      count=$((count+1)); (( count<=128 )) || break
      [[ $argument != "$root" && $argument != "$root"/* ]] || vfc_die 'An active process uses the selected service/environment. Stop it yourself before replacing code/packages.'
    done < "${process%/cwd}/cmdline" 2>/dev/null || true
  done
}
vfc_required_mod() {
  local root=$1 id=$2 version=$3 name=$4 url=$5 sha=$6 state archive
  state=$(vfc_helper mod-state "$root" "$id" "$version") || vfc_die "Existing $id mods are incompatible or duplicated; preserved."
  [[ $state != present ]] || return 0
  (( ! VFC_CHECK )) || vfc_die "Required mod missing: $id $version"
  archive=$(vfc_download "$url" "$name" "$sha")
  vfc_helper copy-mod "$root" "$archive" "$name"
  [[ $(vfc_helper mod-state "$root" "$id" "$version") == present ]] || vfc_die "Downloaded $id metadata did not match its pinned identity."
}
vfc_required_mods() {
  local root=$1
  vfc_required_mod "$root" verity 3.4.1 verity-3.4.1.jar 'https://edge.forgecdn.net/files/8346/795/verity-3.4.1.jar' b32223e720affda0e617efc694c3fb0bda4cb7de5941890c5e9cc828b700a93b
  vfc_required_mod "$root" geckolib 4.9.3 geckolib-neoforge-1.21.1-4.9.3.jar 'https://dl.cloudsmith.io/public/geckolib3/geckolib/maven/software/bernie/geckolib/geckolib-neoforge-1.21.1/4.9.3/geckolib-neoforge-1.21.1-4.9.3.jar' 20a1995e4074f387ff549e2c57ea79dd7d41ee83c6afb9dd6a6e840e592d1c47
}
vfc_loader_installer() {
  local version=$1 sha=0e47a91ba2139a8db4bf7627af081f7b5789b508bb039ee8dea1272b79693d60
  [[ $version =~ ^21\.1\.([0-9]{3,6})$ ]] && (( 10#${BASH_REMATCH[1]} >= 250 )) || vfc_die 'Unsupported NeoForge version.'
  if [[ $version != 21.1.250 ]]; then
    vfc_require curl
    (( ! VFC_OFFLINE )) || vfc_die 'Offline repair of this loader needs its already pinned installer; the default available installer is 21.1.250.'
    sha=$(curl --fail --silent --show-error --location --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 30 -- "https://maven.neoforged.net/releases/net/neoforged/neoforge/$version/neoforge-$version-installer.jar.sha256")
    sha=${sha%%[[:space:]]*}; [[ $sha =~ ^[0-9a-f]{64}$ ]] || vfc_die 'Official NeoForge SHA256 metadata is invalid.'
  fi
  vfc_download "https://maven.neoforged.net/releases/net/neoforged/neoforge/$version/neoforge-$version-installer.jar" "neoforge-$version-installer.jar" "$sha"
}
vfc_server_loader() {
  local root=$1 version=$VFC_LOADER installer entry
  if vfc_helper server-args "$root" "$version" >/dev/null 2>&1; then return; fi
  (( ! VFC_CHECK )) || vfc_die 'Compatible NeoForge/Minecraft server libraries are missing or incomplete.'
  if [[ -d $root/libraries/net/minecraftforge/forge ]]; then vfc_die 'An existing Forge installation is preserved. Select a separate NeoForge 1.21.1 server.'; fi
  if [[ -d $root/libraries/net/neoforged/neoforge ]]; then
    version=$(vfc_helper server-version "$root" "$version") || vfc_die 'Existing loader is incompatible/ambiguous; it will not be silently upgraded.'
  else
    entry=$(find "$root" -mindepth 1 -maxdepth 1 -print -quit)
    [[ -z $entry ]] || vfc_die 'A standalone NeoForge server requires an empty directory. Existing unrecognized game data is preserved.'
    version=${version:-21.1.250}
  fi
  (( ! VFC_OFFLINE )) || vfc_die 'Installing missing Minecraft/NeoForge server libraries requires online access.'
  installer=$(vfc_loader_installer "$version")
  (cd -- "$root" && "$VFC_JAVA_HOME/bin/java" -jar "$installer" --installServer "$root") || vfc_die 'NeoForge installer failed; no game/server was launched.'
  vfc_helper server-args "$root" "$version" >/dev/null || vfc_die 'NeoForge installation is incomplete.'
}
vfc_copy_public() {
  local name=$1 destination=$2 source
  source=$(vfc_source_file "$name") || vfc_die "Setup package is missing $name"
  [[ ! -L $destination/$name ]] || vfc_die 'Refusing linked service code.'
  if [[ -f $destination/$name ]] && cmp -s -- "$source" "$destination/$name"; then return; fi
  # Code is installer-owned; preserve the old copy before replacing it atomically.
  local temporary backup
  temporary=$(mktemp "$destination/.setup-code.XXXXXXXX")
  cp -- "$source" "$temporary"; chmod 600 -- "$temporary"
  if [[ -e $destination/$name ]]; then
    [[ -f $destination/$name ]] || vfc_die 'Service code destination is not a regular file.'
    backup=$destination/code-backups/$(date -u +%Y%m%dT%H%M%SZ)-$$
    vfc_no_symlink_path "$backup"
    mkdir -p -- "$backup"; cp -n -- "$destination/$name" "$backup/$name"
  fi
  mv -fT -- "$temporary" "$destination/$name"
}
vfc_install_shell_files() {
  local root=$1 service=$1/verity-service name
  mkdir -p -- "$service"; vfc_no_symlink_path "$service"
  for name in linux-common.sh linux-bootstrap.sh linux-detect.sh linux-install.sh linux-optional.sh linux-launch.sh LinuxSetup.java start-server.sh start-bridge.sh; do vfc_copy_public "$name" "$service"; done
  vfc_helper java-home "$root" "$VFC_JAVA_HOME"
  # The launcher stays in the service folder; no existing run.sh is edited.
  chmod u+x -- "$service/start-server.sh" "$service/start-bridge.sh"
}
vfc_install_server() {
  mkdir -p -- "$VFC_SERVER_ROOT"; vfc_no_symlink_path "$VFC_SERVER_ROOT"; vfc_not_running "$VFC_SERVER_ROOT"
  vfc_server_loader "$VFC_SERVER_ROOT"
  vfc_required_mods "$VFC_SERVER_ROOT"
  vfc_helper install-addon "$VFC_SERVER_ROOT" "$VFC_ADDON"
  vfc_helper bootstrap "$VFC_SERVER_ROOT" "$VFC_PROFILE" "$VFC_JAVA_HOME" '' '' server
  vfc_install_shell_files "$VFC_SERVER_ROOT"
  if [[ $VFC_PROFILE == advanced ]]; then
    # shellcheck source=setup/linux/linux-optional.sh
    source "$VFC_HELPERS/linux-optional.sh"; vfc_optional "$VFC_SERVER_ROOT"
  fi
  vfc_note "Server dependencies installed. No server/model was started. Review Minecraft's EULA yourself; launch with: bash '$VFC_SERVER_ROOT/verity-service/start-server.sh'"
}
vfc_client_loader() {
  local root=$1 minecraft=$VFC_MINECRAFT_ROOT version=$VFC_LOADER installer declared
  if [[ -f $root/minecraftinstance.json || -f $root/manifest.json ]]; then
    declared=$(vfc_helper client-version "$root") || vfc_die 'Existing modpack declares an incompatible loader; metadata is preserved.'
    [[ -z $version || $version == "$declared" ]] || vfc_die '--loader-version differs from the selected modpack; refusing a silent upgrade.'
    version=$declared
  fi
  if [[ -z $minecraft && -d $root/../../Install/libraries ]]; then minecraft=$(vfc_path "$root/../../Install"); fi
  if [[ -z $minecraft && -d $root/versions ]]; then minecraft=$root; fi
  if [[ -z $version ]]; then version=$(vfc_helper client-version "$root") || vfc_die 'A matching initialized Minecraft/CurseForge instance is required. Existing metadata is preserved.'; fi
  [[ -n $minecraft ]] || vfc_die 'Pass --minecraft-root with the initialized launcher directory so actual loader/client libraries can be checked. Setup never provisions a Minecraft account.'
  if vfc_helper client-check "$minecraft" "$version" >/dev/null 2>&1; then return; fi
  (( ! VFC_CHECK && ! VFC_OFFLINE )) || vfc_die 'Minecraft/NeoForge client libraries are incomplete; online launcher/loader preparation is required.'
  [[ -f $minecraft/launcher_profiles.json ]] || vfc_die 'NeoForge client installation requires an initialized official launcher root. Use CurseForge/Prism to repair their managed runtime.'
  vfc_no_symlink_path "$minecraft/launcher_profiles.json"
  installer=$(vfc_loader_installer "$version")
  "$VFC_JAVA_HOME/bin/java" -jar "$installer" --installClient "$minecraft" || vfc_die 'NeoForge client installer failed.'
  vfc_helper client-check "$minecraft" "$version" >/dev/null || vfc_die 'Client loader libraries remain incomplete. Open the matching profile in your launcher.'
}
vfc_install_client() {
  [[ $VFC_PROFILE == basic ]] || vfc_die 'Clients never install Python, models or advanced server backends.'
  [[ -d $VFC_CLIENT_ROOT ]] || vfc_die 'Select an existing initialized client instance.'
  vfc_not_running "$VFC_CLIENT_ROOT"; vfc_client_loader "$VFC_CLIENT_ROOT"
  vfc_required_mods "$VFC_CLIENT_ROOT"; vfc_helper install-addon "$VFC_CLIENT_ROOT" "$VFC_ADDON"
  vfc_helper bootstrap "$VFC_CLIENT_ROOT" basic "$VFC_JAVA_HOME" '' '' client
  vfc_note 'Client mods installed. Select the compatible NeoForge profile in your launcher. No Python, credentials or models were installed.'
}
vfc_check_installation() {
  local root state
  if [[ -n $VFC_SERVER_ROOT ]]; then root=$VFC_SERVER_ROOT; vfc_server_loader "$root"
  elif [[ -n $VFC_CLIENT_ROOT ]]; then root=$VFC_CLIENT_ROOT; vfc_client_loader "$root"
  else vfc_note 'PASS Java 21 JDK and standalone addon metadata; no host installation selected.'; return; fi
  vfc_required_mods "$root"; state=$(vfc_helper mod-state "$root" verity_companion "$VFC_ADDON_VERSION")
  [[ $state == present ]] || vfc_die 'Matching addon JAR is missing.'
  if [[ $VFC_PROFILE == advanced ]]; then
    source "$VFC_HELPERS/linux-optional.sh"
    vfc_optional_check "$root"
  fi
  vfc_note 'PASS selected host Java, Minecraft/NeoForge libraries and required mod versions. No files changed, models loaded or APIs called.'
}
