# HandyCam
HandyCam is a Free Libre Open Source Software that works as a replacement client for the popular Smartphone Webcam Software Droidcam. It works in tandem with the [Official Droidcam OBS Plugin](https://github.com/dev47apps/droidcam-obs-plugin).
The purpose of HandyCam is to allow you to use any reasonably recent smartphone as a mobile webcam with OBS.

## Features
- Select any quality settings your camera supports
- Stream low latency video to OBS
- Stream audio to OBS
- Stream screen to OBS
- Automatic Discovery of running HandyCam Servers in the Droidcam OBS Plugin
- Remotely start/stop the stream and control the camera settings using the webinterface or the restAPI
- Runs in the background with the screen off
- Connection over Wifi or USB (USB Debugging has to be enabled on the phone)
- Dark and light Theme depending on System Theme

Note: It's not possible to start the camera stream from the api if the app is not in the foreground <br>

## Network privacy

Use HandyCam only on a trusted network. While streaming, any device that can reach the streaming port can access the video and audio without authentication or encryption. The optional web control server also uses unauthenticated HTTP. Anyone who can reach it can control the camera. USB connections do not disable the Wi-Fi listener. Stop streaming and the web control server when they are not needed.

## Installation
Either use the [compiled APK](https://github.com/TomOdellSheetMusic/HandyCam/releases) from the releases tab or build it yourself with the magic of open source! <br>
Then install the Droidcam OBS Plugin from [here](https://github.com/dev47apps/droidcam-obs-plugin/releases) or [here](https://droidcam.app/obs/#top) and connect via the Wifi IP of your smartphone or via USB by turning on Android USB Debugging. 

For HDR, select HEVC and HDR in HandyCam before starting the camera stream, then select HEVC and enable HDR in the DroidCam OBS source. HDR requires Android 13 or newer, an HLG10-capable camera, and a compatible HEVC Main10 encoder at the chosen settings. Camera preview and screen capture are unavailable in HDR mode. The HDR option resets when the app process restarts.

### Obtainium

Android APKs are published to every release, and [Obtainium](https://obtainium.imranr.dev) keeps them updated straight from GitHub. Use the nightly channel to follow the rolling `nightly` tag, where prereleases and date-based version tracking have to be enabled.

### Stable

[![Add to Obtainium](https://img.shields.io/badge/Add_to_Obtainium-6750A3?style=for-the-badge)](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/TomOdellSheetMusic/HandyCam) <br>
[![Download APK](https://img.shields.io/badge/Download_APK-3DDC84?style=for-the-badge&logo=android)](https://github.com/TomOdellSheetMusic/HandyCam/releases/latest)

### Nightly

[![Add to Obtainium](https://img.shields.io/badge/Add_to_Obtainium-6750A3?style=for-the-badge)](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.example.handycam%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FTomOdellSheetMusic%2FHandyCam%22%2C%22author%22%3A%22TomOdellSheetMusic%22%2C%22name%22%3A%22HandyCam%20Nightly%22%2C%22preferredApkIndex%22%3A0%2C%22additionalSettings%22%3A%22%7B%5C%22about%5C%22%3A%5C%22A%20nightly%20build%20of%20HandyCam%5C%22%2C%5C%22includePrereleases%5C%22%3Atrue%2C%5C%22useLatestAssetDateAsReleaseDate%5C%22%3Atrue%2C%5C%22releaseDateAsVersion%5C%22%3Atrue%2C%5C%22versionDetection%5C%22%3Afalse%2C%5C%22fallbackToOlderReleases%5C%22%3Atrue%2C%5C%22filterReleaseTitlesByRegEx%5C%22%3A%5C%22%5ENightly%20%5C%5C%5C%5C%28dev%5C%5C%5C%5C%29%24%5C%22%7D%22%2C%22overrideSource%22%3A%22GitHub%22%7D) <br>
[![Download APK](https://img.shields.io/badge/Download_APK-3DDC84?style=for-the-badge&logo=android)](https://github.com/TomOdellSheetMusic/HandyCam/releases/tag/nightly)

### Setup & install

1. Install [Obtainium](https://github.com/ImranR98/Obtainium/releases/latest).
2. Tap **Add to Obtainium** above. Stable opens the **Add App** page prefilled with `https://github.com/TomOdellSheetMusic/HandyCam`; nightly opens an import prompt with the settings needed for the rolling release.

If you already added the nightly build, edit its Obtainium settings: enable **Include prereleases**, **Fallback to older releases**, **Use latest asset upload as release date**, and **Use release date as version string**; set **Filter release titles by regular expression** to `^Nightly \(dev\)$` and disable version detection. Both channels use the same Android app ID, so switching channels updates the same installed app.


## Tested with
- Samsung S22 Android 15
- Samsung S20 Android 12
- Samsung A50 Android 11

## ToDo
- Remote Control Camera Focus
- Squash Bugs

## Build Instructions
The easiest way to get going is to download and install [Android Studio](https://developer.android.com/studio) and use its inbuilt Version Control to clone/fork the HandyCam Repository. This should just work out of the box. Alternatively, you can use an IDE like VSCode and download the Android and JAVA JDKs separately. Then you need to point your system to them, for example:

```
[Environment]::SetEnvironmentVariable(
  "JAVA_HOME",
  "C:\Your\Path\To\Java\jdk-17.0.2",
  "User"
)

[Environment]::SetEnvironmentVariable(
  "ANDROID_HOME",
  "C:\You\Path\To\android-sdk",
  "User"
)       
```
and you should be able to run ./gradlew.bat. 

Dependency versions are declared in `gradle/libs.versions.toml`. Dependabot proposes
Gradle and GitHub Actions updates weekly. The dependency workflow builds and tests
changes, reviews pull requests, and submits the resolved Gradle graph on pushes to
`main` and `dev`. Dependency review requires GitHub's dependency graph to be enabled.

## License

HandyCam is licensed using the GPL-3.0 license.

The included icon is courtesy of [janjf93](https://pixabay.com/vectors/camera-lens-photos-photography-1933338/)
(link back not required by author if used)
