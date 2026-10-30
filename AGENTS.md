# Verity Friendly Companion

This is the standalone addon source repository. Minecraft installations, the
optional local voice tools and the original Verity 3.4.1 JAR are external to this source tree.
Do not assume this checkout is a deployed game/server directory.

Read README.md, docs/BUILDING.md and CHANGELOG.md before changing runtime
behavior. Preserve saves, conversations, credentials, reference audio and
unrelated edits. Do not restart a game/server/model incidentally for QA.

Keep all credentials and Python/voice processing server-side. Clients receive
audio over the authenticated Minecraft connection. Never add models, recordings,
worlds, secrets or original Verity binaries to Git.

Use setup.cmd (portable Windows bootstrap) or the Java 21 Gradle build, then validate
release artifacts with tools/release/package-release.ps1. Internal regression harnesses are
kept outside the public source distribution. Live cloud
generation consumes quota and must remain within the user's approved free tier.
Distinguish source validation, actual runtime checks, installation and publication
when reporting results. A passing synthetic speech test does not establish
accuracy for a player's microphone or voice quality.

Installer, dependency provisioning, documentation and packaging-only changes do
not justify a new mod version or mod release. Keep those changes in source and
validate setup independently. Do not add incidental JAR changes to justify a
version bump. Keep published artifacts immutable; runtime Java or bridge changes
must be identified separately from setup changes before deciding release scope.

Use Context7 only for a specific dependency API gap at the installed version;
reuse a resolved library ID. No broad documentation retrieval is needed.

Source layout: Java/resources under src/main, public examples under config,
optional audio runtime under runtime/python, provisioning helpers/requirements
under setup/python, platform installers under setup/windows and setup/linux,
source builders under tools/build, packagers under tools/release, and installed
process launchers under launchers. docs/COMMANDS.md is the sole command reference.
setup/source-layout.ps1 is the shared source-to-flat-service mapping. Preserve
installed settings, credentials and asset paths when reorganizing source.

Cloud-only runtime is self-contained in the addon JAR; do not make scripts,
Python, native DLLs or optional local-service ZIPs prerequisites for cloud use.
Read docs/CLOUD.md for the host-only key and explicit microphone upload consent.
