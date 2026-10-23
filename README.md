# DLauncher

Android Minecraft Java launcher. GPL-3.0 if you merge it into a PojavLauncher fork.

## Get an APK from GitHub Actions
1. Create a repo, push this folder, open the **Actions** tab.
2. **Build DLauncher APK** runs on every push. Download `DLauncher-debug` from the run's artifacts.
   Tag `v0.1.0` and the APK is attached to a GitHub Release.
3. For the playable build: fork PojavLauncher, then run **Build rebranded Pojav fork** with your
   fork name. If the workflow fails, copy any extra steps from the fork's own `.github/workflows`.

See `docs/FORK_PLAN.md` for wiring `PojavLaunchBridge` to the Pojav runtime.

## v0.2 features
Installations (per-version profiles with RAM/JVM args and isolated game folders), Fabric install,
Microsoft device-code login (+ offline), 1.13+ and legacy launch arguments, Java-version detection,
patch-notes feed.

## Microsoft login setup
1. Azure portal -> App registrations -> New (accounts: personal Microsoft accounts), enable
   "Allow public client flows".
2. Mojang must approve your app ID for the Minecraft API (their app-registration form),
   or login_with_xbox returns 403.
3. GitHub repo -> Settings -> Secrets -> add `MS_CLIENT_ID`. The workflow injects it into the APK.
