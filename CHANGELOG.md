# Changelog

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
