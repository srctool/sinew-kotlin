# iosApp

The Xcode project for the iOS sample. Create it at S0 with Xcode (an iOS App target, SwiftUI), then:

1. Add a Run Script build phase before "Compile Sources":
   `cd "$SRCROOT/../.." && ./gradlew :sample:shared:embedAndSignAppleFrameworkForXcode -Psinew.devtools=${SINEW_DEVTOOLS:-false}`
2. Add `$(SRCROOT)/../shared/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)` to Framework Search Paths, and `-framework SinewSample` to Other Linker Flags.
3. Show `MainViewControllerKt.MainViewController()` from a `UIViewControllerRepresentable`.
4. Set `SINEW_DEVTOOLS=true` in the Debug and Staging configurations only.
