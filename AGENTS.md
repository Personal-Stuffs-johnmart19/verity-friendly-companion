# Verity Friendly Companion

This is the standalone addon source repository. Minecraft installations, the
server voice service and the original Verity 3.4.1 JAR are external dependencies.
Do not assume this checkout is a deployed game/server directory.

Read README.md, docs/BUILDING.md and CHANGELOG.md before changing runtime
behavior. Preserve saves, conversations, credentials, reference audio and
unrelated edits. Do not restart a game/server/model incidentally for QA.

Keep all credentials and Python/voice processing server-side. Clients receive
audio over the authenticated Minecraft connection. Never add models, recordings,
worlds, secrets or original Verity binaries to Git.

Compile with Java 21 and matching installed client/server libraries, then validate
release artifacts with package-release.ps1. Internal regression harnesses are
kept outside the public source distribution. Live cloud
generation consumes quota and must remain within the user's approved free tier.
Distinguish source validation, actual runtime checks, installation and publication
when reporting results. A passing synthetic speech test does not establish
accuracy for a player's microphone or voice quality.

Use Context7 only for a specific dependency API gap at the installed version;
reuse a resolved library ID. No broad documentation retrieval is needed.
