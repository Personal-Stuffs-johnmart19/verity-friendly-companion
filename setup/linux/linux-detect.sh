#!/usr/bin/env bash
# Bounded, read-only host discovery. No recursive home scan or automatic selection.
VFC_CANDIDATE_ROOTS=(); VFC_CANDIDATE_KINDS=(); VFC_CANDIDATE_VERIFIED=()
vfc_candidate() {
  local kind=$1 candidate=$2 previous verified=false
  [[ -d $candidate && ! -L $candidate ]] || return 0
  candidate=$(vfc_path "$candidate")
  for previous in "${VFC_CANDIDATE_ROOTS[@]}"; do [[ $previous != "$candidate" ]] || return 0; done
  if [[ -n $VFC_JAVA_HOME ]]; then
    if [[ $kind == client ]]; then vfc_helper client-version "$candidate" >/dev/null 2>&1 && verified=true
    else vfc_helper server-args "$candidate" >/dev/null 2>&1 && verified=true; fi
  fi
  VFC_CANDIDATE_ROOTS+=("$candidate"); VFC_CANDIDATE_KINDS+=("$kind"); VFC_CANDIDATE_VERIFIED+=("$verified")
}
vfc_detect() {
  local root candidate count=0
  for root in "$HOME/.minecraft" "$HOME/.var/app/com.mojang.Minecraft/data/minecraft"; do
    [[ ! -d $root/versions ]] || vfc_candidate client "$root"
  done
  for root in "$HOME/curseforge/minecraft/Instances" "$HOME/.local/share/curseforge/minecraft/Instances" "$HOME/Games/curseforge/minecraft/Instances" "$HOME/.var/app/com.overwolf.CurseForge/data/curseforge/minecraft/Instances"; do
    [[ -d $root && ! -L $root ]] || continue
    for candidate in "$root"/*; do
      [[ -d $candidate && ( -f $candidate/minecraftinstance.json || -f $candidate/manifest.json ) ]] || continue
      vfc_candidate client "$candidate"; count=$((count+1)); (( count < 128 )) || break 2
    done
  done
  for candidate in "$PWD" "$HOME/minecraft" "$HOME/Minecraft" "$HOME/minecraft-server" "$HOME/servers/minecraft" /srv/minecraft; do
    [[ ! -d $candidate/libraries/net/neoforged/neoforge ]] || vfc_candidate server "$candidate"
  done
}
vfc_show_candidates() {
  local index
  if (( ${#VFC_CANDIDATE_ROOTS[@]} == 0 )); then vfc_note 'No standard Minecraft/CurseForge/NeoForge installation was detected. Choose a standalone JAR or a new standalone NeoForge server.'; return; fi
  vfc_note 'Detected candidates (never selected automatically):'
  for ((index=0;index<${#VFC_CANDIDATE_ROOTS[@]};index++)); do
    printf '  %d. %s: %s [metadata/libraries checked: %s]\n' "$((index+1))" "${VFC_CANDIDATE_KINDS[index]}" "${VFC_CANDIDATE_ROOTS[index]}" "${VFC_CANDIDATE_VERIFIED[index]}" >&2
  done
  vfc_note 'Client candidate validation is metadata only until the launcher library root is checked during installation.'
}
vfc_plan() {
  local index comma='' action=artifact
  [[ -z $VFC_SERVER_ROOT ]] || action=server; [[ -z $VFC_CLIENT_ROOT ]] || action=client
  printf '{"schema":1,"platform":"linux","architecture":'; vfc_json "$(uname -m)"
  printf ',"profile":'; vfc_json "$VFC_PROFILE"; printf ',"action":'; vfc_json "$action"
  printf ',"java_home":'; vfc_json "$VFC_JAVA_HOME"; printf ',"cache_directory":'; vfc_json "$VFC_CACHE"
  printf ',"server_root":'; vfc_json "$VFC_SERVER_ROOT"; printf ',"client_root":'; vfc_json "$VFC_CLIENT_ROOT"
  printf ',"minecraft_root":'; vfc_json "$VFC_MINECRAFT_ROOT"; printf ',"stt":'; vfc_json "$VFC_STT"; printf ',"tts":'; vfc_json "$VFC_TTS"
  printf ',"download_models":%s,"offline":%s,"changes_performed":false,"candidates":[' "$([[ $VFC_DOWNLOAD_MODELS == 1 ]] && printf true || printf false)" "$([[ $VFC_OFFLINE == 1 ]] && printf true || printf false)"
  for ((index=0;index<${#VFC_CANDIDATE_ROOTS[@]};index++)); do
    printf '%s{"kind":' "$comma"; vfc_json "${VFC_CANDIDATE_KINDS[index]}"; printf ',"root":'; vfc_json "${VFC_CANDIDATE_ROOTS[index]}"; printf ',"verified":%s}' "${VFC_CANDIDATE_VERIFIED[index]}"; comma=,
  done
  printf ']}\n'
}
