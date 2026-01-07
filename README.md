# HandyCam
HandyCam is a Free Libre Open Source Software that works as a replacement client for the popular Smartphone Webcam Software Droidcam. It works in tandem with the [Official Droidcam OBS Plugin](https://github.com/dev47apps/droidcam-obs-plugin).
The purpose of HandyCam is to allow you to use any reasonably recent smartphone as a mobile webcam with OBS.

## Features
- Select any quality settings your camera supports
- Stream low latency video to OBS
- Remotely start/stop the stream and control the camera settings using the webinterface or the restAPI
- Runs in the background with the screen off
- Connection over Wifi or USB (USB Debugging has to be enabled on the phone)
  
## Installation
Either use the [compiled APK](https://github.com/TomOdellSheetMusic/HandyCam/releases) from the releases tab or build it yourself with the magic of open source! <br>
Then install the Droidcam OBS Plugin from [here](https://github.com/dev47apps/droidcam-obs-plugin/releases) or [here](https://droidcam.app/obs/#top) and connect via the Wifi IP of your smartphone or via USB by turning on Android USB Debugging. 

## Tested with
- Samsung S22 Android 15
- Samsung S20 Android 12
- Samsung A50 Android 11

## ToDo
- Add Wifi Discovery
- Clean up Code
- Stream Audio
- Remote Control Camera Zoom and Focus
- Auto-Disover Phone Camera Capabilites
- Document restAPI
- Squash Bugs
