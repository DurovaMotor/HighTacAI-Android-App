# Project Notes For Codex

## Java Environment

- This Android project uses Temurin OpenJDK 21.0.11 LTS.
- `JAVA_HOME` should be `C:\Users\ooo\.jdks\jdk-21.0.11+10`.
- Before running Java, Gradle, or Android build commands, make sure `C:\Users\ooo\.jdks\jdk-21.0.11+10\bin` is on `PATH`.
- If a Codex shell does not see Java, set it in PowerShell with:

```powershell
$env:JAVA_HOME = 'C:\Users\ooo\.jdks\jdk-21.0.11+10'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```
