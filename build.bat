@echo off
setlocal
cd /d "%~dp0"

if exist build rmdir /s /q build
mkdir build\classes

dir /s /b src\*.java > build\sources.txt
javac --release 8 -encoding UTF-8 -d build\classes @build\sources.txt
if errorlevel 1 goto fail

(
echo Main-Class: io.github.colorcard.hmcl.regionfix.RegionFixGui
echo Premain-Class: io.github.colorcard.hmcl.regionfix.RegionFixAgent
) > build\manifest.txt

pushd build\classes
jar cfm ..\HMCL-Region-Fix.jar ..\manifest.txt .
popd

echo Built: build\HMCL-Region-Fix.jar
exit /b 0

:fail
echo Build failed.
pause
exit /b 1
