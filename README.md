# Seam for Android

The phone half of Seam. Pairs with Seam on your computer by scanning a QR code and sends
your notifications and battery status over Wi-Fi. **No USB debugging, no cable.**

## How it works

- Seam on the computer shows a QR code with a `seam://pair?...` link. The phone's camera
  opens it in this app, which stores the computer's address, certificate fingerprint and a
  one-time key.
- A foreground service keeps an encrypted connection (TLS 1.3, certificate pinned via the QR
  code, HMAC-authenticated) to the computer, and reconnects automatically.
- A notification listener forwards notifications and what's playing; battery changes are sent as they happen. The same notification access is what allows media controls.

Protocol: `protocol/PROTOCOL.md`, with test vectors shared with
[seam-desktop](https://github.com/JohnAI-dev/seam-desktop) so both sides stay compatible.

Kotlin on the Android framework, using AndroidX and well-known libraries where they fit.

## Install

Open the [latest release](../../releases/latest) on your phone, download the `.apk` and open it.
Releases are signed with a private key stored only as the GitHub secrets
`ANDROID_KEYSTORE_B64` (the keystore, base64) and `ANDROID_KEYSTORE_PASSWORD`, so only this
repo's release pipeline can produce APKs that update an installed Seam. Keep a backup of the
keystore and password: without them, existing installs can't be updated.

## Updates

Seam checks [GitHub Releases](../../releases/latest) every 6 hours, downloads a newer APK,
checks it is signed with the same key, and installs it. The first update asks you to allow
Seam to install updates (and to confirm once); after that, Android 12+ lets Seam update
itself without asking.

## How it gets built (autonomous)

1. **Open an issue** (as the repo owner) describing a bug or feature.
2. **Grok engineer** writes the change; `scripts/test.sh` runs JVM unit tests (including the
   shared protocol vectors), a full build and Android lint.
3. **Grok reviewer** approves or rejects; up to 3 attempts with feedback.
4. A PR is opened; **CI re-runs the tests and launches the app on an Android emulator**
   (`app/src/androidTest`), then it is **auto-merged**.
5. **Release**: a signed APK is published to GitHub Releases and the issue is closed with a link.

The agent can't modify `.github/`, `scripts/` or `protocol/`; tests run without access to secrets.
Issues from other people are ignored until the owner adds the `agent` label; `no-agent` keeps
the agent off an issue.
