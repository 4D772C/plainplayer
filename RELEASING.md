# Releasing PlainPlayer (signed, for Obtainium and GitHub)

Android installs an update only if it is signed with **the same key** as the installed app.
Debug builds are signed with a throw-away key of the machine that built them (and GitHub Actions
creates a new one on every run) – that is what causes "signature mismatch" in Obtainium.
Releases therefore use **one permanent release key**.

## 1. Create the release key (once)

On your PC, outside the project folder:

```bash
mkdir -p ~/keys
keytool -genkeypair -v \
  -keystore ~/keys/plainplayer-release.jks \
  -alias plainplayer -keyalg RSA -keysize 4096 -validity 36500
```

`keytool` comes with the JDK. Choose a strong password; name/organisation fields can be anything.

**Back the key up** (e.g. on the NAS and an offline USB stick) together with its passwords.
If it is lost, no update can ever be installed over the existing app again – only uninstall + reinstall.
Anyone who has it can sign updates in your name, so don't share it.

## 2. Local builds

Copy `keystore.properties.example` to `keystore.properties` and fill in path and passwords.
(`keystore.properties` and `*.jks` are in `.gitignore`.)

```bash
./gradlew assembleRelease
# → app/build/outputs/apk/release/PlainPlayer-<version>-release.apk
```

## 3. GitHub releases (Obtainium)

Add four repository secrets under *Settings → Secrets and variables → Actions*:

| Secret | Value |
|---|---|
| `PLAINPLAYER_KEYSTORE_BASE64` | output of `base64 -w0 ~/keys/plainplayer-release.jks` |
| `PLAINPLAYER_KEYSTORE_PASSWORD` | keystore password |
| `PLAINPLAYER_KEY_ALIAS` | `plainplayer` |
| `PLAINPLAYER_KEY_PASSWORD` | key password |

Then, for every release: raise `appVersionName`/`appVersionCode` in `app/build.gradle.kts`, commit, and

```bash
git tag v0.2.6
git push && git push --tags
```

The workflow `.github/workflows/release.yml` builds the signed APK and attaches it to a GitHub release.
It refuses to publish if the key secret is missing, so a randomly signed APK can't slip out again.
Upload only release APKs to GitHub – never debug builds.

## 4. Switching the phone over (once)

The installed app was signed with an old key, so it has to be **uninstalled once**, then installed again
via Obtainium. From then on, updates install normally.

Uninstalling deletes the app's data: playlists, audiobook progress, the saved queue and the chosen
music folder. Your music files are not affected; the library is rebuilt by scanning the folder again.
