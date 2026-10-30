#!/usr/bin/env bash
# Pinned user-private toolchain. No Python, package manager, root or permanent PATH edits.
vfc_test_jdk() {
  local home=$1 java_version compiler_version
  [[ -n $home && -f $home/release && -x $home/bin/java && -x $home/bin/javac ]] || return 1
  grep -Eq '^JAVA_VERSION="21([.+"]|$)' "$home/release" || return 1
  java_version=$(timeout 10 "$home/bin/java" -version 2>&1) || return 1
  compiler_version=$(timeout 10 "$home/bin/javac" -version 2>&1) || return 1
  [[ $java_version =~ version\ \"21[.\"] && $compiler_version =~ ^javac\ 21([.+]|$) ]]
}
vfc_find_jdk() {
  local candidate compiler
  if [[ -n $VFC_JAVA_HOME ]]; then vfc_test_jdk "$VFC_JAVA_HOME" || vfc_die '--java-home must name a complete, working Java 21 JDK (not a bundled JRE).'; return; fi
  if (( ! VFC_PORTABLE_JDK )); then
    if [[ -n ${JAVA_HOME:-} ]] && vfc_test_jdk "$JAVA_HOME"; then VFC_JAVA_HOME=$(realpath -- "$JAVA_HOME"); return; fi
    compiler=$(command -v javac || true)
    if [[ -n $compiler ]]; then candidate=$(dirname -- "$(dirname -- "$(readlink -f -- "$compiler")")"); if vfc_test_jdk "$candidate"; then VFC_JAVA_HOME=$candidate; return; fi; fi
    for candidate in "$VFC_CACHE"/jdk-21.0.13-9-linux-*/jdk-* /usr/lib/jvm/* /opt/java/* "$HOME"/.minecraft/runtime/*/linux/* "$HOME"/.local/share/PrismLauncher/java/*; do
      if vfc_test_jdk "$candidate"; then VFC_JAVA_HOME=$(realpath -- "$candidate"); return; fi
    done
  fi
}
vfc_prepare_jdk() {
  local machine arch sha name archive destination candidate
  vfc_find_jdk
  [[ -z $VFC_JAVA_HOME ]] || return 0
  machine=$(uname -m)
  case $machine in
    x86_64|amd64) arch=x64; sha=d7f5d5cf865a2902b70ff549b97586a7d3f234e016f041ab447ea740c7d66d1c;;
    aarch64|arm64) arch=aarch64; sha=e3522e707fc7dbe2a72c30746b1e7068301a0213ecd213d4491f35a14eb55578;;
    *) vfc_die "No pinned Linux Java archive for $machine. Supply a compatible --java-home.";;
  esac
  destination=$VFC_CACHE/jdk-21.0.13-9-linux-$arch
  candidate=$destination/jdk-21.0.13+9
  if vfc_test_jdk "$candidate"; then VFC_JAVA_HOME=$candidate; return; fi
  (( ! VFC_CHECK && ! VFC_DRY_RUN )) || vfc_die 'Java 21 JDK missing; portable Temurin 21.0.13+9 would be installed into the private cache.'
  name=OpenJDK21U-jdk_${arch}_linux_hotspot_21.0.13_9.tar.gz
  archive=$(vfc_download "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/$name" "$name" "$sha")
  vfc_extract "$archive" "$destination"
  vfc_test_jdk "$candidate" || vfc_die 'Portable Java 21 JDK validation failed; no game installation changed.'
  VFC_JAVA_HOME=$candidate
}
vfc_prepare_gradle() {
  local archive
  VFC_GRADLE=$VFC_CACHE/gradle-9.2.1/gradle-9.2.1/bin/gradle
  [[ -x $VFC_GRADLE ]] && return
  (( ! VFC_CHECK && ! VFC_DRY_RUN )) || vfc_die 'Gradle 9.2.1 is missing from the private cache.'
  archive=$(vfc_download 'https://services.gradle.org/distributions/gradle-9.2.1-bin.zip' 'gradle-9.2.1-bin.zip' '72f44c9f8ebcb1af43838f45ee5c4aa9c5444898b3468ab3f4af7b6076c5bc3f')
  vfc_extract "$archive" "$VFC_CACHE/gradle-9.2.1"
  [[ -x $VFC_GRADLE ]] || vfc_die 'Gradle extraction failed.'
}
vfc_build() {
  local -a arguments
  [[ -f $VFC_ROOT/build.gradle ]] || vfc_die 'This setup package contains a bundled JAR, not source. Use it directly, or obtain the source checkout to build.'
  vfc_prepare_gradle
  arguments=(--no-daemon --console=plain -p "$VFC_ROOT" -g "$VFC_CACHE/gradle-home" "-Dorg.gradle.java.home=$VFC_JAVA_HOME" "-Porg.gradle.java.installations.paths=$VFC_JAVA_HOME")
  (( ! VFC_OFFLINE )) || arguments+=(--offline)
  JAVA_HOME=$VFC_JAVA_HOME PATH="$VFC_JAVA_HOME/bin:$PATH" "$VFC_GRADLE" "${arguments[@]}" jar || vfc_die 'Build failed; no Minecraft installation changed.'
}
