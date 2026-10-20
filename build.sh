#!/bin/sh
set -eu
cd "$(dirname "$0")"

rm -rf build/classes
mkdir -p build/classes

javac --release 8 -Xlint:-options -encoding UTF-8 -d build/classes $(find src -name '*.java')

printf 'Main-Class: io.github.colorcard.hmcl.regionfix.RegionFixGui\nPremain-Class: io.github.colorcard.hmcl.regionfix.RegionFixAgent\n' > build/manifest.txt

# Allow reproducible builds when SOURCE_DATE_EPOCH is provided. jar only
# supports --date on newer JDKs, so fall back to the default timestamps.
JAR_DATE=
if [ -n "${SOURCE_DATE_EPOCH:-}" ] && jar --help 2>&1 | grep -q -- '--date'; then
    if date -u -r "$SOURCE_DATE_EPOCH" +%Y-%m-%dT%H:%M:%SZ >/dev/null 2>&1; then
        BUILD_DATE=$(date -u -r "$SOURCE_DATE_EPOCH" +%Y-%m-%dT%H:%M:%SZ)
    elif date -u -d "@$SOURCE_DATE_EPOCH" +%Y-%m-%dT%H:%M:%SZ >/dev/null 2>&1; then
        BUILD_DATE=$(date -u -d "@$SOURCE_DATE_EPOCH" +%Y-%m-%dT%H:%M:%SZ)
    else
        BUILD_DATE=
    fi
    if [ -n "$BUILD_DATE" ]; then
        JAR_DATE="--date=$BUILD_DATE"
    fi
fi

(
    cd build/classes
    if [ -n "$JAR_DATE" ]; then
        jar --create --file ../HMCL-Region-Fix.jar \
            --manifest ../manifest.txt "$JAR_DATE" -C . .
    else
        jar cfm ../HMCL-Region-Fix.jar ../manifest.txt .
    fi
)

echo "Built: build/HMCL-Region-Fix.jar"
