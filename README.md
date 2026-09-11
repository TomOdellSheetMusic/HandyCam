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

## Installation
Install the Droidcam OBS Plugin from [here](https://github.com/dev47apps/droidcam-obs-plugin/releases) or [here](https://droidcam.app/obs/#top) and connect via the Wifi IP of your smartphone or via USB by turning on Android USB Debugging. 

### Manual
Either use the [compiled APK](https://github.com/TomOdellSheetMusic/HandyCam/releases) from the releases tab or build it yourself with the magic of open source! <br>


### Obtainium

Android APKs are published to every release, and [Obtainium](https://obtainium.imranr.dev) keeps them updated straight from GitHub. Use the nightly channel to follow the rolling `nightly` tag, where prereleases and date-based version tracking have to be enabled.

### Stable

[![Add to Obtainium](https://img.shields.io/badge/Add_to_Obtainium-6750A3?style=for-the-badge)](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/TomOdellSheetMusic/HandyCam) <br>
[![Download APK](https://img.shields.io/badge/Download_APK-3DDC84?style=for-the-badge&logo=android)](https://github.com/TomOdellSheetMusic/HandyCam/releases/latest)

### Nightly

[![Add to Obtainium](https://img.shields.io/badge/Add_to_Obtainium-6750A3?style=for-the-badge)](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.example.handycam%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FTomOdellSheetMusic%2FHandyCam%22%2C%22author%22%3A%22TomOdellSheetMusic%22%2C%22name%22%3A%22HandyCam%20Nightly%22%2C%22preferredApkIndex%22%3A0%2C%22additionalSettings%22%3A%22%7B%5C%22about%5C%22%3A%5C%22A%20nightly%20build%20of%20HandyCam%5C%22%2C%5C%22includePrereleases%5C%22%3Atrue%2C%5C%22useLatestAssetDateAsReleaseDate%5C%22%3Atrue%2C%5C%22releaseDateAsVersion%5C%22%3Atrue%2C%5C%22versionDetection%5C%22%3Afalse%7D%22%2C%22overrideSource%22%3A%22GitHub%22%7D) <br>
[![Download APK](https://img.shields.io/badge/Download_APK-3DDC84?style=for-the-badge&logo=android)](https://github.com/TomOdellSheetMusic/HandyCam/releases/tag/nightly)

### Setup & install

1. Install [Obtainium](https://github.com/ImranR98/Obtainium/releases/latest).
2. Tap **Add to Obtainium** above. Stable opens the **Add App** page prefilled with `https://github.com/TomOdellSheetMusic/HandyCam`; nightly opens an import prompt, since it carries the prerelease and version-tracking settings the rolling `nightly` tag needs.

### Relevant variables and switching between Nightly and Stable

Relevant variable differences between Nightly and Stable:
- *Include prereleases* - `true` for Nightly, `false` for Stable
- *Fallback to older releases* - `false` for Nightly, `true` for Stable
- *Use latest asset upload as release date* - `true` for Nightly, `false` for Stable (this is due to Nightly builds being uploaded to one release instead of creating new ones)
- *Use release date as version string (pseudo-version)* - `true` for Nightly, `false` for Stable (this is due to Nightly builds being uploaded to one release instead of creating new ones)

## Tested with
- Samsung S22 Android 15
- Samsung S20 Android 12
- Samsung A50 Android 11

## ToDo
- Implement new HEVC feature from droidcam plugin
- Clean up Code
- Remote Control Camera Focus
- Auto-Disover Phone Camera Capabilites
- Document restAPI
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

## License

HandyCam is licensed using the GPL-3.0 license.

The included icon is courtesy of [janjf93](https://pixabay.com/vectors/camera-lens-photos-photography-1933338/)
(link back not required by author if used)
