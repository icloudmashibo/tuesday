# TUESDAY v2

Voice assistant for your own phone. Say "Tuesday" then a command, or just chat.

## Build (easiest, from the phone)
1. Make a free GitHub repo, upload this whole folder (keep `.github/workflows/build.yml`).
2. Repo > Actions > "Build TUESDAY" > Run workflow. In about 4 minutes download the `TUESDAY-debug-apk` artifact.
3. Unzip, install the APK (allow "install unknown apps" for your browser/files app).

## Build in Termux
`pkg install openjdk-17 gradle aapt2`, uncomment the aapt2 line in `gradle.properties`,
make sure an Android SDK with platform 34 is set via `ANDROID_HOME`, then `gradle assembleDebug`.

## What it does
- Wake word "Tuesday", 12 s follow-up window, speaks replies, vibrates on wake and reply
- Brightness, volume, open app, Home/Back/Recents, lock screen (device admin or accessibility)
- Game mode: spots games (or apps you say "treat X as heavy"), dims the screen, dims more if hot; restores after
- "Organize my downloads": previews, asks yes/no, sorts loose files into folders, never deletes, "undo" reverts
- "Remember that ..." and a tally of what you ask most feed into its personality prompt
- Sensitive commands need the phone unlocked, plus your passphrase if you set one

## Not in v2
- Voiceprint (only-your-voice) needs a speaker-embedding model: planned
- Noise cancellation, live chess: planned
- Unlocking the lock screen: Android doesn't allow it for apps
