# Release candidate checks

Record the app version, OBS and Droidcam plugin versions, device model, Android
version, connection type, and result for each run. Test the signed release APK,
including an upgrade from the previous published build.

- Connect and reconnect over Wi-Fi and USB; verify discovery and manual connection.
- Exercise camera and screen capture, MJPEG and AVC, audio and video, and available lenses.
- Check controls, preview, orientation, frame rate and resolution changes.
- Stream with the screen off and app in the background, including an extended session;
  check audio/video synchronization, heat, and recovery after network interruptions.
- Deny permissions, occupy the stream port, interrupt screen sharing, and confirm
  the app reports failure and releases the microphone, camera, and notification.
- Test on multiple Android versions and manufacturers, including the minimum supported
  version and current Android; record unsupported combinations and known issues.
- Verify release signing, version code, upgrade, APK installation and release notes.

The stream and web control server currently have no authentication or encryption.
Test only on trusted networks and include this limitation in release notes.
