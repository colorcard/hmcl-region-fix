#!/bin/sh
# Verify the layout, permissions, checksums and jar inside a release ZIP.
#
# Usage: scripts/verify-package.sh <zip> <version>

set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)

ZIP=${1:-}
VERSION=${2:-}

fail() {
    echo "verify-package: $*" >&2
    exit 1
}

[ -n "$ZIP" ] || fail "missing zip path"
[ -n "$VERSION" ] || fail "missing version"
[ -f "$ZIP" ] || fail "zip not found: $ZIP"

DIST=$(CDPATH= cd -- "$(dirname "$ZIP")" && pwd)
ZIP_NAME=$(basename "$ZIP")
[ "$ZIP_NAME" = "HMCL-Region-Fix-v$VERSION.zip" ] \
    || fail "unexpected zip name: $ZIP_NAME"

WORK=$(mktemp -d "${TMPDIR:-/tmp}/hmcl-regionfix-package.XXXXXX")
trap 'rm -rf "$WORK"' EXIT INT TERM

# Reject absolute paths and path traversal before extracting.
unzip -Z1 "$ZIP" > "$WORK/zip-entries.txt" || fail "cannot list zip"
while IFS= read -r entry; do
    case "$entry" in
        /*|..|../*|*/..|*/../*) fail "unsafe zip entry: $entry" ;;
    esac
done < "$WORK/zip-entries.txt"

mkdir -p "$WORK/pkg"
unzip -q "$ZIP" -d "$WORK/pkg" || fail "cannot extract zip"

# The archive must be flat and contain exactly the expected files.
cat > "$WORK/expected.txt" <<'EOF'
HMCL-Region-Fix.jar
LICENSE
README.md
start-linux.sh
start-macos.command
start-windows.bat
EOF

find "$WORK/pkg" -mindepth 1 -maxdepth 1 -print \
    | sed "s|^$WORK/pkg/||" | sort > "$WORK/actual.txt"
sort "$WORK/expected.txt" > "$WORK/expected-sorted.txt"
diff -u "$WORK/expected-sorted.txt" "$WORK/actual.txt" \
    || fail "unexpected package contents"

if find "$WORK/pkg" -type l | grep -q .; then
    fail "package must not contain symbolic links"
fi

[ -x "$WORK/pkg/start-linux.sh" ] || fail "start-linux.sh is not executable"
[ -x "$WORK/pkg/start-macos.command" ] \
    || fail "start-macos.command is not executable"

# The package must ship the exact scripts and documents from this commit.
for file in README.md LICENSE start-linux.sh start-macos.command start-windows.bat; do
    cmp -s "$WORK/pkg/$file" "$ROOT/$file" \
        || fail "$file differs from the repository copy"
done

# Verify the embedded jar, including that its classes match src/.
"$ROOT/scripts/verify-jar.sh" "$WORK/pkg/HMCL-Region-Fix.jar" "$ROOT/src"

# Verify both published checksum files.
[ -f "$DIST/HMCL-Region-Fix.jar.sha256" ] || fail "missing jar checksum"
[ -f "$DIST/$ZIP_NAME.sha256" ] || fail "missing zip checksum"
[ -f "$DIST/SHA256SUMS" ] || fail "missing SHA256SUMS"

if command -v sha256sum >/dev/null 2>&1; then
    (cd "$DIST" && sha256sum -c SHA256SUMS >/dev/null) \
        || fail "checksum verification failed"
else
    (cd "$DIST" && shasum -a 256 -c SHA256SUMS >/dev/null) \
        || fail "checksum verification failed"
fi

echo "verify-package: OK ($ZIP)"
