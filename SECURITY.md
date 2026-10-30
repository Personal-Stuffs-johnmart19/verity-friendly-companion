# Privacy and security

Keep credentials on the Minecraft host under `verity-service/.secrets/`. Never
share those files, configured service settings, player saves or private recordings
in Git, release archives or issues. Report a suspected vulnerability privately
to the repository owner rather than posting credentials or an exploit publicly.

## Data flow

- Clients send push-to-talk audio through the authenticated Minecraft connection
  to the host for local Whisper recognition. The bridge does not open microphones.
- Cloud chat sends messages, recent dialogue and requested game context to Google.
- Google TTS receives reply text. Web tools send search queries or retrieve pages.
- Local LM Studio chat stays on loopback, but TTS and web tools can still use the
  Internet. Local chat mode is not a global offline switch.
- Recognized speech, messages and replies are public to players by default;
  set gameplay `publicChat` to false for private text. Voice replies are personal.
- World-local relationships, dialogue and undo journals persist on the server.
  Voice caches and service logs stay host-local. Tell friends about these flows.

## Trust boundaries

The bridge must remain on loopback. It requires a generated bearer token, accepts
bounded requests and exposes fixed routes. Minecraft binds tool work to the
authenticated player's UUID. LLM/web output remains untrusted: supported tool
names and arguments are validated, and world changes use protection/ownership
checks. No host shell or arbitrary file tool is exposed.

Enabled game tools can create items, change effects, enchant items and modify
world blocks. Restrict server settings and operator access to suit your players.
Maintain backups; undo protects recorded cells and subsequent edits but is not
a substitute for a world backup. Third-party mod behaviors cannot all be verified.

Optional local model backends require trusted weights and compatible environments.
Silero uses a Torch package, so use it only if you trust its source. The addon does
not make downloaded executable model packages safe. Cloud availability, provider
policies and account billing are external to this repository.
