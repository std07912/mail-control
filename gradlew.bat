@echo off
where gradle >nul 2>nul
if errorlevel 1 (
  echo Install Gradle 8.13, add its bin directory to PATH, then rerun.
  echo See README.md for Android Studio and standard wrapper setup.
  exit /b 1
)
gradle %*
