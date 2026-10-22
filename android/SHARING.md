Home Voice — personal Android preview

Works on Pixel Tablet and other ARM64 Android devices running Android 13 or newer.

1. Send home-voice-<version>-arm64.apk to the device and open it. Android may ask you
   to allow installing apps from the app you used to open the file. Install Home Voice.
2. Open Home Voice. Enter:
   Agent URL: https://home-voice-agent.cjols.workers.dev
   Device token: the same device token configured on Christopher's phone.
   Christopher can enter the token during setup; it isn't included in this APK.
3. Tap Save & start. Allow microphone access and notifications.
4. Use the app's permission buttons to grant Display over other apps and disable
   battery optimisation, then return to Home Voice.
5. Say “Hey Jarvis” followed by your question. An internet connection is required.

The square button at the bottom stops a spoken reply. When it changes to the
crossed conversation bubble, tapping it ends listening. You can also stop the
listener entirely with Stop listening in its expanded notification.

Tables stay open until closed with their X. Ending a conversation leaves a table
open so you can continue it later. Each device has its own conversation history.

Automatic updates
-----------------
Every push to main that changes the app publishes a GitHub release of
cjol/home-voice (tag v0.1.<n>) with the APK attached. To pick them up:

1. Install Obtainium (https://github.com/ImranR98/Obtainium/releases) on the device.
2. The repository is private, so create a fine-grained GitHub token with read-only
   "Contents" access to cjol/home-voice only. In Obtainium's settings, paste it
   as the GitHub personal access token.
3. Add app: https://github.com/cjol/home-voice. Obtainium matches the release
   version with the installed one and offers each new release. Allow it to install
   unknown apps; on Android 12+ it can then update in the background.

The first install from Obtainium may need a confirmation tap if the app was
originally installed from a file.

This is a personal sideload build signed with Christopher's existing development
key. Keep that signing key for future updates. This build excludes debug controls.

Building another share APK
-------------------------
From the repository root, run `android/scripts/build-share.sh` with JDK 17.
The output is in `dist/`. The three downloaded wake-word models must already be
present under android/app/src/main/assets/wakeword/ (run fetch-models.sh if needed).
The share build targets ARM64, includes the wake-word models, and is not debuggable.
It uses this machine's existing debug signing key so it can update the installed
personal preview. It is intended for personal sideloading rather than Play Store distribution.
