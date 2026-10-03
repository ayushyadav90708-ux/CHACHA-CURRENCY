# Note Block Songs

A Fabric client/server mod that lets a player play an MP3 from a Note Block. The initiating client uploads the selected file in bounded chunks; the server verifies the nearby Note Block, request size, chunk order, and SHA-256 digest, then relays the file only to nearby players that do not already report that cached song. Receiving clients cache the MP3 and decode/play it locally with distance attenuation and stereo panning.

## Build profile and compatibility

This project targets **Minecraft 1.21.11 only**, using Yarn `1.21.11+build.6`, Fabric Loader `0.19.5`, and Fabric API `0.141.6+1.21.11`.

Requires Java 21. Build with:

```sh
./gradlew build
```

On Windows:

```bat
gradlew.bat build
```

The first invocation downloads Gradle 9.7.1. The output mod JAR is `build/libs/note-block-songs-1.0.0.jar`.

## Install

1. Install Fabric Loader and Fabric API for Minecraft 1.21.11.
2. Put `note-block-songs-1.0.0.jar` in `.minecraft/mods`.
3. For multiplayer, install the mod on the server and on every client that should hear the songs. The server must accept custom Fabric payloads from installed clients.
4. Launch the game once. The mod creates `.minecraft/Songs/` automatically. Place `.mp3` files there.
5. Right-click a Note Block, choose a song, and press **Play**. Nearby modded clients receive and cache the audio automatically. Use **Stop** to stop it; breaking the Note Block also stops playback.

## Settings and limits

Client settings are created at `.minecraft/config/notesongs.properties`:

- `volume`: local playback volume from 0 to 1.
- `range`: local audible radius, clamped to 8–48 blocks.
- `maxFileSizeMb`: upload limit, clamped to 1–8 MiB.

The server independently enforces an 8 MiB upload cap, 24 KiB ordered chunks, a 48-block broadcast radius, a maximum of eight active Note Block songs, and the requirement that the requester be within eight blocks of the Note Block. Client decoding rejects MP3s longer than 180 seconds. Transfer hashes are checked before playback; each client keeps received songs under `.minecraft/config/notesongs-cache/`.

Audio is rendered by the client's Java Sound output device, not Minecraft's native sound mixer. Position-based attenuation/panning is applied locally; start timing is best-effort and can vary slightly with network latency. The server relays the audio content but does not itself decode or output sound.