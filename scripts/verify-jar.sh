#!/bin/sh
# Verify that a HMCL-Region-Fix.jar is well formed and was built from the
# sources in this repository.
#
# Usage: scripts/verify-jar.sh [path/to/jar] [source-dir]

set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
JAR=${1:-"$ROOT/build/HMCL-Region-Fix.jar"}
SRC=${2:-"$ROOT/src"}

AGENT_CLASS="io.github.colorcard.hmcl.regionfix.RegionFixAgent"
GUI_CLASS="io.github.colorcard.hmcl.regionfix.RegionFixGui"
PKG_PATH="io/github/colorcard/hmcl/regionfix"

fail() {
    echo "verify-jar: $*" >&2
    exit 1
}

[ -f "$JAR" ] || fail "jar not found: $JAR"
[ -d "$SRC" ] || fail "source directory not found: $SRC"

case "$JAR" in
    /*) ;;
    *) JAR=$(CDPATH= cd -- "$(dirname "$JAR")" && pwd)/$(basename "$JAR") ;;
esac
case "$SRC" in
    /*) ;;
    *) SRC=$(CDPATH= cd -- "$SRC" && pwd) ;;
esac

WORK=$(mktemp -d "${TMPDIR:-/tmp}/hmcl-regionfix-verify.XXXXXX")
trap 'rm -rf "$WORK"' EXIT INT TERM

# 1. Basic ZIP/JAR sanity.
magic=$(dd if="$JAR" bs=4 count=1 2>/dev/null | od -An -tx1 | tr -d ' \n')
[ "$magic" = "504b0304" ] || fail "not a zip/jar file"

jar --list --file "$JAR" > "$WORK/entries.txt" \
    || fail "cannot list jar entries"

grep -qx 'META-INF/MANIFEST.MF' "$WORK/entries.txt" \
    || fail "missing META-INF/MANIFEST.MF"
grep -qx "$PKG_PATH/RegionFixAgent.class" "$WORK/entries.txt" \
    || fail "missing RegionFixAgent.class"
grep -qx "$PKG_PATH/RegionFixGui.class" "$WORK/entries.txt" \
    || fail "missing RegionFixGui.class"

# Only the manifest, package directories and classes are allowed. In
# particular this rejects nested jars, native libraries and signature files.
while IFS= read -r entry; do
    case "$entry" in
        META-INF/|META-INF/MANIFEST.MF) ;;
        io/|io/github/|io/github/colorcard/|io/github/colorcard/hmcl/) ;;
        "$PKG_PATH/") ;;
        "$PKG_PATH"/*.class)
            name=${entry##*/}
            base=${name%.class}
            case "$base" in
                *[!A-Za-z0-9_$]*|'') fail "unexpected class entry: $entry" ;;
            esac
            ;;
        *) fail "unexpected jar entry: $entry" ;;
    esac
done < "$WORK/entries.txt"

duplicates=$(sort "$WORK/entries.txt" | uniq -d)
[ -z "$duplicates" ] || fail "duplicate jar entries: $duplicates"

# 2. Manifest checks.
mkdir -p "$WORK/jar"
( cd "$WORK/jar" && jar --extract --file "$JAR" ) >/dev/null \
    || fail "cannot extract jar"
tr -d '\r' < "$WORK/jar/META-INF/MANIFEST.MF" > "$WORK/manifest.txt"

grep -qx "Main-Class: $GUI_CLASS" "$WORK/manifest.txt" \
    || fail "Main-Class is missing or wrong"
grep -qx "Premain-Class: $AGENT_CLASS" "$WORK/manifest.txt" \
    || fail "Premain-Class is missing or wrong"
if grep -qi '^Class-Path:' "$WORK/manifest.txt"; then
    fail "manifest must not contain Class-Path"
fi

# 3. Bytecode checks: Java 8 target, expected entry points.
javap -classpath "$JAR" -verbose "$AGENT_CLASS" > "$WORK/agent.txt" \
    || fail "cannot inspect RegionFixAgent"
grep -q 'major version: 52' "$WORK/agent.txt" \
    || fail "RegionFixAgent is not compiled for Java 8"
grep -Fq 'public static void premain(java.lang.String, java.lang.instrument.Instrumentation)' \
    "$WORK/agent.txt" || fail "RegionFixAgent.premain is missing"

javap -classpath "$JAR" -verbose "$GUI_CLASS" > "$WORK/gui.txt" \
    || fail "cannot inspect RegionFixGui"
grep -q 'major version: 52' "$WORK/gui.txt" \
    || fail "RegionFixGui is not compiled for Java 8"
grep -Fq 'public static void main(java.lang.String[]);' "$WORK/gui.txt" \
    || fail "RegionFixGui.main is missing"

# 4. The packaged classes must match a fresh compile of the current sources.
# This makes tampering with the jar detectable without trusting the build
# script.
mkdir -p "$WORK/classes"
find "$SRC" -name '*.java' -print0 \
    | xargs -0 javac --release 8 -Xlint:-options -encoding UTF-8 -d "$WORK/classes" \
    || fail "cannot recompile sources"
diff -r -x META-INF "$WORK/classes" "$WORK/jar" > "$WORK/classes.diff" 2>&1 || {
    cat "$WORK/classes.diff" >&2
    fail "jar classes do not match the current sources"
}

# 5. Smoke test: the GUI entry point starts in headless mode and exits.
if command -v timeout >/dev/null 2>&1; then
    timeout 60 java -Djava.awt.headless=true -jar "$JAR" --scan >/dev/null \
        || fail "headless smoke test failed"
else
    java -Djava.awt.headless=true -jar "$JAR" --scan >/dev/null \
        || fail "headless smoke test failed"
fi

echo "verify-jar: OK ($JAR)"
