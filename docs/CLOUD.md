# Cloud setup with the addon JAR

This guide describes **beta.21**. For older beta.20 installations, use the
[matching installation guide](https://github.com/Personal-Stuffs-johnmart19/verity-friendly-companion/blob/v1.2.0-beta.20/docs/SERVER.md)
when maintaining that older version.

Google chat, transcription and text-to-speech execute in Java inside the addon.
The cloud profile needs no Python, installer scripts, service ZIP, Visual C++
runtime, CUDA, model weights or separate listening voice process. Local engines
and their installers remain optional; see [server setup](SERVER.md).

## Install the game dependencies

Use Java 21, Minecraft 1.21.1 and NeoForge 21.1.250 or newer 21.1.x. Install the
same addon JAR on the server and every client, together with official Verity JE
exactly 3.4.1 and GeckoLib 4.9.3 for NeoForge/Minecraft 1.21.1. Stop Minecraft
before changing mod JARs. Existing compatible modpacks may already provide these
dependencies. The addon does not bundle the original Verity or replace a game
installation, launcher, account or server EULA acceptance.

Build this branch using the [build guide](BUILDING.md). No beta.21 release has
been published. Friends need only the game dependencies and matching addon;
they never need a Google key or server configuration.

## Configure the server key

1. Start the server with its normal Minecraft launcher and run `/companion setup`
   as an operator. The addon creates `verity-service/voice-config.json` and
   `verity-service/.secrets/` under the server working directory. New credential
   directories use server-account permissions where the filesystem supports
   them. Existing settings and credentials are preserved.
2. On the server, create the plain-text file
   `verity-service/.secrets/google-api-key`, with **no file extension**, and put
   only your Google API key in it. Check that your editor did not append `.txt`.
   Keep it accessible only to the server account. Never enter a key in Minecraft
   chat, send it to friends or include it in a client pack.
3. Confirm in your Google project that billing is disabled. Only after that
   check, set `google_free_project_confirmed` to `true` in
   `verity-service/voice-config.json`. The addon cannot inspect or disable Google
   billing. A key alone does not enable cloud calls.
4. Run `/companion setup` again to inspect the host's configuration hints. The
   check does not make generation requests or validate a key against Google.
   New provider requests read updated settings and the key; replacing the JAR
   itself still requires stopping Minecraft.

The defaults select Google dialogue and Achird voice replies. Complete speech
audio is generated before playback. Quota exhaustion can leave the text reply
available while voice is unavailable. Free-project confirmation does not remove
provider quotas or guarantee current model access.

## Enable microphone transcription explicitly

Fresh settings use `stt_engine: disabled`. Text chat and voice replies work
without uploading a microphone recording.

If the host and players agree to send push-to-talk recordings to Google, change
these two existing fields in server `verity-service/voice-config.json`:

```json
{
  "stt_engine": "google",
  "google_audio_transcription_confirmed": true
}
```

Merge those fields into the existing settings; do not replace the whole file
with this fragment. The server uses the same Google key. Hold R to record and
release to send. Choose local recognition instead if recordings must stay on the
host. To disable uploads again, set `stt_engine` to `disabled` or a prepared local
engine. Tell players before enabling cloud transcription.

## Storage and requests

Java manages completed speech caching under
`verity-service/cache/native-speech/` and quota state in
`verity-service/native-google-quota.json`. Cached WAVs contain generated voice
replies; protect them along with server dialogue/history. Recognition recordings
are handled in memory rather than saved by the addon's transcription adapter.
Cloud transcription sends those recordings to Google when explicitly enabled.
Chat sends dialogue and requested game context; TTS sends reply text.

`allow_external_requests: false` blocks this addon's direct cloud calls and web
tools. Use local chat and local/disabled speech for that configuration. It is not
an operating-system firewall for Minecraft or other mods. See
[privacy and security](../SECURITY.md) for the complete data flow.
