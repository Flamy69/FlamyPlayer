# FlamyPlayer

A small, fast, folder-based audio player for Android. It does two things:

- **Music** – point it at a folder (for example your whole `Music` directory full of albums and
  discographies) and everything inside becomes one big playlist. Hit shuffle and get on with your day.
- **Audiobooks** – point it at the folder of a book and it plays the files in order, start to
  finish, and remembers where you stopped.

No accounts, no ads, no network access. The app does not even request the internet permission.

## Features

- Pick one or more folders per section; subfolders are always included
- The library opens instantly, even with thousands of tracks (see [below](#why-it-is-fast))
- Shuffle, repeat all / repeat one, search across title, artist, album and file name
- Audiobooks keep their own position, with a progress bar per book, 10 s back / 30 s forward
  buttons and playback speed from 0.75× to 2×
- Natural file ordering, so `Chapter 2` comes before `Chapter 10`
- Media notification and lock screen controls with cover art
- Headset and Bluetooth buttons: play, pause, next, previous
- Pauses when headphones are unplugged and handles audio focus (calls, navigation prompts)
- Skips files the device cannot decode instead of stopping the playlist
- Light and dark theme, English and Czech

## Using it

1. Install the app and allow access to audio files when asked.
2. **Music**: tap the folder icon, tick the folders you want, confirm. Tap **Shuffle**, or tap any
   track to start from there.
3. **Audiobooks**: tap the folder icon and tick the folder of each book. Every ticked folder is one
   book; folders like `CD1`, `CD2` inside it are played in order. Tap a book to see its chapters,
   or the play button to continue where you left off.

## Supported formats

Playback is handled by [Media3 ExoPlayer](https://developer.android.com/media/media3/exoplayer)
with the decoders built into Android:

| Format | Typical extensions |
| --- | --- |
| MP3 | `.mp3` |
| FLAC | `.flac` |
| AAC | `.m4a`, `.m4b`, `.aac`, `.mp4` |
| Ogg Vorbis | `.ogg` |
| Opus | `.opus`, `.ogg` |
| WAV | `.wav` |

Other formats play if the device has a decoder for them. ALAC and WMA usually do not.

## Why it is fast

FlamyPlayer never scans your storage or opens files to read their tags. Android already maintains
an index of every audio file on the device (`MediaStore`), so the whole library, including titles,
artists and durations, comes back from a single database query. The folder picker is built from the
same data and only shows folders that actually contain audio.

Two consequences worth knowing:

- Files copied to the phone show up once Android has indexed them, which normally takes a few
  seconds. The app refreshes on its own when that happens.
- Folders that contain a `.nomedia` file are hidden from the index and therefore from the app.

Playlists are handed to the playback service in-process, so starting a 10 000 track shuffle does
not have to squeeze thousands of items through a Binder transaction.

## Building

Requirements: JDK 17 or newer and the Android SDK (either set `ANDROID_HOME`, or create a
`local.properties` file with `sdk.dir=/path/to/sdk`). Android Studio takes care of both.

```sh
./gradlew assembleRelease        # optimized build
adb install -r app/build/outputs/apk/release/app-release.apk
```

The release build is minified and noticeably smoother than a debug build. For convenience it is
signed with the debug key so that it installs straight away; configure your own keystore before
distributing builds.

```sh
./gradlew testDebugUnitTest      # unit tests
./gradlew lintDebug              # Android lint
```

Minimum Android version: 8.0 (API 26).

## Project layout

```
app/src/main/kotlin/io/github/flamy69/flamyplayer/
├── data/        library query, folder tree, natural ordering, settings
├── playback/    media session service and the UI's connection to it
└── ui/          Jetpack Compose screens
```

Written in Kotlin with Jetpack Compose (Material 3) and Media3. There is no database and no
dependency injection framework; the whole app is a few small files.

## Permissions

| Permission | Why |
| --- | --- |
| `READ_MEDIA_AUDIO` (`READ_EXTERNAL_STORAGE` before Android 13) | Reading your audio files |
| `POST_NOTIFICATIONS` | Showing the playback notification |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Playing while the app is in the background |
| `WAKE_LOCK` | Keeping playback going with the screen off |
| `ACCESS_NETWORK_STATE` | Added by the Media3 library; the app itself has no internet permission |
