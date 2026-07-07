# HighTac AI Internal Test Checklist

## Configure API Key

Add your real API key to `local.properties`:

```properties
OPENAI_API_KEY=你的真实APIKey
```

Do not commit `local.properties`.

## Build

```bash
./gradlew assembleDebug
```

On Windows PowerShell, you can also run:

```powershell
.\gradlew.bat assembleDebug
```

## Device Test Steps

1. Install the app.
2. Open the app.
3. Test plain text: send `Hello`.
4. Test Chinese text: send `你好`.
5. Test gallery image: choose one motorcycle parts image and send it.
6. Test camera: take one photo and send it.
7. Test image + text: choose an image, enter `请识别这个配件`, then send.
8. Test cancelling gallery selection.
9. Test cancelling camera capture.
10. Test sending while the phone is offline.

## If A Test Fails, Collect

- Phone model
- Android version
- Exact operation steps
- Logcat logs with tag `HighTacAI`
- Error screenshot

## Logcat

Use this filter while testing:

```bash
adb logcat -s HighTacAI
```
