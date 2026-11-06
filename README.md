# Wyrd client for Android

> This repository is a public mirror of the Wyrd Android client. It is synchronized with the development repository periodically.

The Android app of [Wyrd](https://wyrd.link), a fleet manager for corporate phones on GrapheneOS. The app manages the work profile on the phone and connects it to the organisation's Wyrd server.

Documentation: [docs.wyrd.link](https://docs.wyrd.link/en/).

## Requirements

The app runs on GrapheneOS, Android 13 or later. It needs a Wyrd server reachable over HTTPS.

## Building

```bash
./gradlew testDebugUnitTest assembleRelease
```

## License

MIT, see [LICENSE](LICENSE). Licenses of third-party components are in [app/licenses](app/licenses).
