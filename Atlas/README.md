# Atlas

An Android agent with local tools, persistent memory, scheduled goals and API-backed inference.

![Atlas — actual Android automation interface](docs/showcase/atlas-spread.jpg)

*Historical Android screenshot, presented without changing the UI. The enabled goal reports zero recorded runs; the image demonstrates configuration, not successful background execution.*

**[View the app showcase](https://mdsadman2004.github.io/#atlas)** · **[Original-size capture](docs/showcase/atlas-goals.jpg)**

## An agent on the phone, not a desktop relay

Atlas runs its orchestration, tools and state on Android. It can work with device information, files, web requests, accessibility actions, memory, skills and scheduled goals. Language-model inference is provided by the **external CommandCode API**; this is not a fully offline on-device model.

**[Download releases](https://github.com/MdSadman2004/Atlas/releases)** · **[Architecture and verification record](docs/ARCHITECTURE.md)**

## App preview

![Atlas automation screen](docs/phone-goals.png)

*Existing Android capture from this repository: an enabled Battery watchdog goal. This is not a fresh device test or a live battery measurement.*

## Build and install

The module declares **minSdk 26, compileSdk 35 and JVM target 17**. Use JDK 17, Android SDK 35 and a compatible Gradle installation.

```bash
git clone https://github.com/MdSadman2004/Atlas.git
cd Atlas
```

The wrapper launch scripts and properties are included, but the wrapper JAR is absent in the audited tree. Use an installed Gradle 8.9 distribution, or restore the wrapper from a trusted Gradle distribution first:

```bash
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Alternatively, sideload the APK from the release page; debug-signed builds are development distributions, not store-certified releases.

## First run and permissions

1. Enter your own CommandCode key in Setup and use the connection test.
2. Review app permissions; enable the Atlas AccessibilityService only if you need UI control.
3. Choose an approval mode before asking it to change files or operate another application. The source defaults to automatic approval (`off`); use `smart` or `manual` for more oversight.
4. Review battery restrictions before relying on background schedules.

[Agent prompt](app/src/main/java/com/atlas/agent/core/agent/Prompt.kt) · [tool registry](app/src/main/java/com/atlas/agent/core/tools/ToolRegistry.kt) · [settings](app/src/main/java/com/atlas/agent/core/store/Settings.kt).

## Source guide

| Component | File | Purpose |
| :-- | :-- | :-- |
| Agent loop | [app/src/main/java/com/atlas/agent/core/agent/AgentEngine.kt](app/src/main/java/com/atlas/agent/core/agent/AgentEngine.kt) | Plans turns and executes registered tools |
| Phone interaction | [app/src/main/java/com/atlas/agent/core/a11y/AtlasA11yService.kt](app/src/main/java/com/atlas/agent/core/a11y/AtlasA11yService.kt) | Accessibility tree, actions and screenshots |
| Scheduled goals | [app/src/main/java/com/atlas/agent/core/autonomy/Goals.kt](app/src/main/java/com/atlas/agent/core/autonomy/Goals.kt) | Persistent background-goal scheduling |

## Scope & limitations

API credentials, model access, internet connectivity and Android permissions are external prerequisites. Accessibility behavior varies by OS and application. Background scheduling is subject to Android policy. Historical hardware observations are recorded in docs/ARCHITECTURE.md, not re-certified by this README refresh. Do not place API keys or private screen content in issues.

## License

See [LICENSE](LICENSE) for the repository license. Third-party components retain their own terms.
