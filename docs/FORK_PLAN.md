# DLauncher: fork plan (PojavLauncher base)

PojavLauncher already ships the hard parts: an Android build of OpenJDK, LWJGL/GL bridging
(gl4es, Zink, etc.), and input mapping. DLauncher = rebranded fork + your own UI/UX.
I haven't compiled any of this; verify class and path names against the current Pojav source.

## 1. Legal first
- Pojav is **GPL-3.0**. Your fork must stay GPL-3.0, publish source, keep copyright notices and credit.
- Never bundle Minecraft jars/assets; download them from Mojang at runtime (as the code here does).
- Register your **own** Microsoft/Azure app for login instead of reusing Pojav's client ID.

## 2. Fork and build
1. Fork `PojavLauncherTeam/PojavLauncher` on GitHub, clone with `--recursive`.
2. Install JDK 17+, Android Studio, SDK + NDK (versions in Pojav's README).
3. `./gradlew assembleDebug` and confirm the stock app runs on an arm64 device first.
4. Follow the README steps for the bundled JRE components (the `jre*` runtimes in assets).

## 3. Rebrand (do in this order)
1. `applicationId` -> `net.dlauncher.app`, `app_name` -> DLauncher, new icons/colors.
2. Do NOT rename the Java package `net.kdt.pojavlaunch` at first: native JNI symbols are
   named after it, so renaming means editing the C sources too. Do it later if you must.
3. Change About/credits screens and update links; keep the Pojav attribution.

## 4. Plug in the DLauncher UI
- Add the `dlauncher-ui` module (the Kotlin files here) with Compose + OkHttp + kotlinx-serialization.
- Replace Pojav's launcher home screen with `DLauncherScreen`, or open it from the existing
  main activity.
- Implement `LaunchBridge` by calling Pojav's launch path (its JRE launch utilities and the
  game-args builder). Pojav also swaps LWJGL natives for its Android ones, so reuse its
  library handling rather than the plain classpath built here.

## 5. Gradle deps for the UI module
```
implementation("com.squareup.okhttp3:okhttp:4.12.0")
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
implementation(platform("androidx.compose:compose-bom:2024.10.00"))
implementation("androidx.compose.material3:material3")
```
Plugin: `org.jetbrains.kotlin.plugin.serialization`.
