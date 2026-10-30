# Privacy and security

Keep credentials on the Minecraft host under `verity-service/.secrets/`. Never
share those files, configured service settings, player saves or private recordings
in Git, release archives or issues. Report a suspected vulnerability privately
to the repository owner rather than posting credentials or an exploit publicly.

## Data flow

- Clients send push-to-talk audio through the authenticated Minecraft connection
  to the host. Recognition is disabled by default, or uses the host-selected
  local engine or explicitly authorized cloud transcription. Server-side audio
  workers do not open microphones.
- Cloud chat sends messages, recent dialogue and requested game context to Google.
- Google TTS receives reply text. Web tools send search queries or retrieve pages.
- Local LM Studio chat stays on loopback, but TTS and web tools can still use the
  Internet. Local chat mode is not a global offline switch.
- Recognized speech, messages and replies are public to players by default;
  set gameplay `publicChat` to false for private text. Voice replies are personal.
- World-local relationships, dialogue and undo journals persist on the server.
  Voice caches and service logs stay host-local. Tell friends about these flows.

Native cloud microphone transcription is disabled by default. Setting
`stt_engine: google` additionally requires explicit
`google_audio_transcription_confirmed: true`; recordings are then uploaded to
Google with a transcription-only instruction. Local Whisper does not upload
recordings. Inform players before enabling cloud recognition. The addon handles
recognition recordings in memory and does not save them as audio files. Generated
TTS WAVs are cached on the server; optional Drama 3 reference recordings are
owner-supplied persistent assets.

The native `allow_external_requests: false` setting blocks this addon's direct
cloud requests and web tools. Use local/disabled recognition and TTS with local
chat for this profile. It does not configure a firewall or control other mods.
Legacy runtime selectors remain accepted, while dialogue, cloud speech and web
requests execute in Java. Optional Python workers handle local audio only and
never read the Google key.

## Trust boundaries

Cloud providers run inside the addon JAR without a listening HTTP service,
Python, native DLLs or installer scripts. They use fixed Google HTTPS endpoints
or a validated LM Studio loopback origin, never follow redirects with
credentials, and bound response sizes and deadlines. Incomplete model results
cannot execute actions. New server credential directories are created with
account-only permissions where supported; preserve or review permissions when
copying existing credentials to another host.

Java owns optional native audio.cpp processes, confines their HTTP endpoints to
loopback and validates their identity before use. Optional CUDA library paths
apply only to the child process, never the system environment. The optional
Python audio worker must also remain on loopback. It requires a generated bearer
token, accepts bounded requests and exposes only fixed local-audio routes. Minecraft binds tool work to the
authenticated player's UUID. LLM/web output remains untrusted: supported tool
names and arguments are validated, and world changes use protection/ownership
checks. No host shell or arbitrary file tool is exposed.

Enabled game tools can create items, change effects, enchant items and modify
world blocks. Restrict server settings and operator access to suit your players.
Maintain backups; undo protects recorded cells and subsequent edits but is not
a substitute for a world backup. Third-party mod behaviors cannot all be verified.

Java stores generated TTS WAVs in `verity-service/cache/native-speech/` and
provider quota state in `verity-service/native-google-quota.json`. Protect cache
files as server dialogue data. Google configuration and key changes are read for
new requests; cloud transcription additionally requires explicit host consent.

Optional local model backends require trusted weights and compatible environments.
Silero uses a Torch package, so use it only if you trust its source. The addon does
not make downloaded executable model packages safe. Cloud availability, provider
policies and account billing are external to this repository.
