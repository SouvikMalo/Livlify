# Livlify — Android music player

A native offline Android music player starter built with Kotlin, Jetpack Compose and AndroidX Media3.

## Features in this first build
- Reads local MP3 and M4A/AAC music using Android MediaStore.
- Search by title, artist or album.
- Play/pause, next/previous, seek, shuffle and repeat-one.
- Background playback through MediaSessionService with Android media notification controls.
- Local favourites (in-memory for this first starter session; persistence is a planned next step).
- Dark interface designed to stay lightweight.

## Open in VS Code
1. Install a JDK 17 (or newer compatible JDK), Android SDK Platform 36 and Android SDK Build Tools.
2. Install the Android command-line tools and set `ANDROID_HOME` to your Android SDK directory.
3. Open this folder in VS Code.
4. In a terminal, from this folder, run `gradle wrapper --gradle-version 8.13` once if you have Gradle installed. This generates the wrapper scripts and wrapper JAR.
5. Then run `./gradlew assembleDebug` (Windows PowerShell: `./gradlew.bat assembleDebug`).
6. Install `app/build/outputs/apk/debug/app-debug.apk` on your phone.

You can also open the folder in Android Studio and let it sync Gradle, then build the debug APK.

## Notes
- Android asks for audio permission at first launch. Allow it so Livlify can list local tracks.
- Put music in the phone's Music folder, then tap refresh in Livlify.
- Media3/ExoPlayer uses the Android platform decoders. MP3 and M4A support depends on the codec/container supported by the device.
- This is a starter v1 project, not yet a signed release build. The debug APK is suitable for local testing.
