# HighTac AI Android App

Private standalone Android source for HighTac AI.

Related WeChat Mini Program repository:
[DurovaMotor/HighTacAI-MiniProgram](https://github.com/DurovaMotor/HighTacAI-MiniProgram).

## Overview

HighTac AI is an Android motorcycle parts assistant built with Kotlin and Jetpack Compose. It provides an AI advisor for parts questions, image-based consultation, and a price lookup workflow backed by bundled catalog data and JianDaoYun APIs.

## Features

- Chinese advisor chat for motorcycle parts, models, and maintenance questions.
- Image attachment support for visual parts consultation.
- Reasoning-depth selector for OpenAI Responses API requests.
- Price lookup mode with multi-field filters for code, Chinese name, English name, model, and brand.
- JianDaoYun-backed product and price search with incremental loading feedback.
- Full-screen HighTac AI splash image shown at app launch.

## Tech Stack

- Kotlin
- Jetpack Compose
- Android Gradle Plugin
- OkHttp
- Coil Compose
- OpenAI Responses API
- JianDaoYun API

## Local Setup

This project uses Java 17+ and is configured with the Gradle wrapper.

Create or update `local.properties` for local-only secrets:

```properties
OPENAI_API_KEY=your_openai_api_key
JIANDAOYUN_API_KEY=your_jiandaoyun_api_key
JIANDAOYUN_APP_ID=your_jiandaoyun_app_id
JIANDAOYUN_ENTRY_ID=your_jiandaoyun_entry_id
JIANDAOYUN_BASE_URL=https://api.jiandaoyun.com/api
```

Do not commit `local.properties`; it is ignored by Git.

## Build

```powershell
.\gradlew.bat :app:assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The debug build uses the `.next` application ID suffix.

## Tests

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

See `TEST_CHECKLIST.md` for manual device testing steps and logcat collection tips.
