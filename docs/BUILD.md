# Build DiPlay

Use this procedure to build the DiPlay app for an Android head unit.
Run the commands from the repository root, where `gradlew` and `settings.gradle.kts` are located.

## Select the application

This repository contains several Android applications.
Select `mobile` to build the main DiPlay app.

| Gradle module | Purpose | Debug APK |
| --- | --- | --- |
| `:mobile` | Main DiPlay app for Android head units | `mobile/build/outputs/apk/debug/mobile-debug.apk` |
| `:maphost` | Sample application that displays a map from DiPlay | `samples/maphost/build/outputs/apk/debug/maphost-debug.apk` |
| `:home` | Optional DiPlay Home launcher | `samples/home/build/outputs/apk/debug/home-debug.apk` |
| `:automotive` | Separate Android Automotive application inherited from xcertplay | `automotive/build/outputs/apk/debug/automotive-debug.apk` |

The `:common` and `:shared` modules are libraries.
The applications use these libraries.

If the installed app is **DiPlay map host**, you selected `:maphost` or its APK.
Build `:mobile` and select `mobile-debug.apk` to install the main app.
A command such as `./gradlew assembleDebug` can build more than one application.
Use the module name in each build command to select the required application.

## Prepare the build environment

Install these tools:

- JDK 25.
- Android SDK Platform 37 (`platforms;android-37.0`).
- Android SDK Build-Tools 36.0.0.
- Android SDK Platform-Tools.
- Android NDK 28.2.13676358.

Use the Gradle wrapper included in this repository.
The first build needs an internet connection to download dependencies.

1. Open the repository root in Android Studio.
2. Install the Android SDK packages with **Tools → SDK Manager**.
3. Set **Gradle JDK** to JDK 25 in the Android Studio Gradle settings.
4. Wait for Gradle sync to finish.

For a terminal build, set `JAVA_HOME` to your JDK 25 directory.
Set `ANDROID_HOME` to your Android SDK directory.
You can also specify the SDK directory in the root `local.properties` file:

```properties
sdk.dir=/absolute/path/to/Android/sdk
```

Keep `local.properties` outside Git.
Check the Java version before the build:

```sh
java -version
```

The output must identify Java 25.
On Windows, use `gradlew.bat` in place of `./gradlew` in the commands below.

## Build the main app

For a source build without runtime authentication assets:

```sh
./gradlew :mobile:assembleDebug
```

Check that this file exists:

```text
mobile/build/outputs/apk/debug/mobile-debug.apk
```

The debug application ID is `com.shihab.diplay.hudtest`.
The release application ID in this fork is `com.tencent.mm` for the Camry
package whitelist. Debug retains `com.shihab.diplay.hudtest` for the existing
HUD debug guards; it does not match the Camry whitelist entry.

Provider authorities and task affinities use `${applicationId}` and follow
the release package automatically. The Kotlin namespace and component class
names remain `com.shilapi.xcertplay`.

The source APK contains no accessory identity unless you supply runtime authentication assets.
Standalone CarPlay needs these assets to connect to an iPhone.
Use the car-test procedure below for that purpose.
Tests generate synthetic identities at runtime.
Keep test private keys outside Git.

### Use Android Studio

1. Open **Run → Edit Configurations**.
2. Select an **Android App** configuration, or create one.
3. Set **Module** to `mobile`.
4. Select this configuration in the toolbar.
5. Select the Android device.
6. Click **Run** to install the main debug app.

To build an APK without installation, run `:mobile:assembleDebug` in the Android Studio terminal.
Select the APK from the `mobile` output directory.

## Check the source build

Run the unit tests, lint checks, and main debug build:

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug
```

Check that Gradle reports `BUILD SUCCESSFUL`.
For the complete CI procedure, see [the Android workflow](../.github/workflows/android.yml).
That workflow also checks and builds the `home` and `maphost` applications.

## Build a standalone car-test APK

Prepare an external directory with these runtime authentication files:

```text
runtime-assets/
└── offline-mfi/
    ├── identity.pk8
    └── certificate.p7b
