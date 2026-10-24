# Modern Music

This branch ports Modern Music to **Minecraft 1.20.1, Forge and Java 17**. Build with JDK 17 using `./gradlew build` (Windows: `scripts/dev.ps1 build`). Install `build/libs/modernmusic-1.20.1-1.0.0.jar`; the slim and sources JARs are not installation files. Development uses Forge 47.4.22; supported loader range is Forge 47.2–47.x.

Multiplayer features require this Forge port on both ends. Update the server and participating clients together for playback-bound duration reporting and the bounded public-room protocol. Public rooms admit at most 1,000 concurrent members; this is a protocol limit, not a measured server capacity. When no administrator or DJ remains in the room, listeners can automatically supply an unknown duration for the current playback without gaining playback controls. Personal playback also works on servers without the mod. The 1.21.1 NeoForge network protocol is not compatible with this port. Optional FTB integration uses FTB Teams 2001.3.1, FTB Library 2001.2.9 and Architectury 9.2.14 for Forge 1.20.1.

[简体中文](README.md) | English

Listen to NetEase Cloud Music, QQ Music, Kugou Music, and local audio in Minecraft, with support for individual playback and synchronized listening with other players.

- Search for songs, import playlists, and manage multiple playback queues.
- Play, pause, skip tracks, seek, adjust the volume, and switch repeat modes.
- View album art, lyrics, and an in-game HUD, with ten visual themes to choose from.
- Join the server public room, create listening groups, or listen with FTB Teams teammates. Public-room administrators can appoint DJs and grant song-request permissions.
- Browse and play local MP3 and WAV files. Drag and drop files to copy them into your local audio library; administrators can also drag and drop files to upload them to the server for use in individual or group queues.

![Modern Music player](docs/images/ui-player.png)

## Installation

Place `modernmusic-1.20.1-1.0.0.jar` in the `mods` folder of your Minecraft game directory. After joining a world, press **Ctrl+M** to open the player. No additional music API service is required.

Individual playback only requires the mod on the client. Synchronized multiplayer listening and shared accounts require compatible versions of the mod on both the server and participating players' clients. Listening with an FTB team also requires FTB Teams and its dependencies. Song availability depends on the music platform and your account's access permissions.

## Documentation

The following guides are currently available in Chinese:

[User Guide](docs/USER-GUIDE.md) · [QQ Music](docs/QQ-MUSIC.md) · [Kugou Music](docs/KUGOU-MUSIC.md) · [Server Shared Accounts](docs/SERVER-SHARED-ACCOUNT.md) · [Visual Themes](docs/THEMES.md)

This project is licensed under the [MIT License](LICENSE). Copyright (c) 2026 Aquariverr. See [Third-Party Notices](THIRD_PARTY_NOTICES.md) for information about third-party components.
