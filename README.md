# SDK-Pruner

Open-source Android SDK component auditor. Identifies embedded ad/analytics/push SDKs in any app, lets you review and selectively block their components via Intent Firewall, and keeps rules in sync across app updates. Root required. Apache-2.0.

开源 Android SDK 组件审计工具：识别应用内嵌的广告/统计/推送 SDK，基于 Intent Firewall 选择性禁用组件，支持规则订阅与更新后自动重应用。需要 Root。Apache-2.0。

## Features

- **Scan** — enumerate every third-party app's activities/services/receivers/providers and match them against a 1,900+ SDK ruleset (package prefixes + exact component anchors), with 4-level safety grading
- **Dual engine** — Intent Firewall (app never sees it, cannot self-recover) as primary, `pm disable` (survives updates) as secondary; switchable per action
- **Safety first** — mandatory pre-apply backup, one-tap restore, system-package whitelist hard-block, atomic rule writes
- **Rule subscription** — point the app at any snapshot-format URL (self-hosted or the official [sdk-pruner-rules](https://github.com/deserthouse/sdk-pruner-rules) repo); subscribed entries override the built-in snapshot by id
- **Auto reapply** — a foreground guard service re-applies your selected rules after app updates, picking up newly added components incrementally
- **Material 3 Expressive** UI, minSdk 31 (Android 12) → targetSdk 37

## Build

```bash
./gradlew assembleDebug
```

Toolchain: Gradle 9.5.1 / AGP 9.2.1 / Kotlin 2.2.10 / JDK 21.

## Rules

The ruleset lives in the companion repo [sdk-pruner-rules](https://github.com/deserthouse/sdk-pruner-rules) — PRs welcome. Every rule carries `sources[]` attribution and a confidence level.

## Safety & recovery

Every apply is preceded by an automatic backup (IFW rules + pm state). Restore from the
in-app "应急恢复" (Emergency recovery) dialog, or — if the UI is unavailable — clear all
IFW rules over adb:

```bash
adb shell am broadcast -a io.github.deserthouse.sdkpruner.action.CLEAR_IFW --ez confirm true
```

The whitelist hard-blocks system packages; the tool only ever touches third-party apps.

## License

Apache-2.0. Attributions for inherited datasets are listed in the rules repo's [NOTICE](https://github.com/deserthouse/sdk-pruner-rules/blob/main/NOTICE).

## AI usage & Disclaimer

Developed with heavy AI assistance under human direction and review — see [DISCLAIMER.md](DISCLAIMER.md) for the full AI usage statement and disclaimer (provided as-is; component blocking may affect app functionality; use at your own risk).