```

Use only the intended runtime files in this directory.
Keep the directory and its private key outside Git.
The build rejects unexpected credential containers in APK assets.

1. Set `DIPLAY_AUTH_ASSETS_DIR` to the absolute directory path.
2. Run the standalone debug task.

```sh
DIPLAY_AUTH_ASSETS_DIR=/absolute/path/to/runtime-assets ./gradlew :mobile:assembleStandaloneDebug
```

The task stops if either required file is absent or empty.
Its APK output is `mobile/build/outputs/apk/debug/mobile-debug.apk`.
Check both files in the APK against your selected local inputs:

- `assets/offline-mfi/identity.pk8`.
- `assets/offline-mfi/certificate.p7b`.

Use this APK for a standalone connection test.
Update the existing test app with the same signing key to keep its settings.
Different signing keys cannot update the same installed application.

## Build a release APK

### GitHub Actions: Camry installation test

This fork includes **Camry APK** under Actions. It is manually
started and creates a signed `com.tencent.mm` release APK without CarPlay runtime
authentication assets. Use it to test installation and startup, not iPhone connection.

Create an Android signing keystore once using Android Studio's **Generate Signed
Bundle / APK** wizard. Keep a local backup for future updates. In repository
**Settings → Secrets and variables → Actions**, add these repository secrets:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded bytes of your keystore file |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Signing key alias |
| `ANDROID_KEY_PASSWORD` | Signing key password |

On macOS, copy the encoded keystore to the clipboard without printing it:

```sh
base64 -i /absolute/path/to/your-signing.jks | pbcopy
```

Enable Actions in your fork if GitHub prompts you. Open **Actions → Camry APK → Run workflow** on `main`. Missing signing secrets stop
the run before compilation. After a successful run, download
**camry-install-test-apk** from **Artifacts** and unzip it. It contains
`DiPlay-com.tencent.mm-install-test.apk`, its SHA-256 checksum and a short usage note.
The workflow verifies the final package and APK signature before uploading.
The signing file is temporary and is not uploaded. Artifacts expire after 14 days.

### GitHub Actions: complete Camry CarPlay build

Use the same four signing secrets and select **Camry APK → Run workflow** on
`main`. Enable **include_carplay_authentication**. The workflow downloads the
upstream 0.2.15 APK and requires SHA-256
`4bf45f16d6b1ab0a61462b831014081f07240f5596c90ca6bf38fb43f9890511` before importing
its two runtime files into a temporary directory outside the repository.
The complete release APK must contain byte-identical copies of those files.
Neither the identity files nor your signing keystore are committed to Git.

After success, download **camry-full-apk** and unzip
`DiPlay-com.tencent.mm-full.apk`. This includes the same experimental identity
as upstream, not Apple certification or a guarantee of iPhone/vehicle support.
As with upstream, anyone receiving the APK can extract the bundled identity.

Update the existing installation with the same signing secrets and package:

```sh
adb install -r /absolute/path/to/DiPlay-com.tencent.mm-full.apk
```

Do not uninstall the installation-test app or reset its hotspot configuration.
This build keeps versionCode 34; an equal version code can replace the existing
APK with a matching certificate. Changing signing keys prevents in-place updates.

### Local release with CarPlay runtime authentication

Prepare the runtime assets as described in the car-test procedure.
Set these environment variables locally:

| Variable | Value |
| --- | --- |
| `DIPLAY_AUTH_ASSETS_DIR` | Absolute path to the runtime assets directory |
| `ANDROID_KEYSTORE_PATH` | Path to your Android signing keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Signing key alias |
| `ANDROID_KEY_PASSWORD` | Signing key password |

Keep the keystore and passwords outside Git.
Run the release checks and build:

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintRelease :mobile:assembleRelease
```

The output is `mobile/build/outputs/apk/release/mobile-release.apk`.
Before installation, verify the final package with Android SDK tools:

```sh
apkanalyzer manifest application-id mobile/build/outputs/apk/release/mobile-release.apk
apksigner verify --verbose mobile/build/outputs/apk/release/mobile-release.apk
```

The application ID must be exactly `com.tencent.mm`. This only targets the
package-name whitelist; it does not bypass existing-package signature checks
or establish vehicle compatibility. Inspect any existing WeChat package before
uninstalling or replacing it. Build future updates with the same local signing key.
The in-app update checker still points to upstream releases, whose package and
signature differ; use locally built releases to update this fork.

The APK contains the runtime identity you supplied.
Recipients can extract that identity from the APK.
The APK excludes the Android signing key.
See [the project notices](THIRD_PARTY_NOTICES.md) for the experimental identity used in public releases.

The public source archive matches the release tag.
It excludes runtime identities, signing keys, local configuration, and build output.
The retired `build-beta.py` helper is no longer part of the build procedure.

## Correct build problems

| Symptom | Action |
| --- | --- |
| Installed app is DiPlay map host | Select `mobile` in Android Studio. Build and install the APK from the `mobile` output directory. |
| Gradle cannot find the Android SDK | Set `ANDROID_HOME` or the `sdk.dir` value in `local.properties`. |
| A required SDK or NDK package is absent | Install the listed package with SDK Manager. |
| Java or Gradle JDK version is incorrect | Set the terminal JDK and Android Studio Gradle JDK to Java 25. |
| Source APK cannot start standalone CarPlay | Supply runtime authentication assets. Use `:mobile:assembleStandaloneDebug`. |
| Standalone task reports missing authentication files | Check the external directory path and both required files. |
| Android rejects an app update | Use the same application ID and signing key as the installed app. |

### Camry update channel

The `com.tencent.mm` Camry build checks `DarwinHo/DiPlay` releases and only offers
`DiPlay-com.tencent.mm-full.apk` with `SHA256SUMS.txt`. It never offers the upstream
APK or installation-only test APK. Foreground and background checks use the same
channel; cached upstream download metadata is discarded. Until a custom release
is published, obtain the verified custom APK from the Camry APK workflow artifacts.
