# FlamyPlayer

A small, fast, folder-based media player for Android. It does three things:

- **Music** – point it at a folder (for example your whole `Music` directory full of albums and
  discographies) and everything inside becomes one big playlist. Hit shuffle and get on with your day.
- **Audiobooks** – point it at the folder of a book and it plays the files in order, start to
  finish, and remembers where you stopped.
- **Video** – plays the videos on the phone and, over Wi-Fi, the ones in shared folders of a
  Windows PC or a NAS, with subtitles you can restyle and move.

No accounts, no ads, no tracking. The app never contacts the internet; it only talks to your own
local network, to find the machines that share folders and to play from the ones you add.

## Features

- Pick one or more folders per section; subfolders are always included
- The library opens instantly, even with thousands of tracks (see [below](#why-it-is-fast))
- Shuffle, repeat all / repeat one, search across title, artist, album and file name
- Audiobooks keep their own position, with a progress bar per book, 10 s back / 30 s forward
  buttons and playback speed from 0.75× to 2×
- Boost for quiet recordings and quiet headphones: up to +20 dB on top of the normal volume,
  remembered for each audiobook separately (and once for music)
- Natural file ordering, so `Chapter 2` comes before `Chapter 10`
- Media notification and lock screen controls with cover art
- Headset and Bluetooth buttons: play, pause, next, previous
- Pauses when headphones are unplugged and handles audio focus (calls, navigation prompts)
- Skips files the device cannot decode instead of stopping the playlist
- Video from the phone and from network folders (SMB 2/3: Windows shares, NAS), played straight
  from the share without copying
- Computers and NAS boxes on your Wi-Fi are found automatically, and Windows computer names
  work as addresses
- Subtitles from the video file or from `.srt`, `.ass` and `.vtt` files next to it, with
  adjustable size, color and position; old Windows code pages are detected, so accented
  letters survive
- Rotation lock and manual rotation, brightness and volume by swiping, double tap to skip
  10 seconds, choice of audio track, resume where you stopped
- Light and dark theme, English and Czech

## Download

Grab the APK of the [latest release](https://github.com/Flamy69/FlamyPlayer/releases/latest) and
open it on your phone. Android will ask you to allow installing apps from your browser or file
manager the first time. Newer releases install over older ones and keep your settings.

Requires Android 8.0 or newer.

## Using it

1. Open the app and allow access to audio files when asked.
2. **Music**: tap the folder icon, tick the folders you want, confirm. Tap **Shuffle**, or tap any
   track to start from there.
3. **Audiobooks**: tap the folder icon and tick the folder of each book. Every ticked folder is one
   book; folders like `CD1`, `CD2` inside it are played in order. Tap a book to see its chapters,
   or the play button to continue where you left off.
4. **Video**: folders of the phone that contain videos are listed automatically, and so are the
   computers and NAS boxes on your Wi-Fi that share folders. Tap one of them, or tap **+** and
   enter an address yourself, for example `192.168.1.10`, `NAS/video` or `\\PC\Films`; add the
   user name and password if the share needs them. Tap a video to play it; the other videos of
   the folder follow. When you add another folder, the settings of one you already have,
   password included, can be copied with one tap.

In the video player:

| Gesture or button | What it does |
| --- | --- |
| Tap | Show or hide the controls |
| Double tap on the left / right half | Back / forward 10 seconds |
| Swipe up or down on the left half | Brightness |
| Swipe up or down on the right half | Volume |
| Subtitles button | Pick the subtitles, load them from a file, set size, position and color |
| Audio button | Pick the audio track |
| Rotation lock | Keep the current orientation however the phone is held |
| Rotate | Switch between landscape and portrait |
| Picture size | Fit, crop to fill the screen, or stretch |

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

Video works the same way: MKV, MP4/MOV, WebM, AVI, MPEG-TS and FLV files are read by ExoPlayer and
decoded by the phone. H.264 plays everywhere; H.265, VP9 and AV1 on any reasonably recent phone.
Many phones have no decoder for AC3 or DTS sound: such a video plays silently, and the app says
so. Subtitles inside the file (text or picture based) and separate `.srt`, `.ass`, `.ssa` and
`.vtt` files are supported.

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

Videos on the phone come from the same index. Network folders are listed on demand, one folder
at a time, over a connection that stays open between requests; nothing is scanned or indexed in
advance. During playback the file is read in requests of up to 1 MB, which keeps a Wi-Fi link
busy with data instead of round trips.

Finding the machines on your network takes two to four seconds: the app tries the file sharing
port of every address of the phone's own Wi-Fi network (at most 254 of them) at the same time
and asks the ones that answer for their name. Alongside, it asks the network which machines
stream media, the way a TV finds its sources, and checks those for shared folders too. It never
looks beyond the local network.

## Building

Requirements: JDK 17 or newer and the Android SDK (either set `ANDROID_HOME`, or create a
`local.properties` file with `sdk.dir=/path/to/sdk`). Android Studio takes care of both.

```sh
./gradlew assembleRelease        # optimized build
adb install -r app/build/outputs/apk/release/app-release.apk
```

The release build is minified and noticeably smoother than a debug build.

```sh
./gradlew testDebugUnitTest      # unit tests
./gradlew lintDebug              # Android lint
```

### Signing

On a fresh checkout the release build is signed with the Android debug key, so it installs straight
away but cannot replace an official release (Android refuses updates signed with a different key).
To sign with your own key, create a `keystore.properties` file in the project root; it is ignored
by git:

```properties
FLAMY_KEYSTORE_FILE=my-key.jks
FLAMY_KEYSTORE_PASSWORD=...
FLAMY_KEY_ALIAS=...
FLAMY_KEY_PASSWORD=...
```

The same four names work as environment variables, which is how the CI build gets them.

### Releases

Every push to `main` is tested and built by [GitHub Actions](.github/workflows/build.yml), and the
APK is attached to the workflow run. Pushing a version tag publishes it as a release:

```sh
git tag v1.2.3
git push origin v1.2.3
```

The tag sets the version of the app, so it has to have the form `vMAJOR.MINOR.PATCH`. The workflow
reads the signing key from the repository secrets `KEYSTORE_BASE64` (the keystore file, Base64
encoded), `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`.

## Project layout

```
app/src/main/kotlin/io/github/flamy69/flamyplayer/
├── data/        library queries, folder tree, natural ordering, settings
├── network/     SMB client and the data source that lets the player read from a share
├── playback/    media session service and the UI's connection to it
├── ui/          Jetpack Compose screens
└── video/       the video player
```

Written in Kotlin with Jetpack Compose (Material 3) and Media3; network folders are accessed
with [smbj](https://github.com/hierynomus/smbj). There is no database and no dependency
injection framework; the whole app is a few small files.

## Permissions

| Permission | Why |
| --- | --- |
| `READ_MEDIA_AUDIO` (`READ_EXTERNAL_STORAGE` before Android 13) | Reading your audio files |
| `READ_MEDIA_VIDEO`, `READ_MEDIA_VISUAL_USER_SELECTED` | Reading your videos; asked for in the video section only |
| `INTERNET` | Connecting to the network folders you add; the app contacts nothing else |
| `ACCESS_LOCAL_NETWORK` | The same, on Android 17 and newer, where reaching devices on the local network needs your consent |
| `POST_NOTIFICATIONS` | Showing the playback notification |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Playing while the app is in the background |
| `WAKE_LOCK` | Keeping playback going with the screen off |
| `ACCESS_NETWORK_STATE` | Finding out which Wi-Fi network the phone is on, to look for shared folders there |

## License

[MIT](LICENSE)
