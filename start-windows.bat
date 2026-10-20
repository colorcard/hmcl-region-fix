@echo off
cd /d "%~dp0"

if not exist "HMCL-Region-Fix.jar" (
    echo HMCL-Region-Fix.jar not found. Keep it next to this .bat.
    pause
    exit /b 1
)

if exist "%JAVA_HOME%\bin\javaw.exe" (
    start "" "%JAVA_HOME%\bin\javaw.exe" -jar "HMCL-Region-Fix.jar"
    exit /b
)

where javaw.exe >nul 2>nul
if not errorlevel 1 (
    start "" javaw.exe -jar "HMCL-Region-Fix.jar"
    exit /b
)

where java.exe >nul 2>nul
if not errorlevel 1 (
    java.exe -jar "HMCL-Region-Fix.jar"
    pause
    exit /b
)

echo Java not found. Please install Java 21 first.
start "" "https://adoptium.net/temurin/releases/?version=21"
pause
