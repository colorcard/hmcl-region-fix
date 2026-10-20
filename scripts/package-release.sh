#!/bin/sh
# Build the release ZIP and checksums from a verified jar.
#
# Usage: scripts/package-release.sh <version> <jar> <output-dir>
#
# <version> must be the version without the leading "v", e.g. 1.0.1.
# Output: dist/HMCL-Region-Fix-v<version>.zip, .sha256 files and SHA256SUMS.

set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)

VERSION=${1:-}
JAR=${2:-}
OUT=${3:-"$ROOT/dist"}

fail() {
    echo "package-release: $*" >&2
    exit 1
}

[ -n "$VERSION" ] || fail "missing version"
[ -n "$JAR" ] || fail "missing jar path"
[ -f "$JAR" ] || fail "jar not found: $JAR"

case "$OUT" in
    /|.|..) fail "refusing unsafe output directory: $OUT" ;;
esac

printf '%s' "$VERSION" \
    | grep -Eq '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[0-9A-Za-z][0-9A-Za-z.-]*)?$' \
    || fail "invalid version: $VERSION"

ZIP_NAME="HMCL-Region-Fix-v$VERSION.zip"

mkdir -p "$OUT"
OUT_ABS=$(CDPATH= cd -- "$OUT" && pwd)
[ "$OUT_ABS" != "$ROOT" ] || fail "refusing to use the repository root as output"
[ "$OUT_ABS" != "/" ] || fail "refusing to use the filesystem root as output"
rm -rf "$OUT_ABS"
mkdir -p "$OUT_ABS"

# Keep the staging directory outside of the output directory so that only
# release assets are uploaded.
STAGE="$ROOT/build/package"
rm -rf "$STAGE"
mkdir -p "$STAGE"

cp "$JAR" "$STAGE/HMCL-Region-Fix.jar"
cp "$ROOT/README.md" "$ROOT/LICENSE" \
   "$ROOT/start-windows.bat" "$ROOT/start-macos.command" "$ROOT/start-linux.sh" \
   "$STAGE/"

chmod 755 "$STAGE/start-linux.sh" "$STAGE/start-macos.command"
chmod 644 "$STAGE/HMCL-Region-Fix.jar" "$STAGE/README.md" "$STAGE/LICENSE" \
    "$STAGE/start-windows.bat"

# Make timestamps deterministic when the caller provides SOURCE_DATE_EPOCH.
if [ -n "${SOURCE_DATE_EPOCH:-}" ]; then
    if date -u -r "$SOURCE_DATE_EPOCH" +%Y%m%d%H%M.%S >/dev/null 2>&1; then
        STAMP=$(date -u -r "$SOURCE_DATE_EPOCH" +%Y%m%d%H%M.%S)
    elif date -u -d "@$SOURCE_DATE_EPOCH" +%Y%m%d%H%M.%S >/dev/null 2>&1; then
        STAMP=$(date -u -d "@$SOURCE_DATE_EPOCH" +%Y%m%d%H%M.%S)
    else
        STAMP=
    fi
    if [ -n "$STAMP" ]; then
        TZ=UTC touch -t "$STAMP" \
            "$STAGE/HMCL-Region-Fix.jar" "$STAGE/README.md" "$STAGE/LICENSE" \
            "$STAGE/start-windows.bat" "$STAGE/start-macos.command" \
            "$STAGE/start-linux.sh"
    fi
fi

(
    cd "$STAGE"
    TZ=UTC zip -X -q -9 "$OUT_ABS/$ZIP_NAME" \
        HMCL-Region-Fix.jar LICENSE README.md \
        start-linux.sh start-macos.command start-windows.bat
)

cp "$JAR" "$OUT_ABS/HMCL-Region-Fix.jar"

hash_file() {
    if command -v sha256sum >/dev/null 2>&1; then
        (cd "$OUT_ABS" && sha256sum "$1")
    else
        (cd "$OUT_ABS" && shasum -a 256 "$1")
    fi
}

hash_file "HMCL-Region-Fix.jar" > "$OUT_ABS/HMCL-Region-Fix.jar.sha256"
hash_file "$ZIP_NAME" > "$OUT_ABS/$ZIP_NAME.sha256"
cat "$OUT_ABS/HMCL-Region-Fix.jar.sha256" "$OUT_ABS/$ZIP_NAME.sha256" \
    > "$OUT_ABS/SHA256SUMS"

rm -rf "$STAGE"

echo "package-release: created $OUT_ABS/$ZIP_NAME"
