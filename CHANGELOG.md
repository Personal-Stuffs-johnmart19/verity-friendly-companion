# Changelog

## 1.2.0-beta.21 — 2026-10-31

### Self-contained cloud and model controls

- Run Gemini chat, complete voice replies and optional cloud transcription in
  Java inside the addon JAR. Cloud and native LM Studio use no Python bridge.
- Discover LM Studio models dynamically; select/load a model, manage context and
  supported reasoning, and append a temporary prompt without replacing Verity's
  persona. Local chat failures do not silently switch to Google.
- Cache backend readiness away from the server thread. Command suggestions/help
  show configured or prepared choices, with `/llm refresh`, `/tts provider` and
  `/voice backend` for operator-controlled selection.
- Retain bounded provider deadlines, persisted quota handling and free-model
  fallback. Complete speech is generated before playback frames are sent.

### Languages and companion lifecycle

- Use standard Minecraft language resources: 32 translated interfaces with
  independent player preferences for chat, TTS and recognition language.
  Voice-language availability still depends on the selected backend.
- Preserve the exact box position when Verity emerges, including dirt paths and
  bottom slabs, while retaining opening timing and player ownership.

### Optional local audio and portable setup

- Move native audio.cpp ASR and Drama 3 process ownership/transport into Java.
  Native engines need separately prepared binaries/models, but no Python.
- Keep Faster Whisper CPU/CUDA, ROCm Whisper and Silero as optional isolated
  Python engines, with selected-venv validation and active-environment guards.
- Add Windows/Linux basic and advanced setup: detect compatible JDK/game
  installations, export a JAR or create a standalone server, and explicitly
  choose optional backends, environments and model downloads.
- Verify downloads and existing original Verity dependencies; reject altered
  Verity 3.4.1 JARs on both platforms. Preserve settings, credentials and worlds.
- Group platform installers, source builders and packaging tools; consolidate
  the command guide under `docs/COMMANDS.md`. Setup archives exclude source-only
  build tools, models, recordings and configured credentials.

### Validation and limits

- Retested gameplay/command/provider regressions, 32 language resources, original
  Verity bytecode hooks, and a fresh minimal NeoForge server. Verified native box
  emergence on stone, dirt paths and bottom slabs.
- Fresh Windows and WSL Linux builds produced identical JARs; installer/archive
  checks passed. Physical Linux GPUs, real model inference, cloud quotas and
  microphone/voice quality were not exercised by this release validation.
- This remains beta software. Custom GUIs/networks and model tool-use quality
  vary. Local recognition loads on first use; cloud replies can queue. Original
  Verity 3.4.1 and GeckoLib remain separate required dependencies.

## 1.2.0-beta.20

Public-distribution cleanup of beta.19, with no gameplay behavior changes.

- Removed test harnesses, diagnostic/research tools, old audit reports and stale
  operational documents from the public source tree.
- Replaced documentation with current client/server, command, build and privacy
  guides. Corrected voice bindings, default boss behavior and setup limitations.
- Added version-derived packaging with a universal addon JAR, server-only service
  archive, artifact validation and SHA256 checksums.
- Generalized example paths and made the portable example use CPU recognition;
  existing host configurations are not migrated or overwritten.

## Included beta.19 functionality

Saved microphone selection and recording HUD input label; Windows-default input
routing; band-limited GPU audio resampling; bounded recognition worker lifecycle;
complete TTS generation before playback; local/cloud tool dispatch; retained tool
receipts; quota retries/fallbacks; item targeting; recipe/quest inspection; building
metadata and undo protection; individual relationships, faces and optional horror.

## Validation and limits

Before removing test material, the existing 224 Python tests and Java regression
suite were run locally against matching installed dependencies. Release artifacts
are rebuilt and checked separately for metadata, content and source provenance.
These checks do not prove all in-game flows, providers or hardware work flawlessly.

One supplied Russian recording was previously decoded with one word-form error
out of twelve, preserving its meaning; this does not establish general Russian
accuracy. First GPU recognition includes initialization overhead. Russian and
English Achird samples were generated successfully in a confirmed free project;
quotas can still queue or prevent voice. Audible gameplay playback and arbitrary
modpack combinations remain outside this cleanup's validation.
