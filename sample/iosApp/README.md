# iosApp

The Xcode project for the iOS sample: a SwiftUI app that shows `SampleApp()` from `:sample:shared` through `MainViewControllerKt.MainViewController()`.

Open `iosApp.xcodeproj` in Xcode and run the `iosApp` scheme on a simulator. The "Compile Kotlin Framework" build phase runs `./gradlew :sample:shared:embedAndSignAppleFrameworkForXcode` first. Xcode doesn't inherit your shell's `JAVA_HOME`, so the phase falls back to Android Studio's bundled JDK.

From the command line:

```bash
xcodebuild -project sample/iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' build
```

Devtools stay off unless `SINEW_DEVTOOLS=true` is set for a build: set it in Debug and Staging configurations only.
