# Nothing Launcher

A small, icon-free Android home-screen launcher focused on keeping idle work and UI overhead low.

## Design

- Native Android views only; no UI framework or third-party runtime dependencies.
- Queries and sorts launchable apps on a single background worker at startup, then refreshes only when installed apps change.
- Filters cached app labels as you type.
- Displays text-only rows and does not load or retain app icons.
- Has no services, polling, animations, network access, or periodic jobs.
- Supports launching apps, opening app info with a long press, and choosing it as the Android HOME app.

Kotlin is used for implementation, but the language alone does not make an app faster or smaller. The main savings come from doing less work and avoiding unnecessary resources and dependencies. Release builds enable R8 shrinking and resource shrinking.

## Build

Open this project in Android Studio or build it with the Gradle wrapper:

```sh
./gradlew assembleDebug
```

Minimum Android version: Android 7.0 (API 24).
