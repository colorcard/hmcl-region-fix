#!/bin/sh
# Run the agent smoke tests against a built HMCL-Region-Fix.jar.
#
# Usage: tests/run.sh [path/to/HMCL-Region-Fix.jar]

set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
JAR=${1:-"$ROOT/build/HMCL-Region-Fix.jar"}
CLASSES="$ROOT/build/test-classes"

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

[ -f "$JAR" ] || fail "jar not found: $JAR"

rm -rf "$CLASSES"
mkdir -p "$CLASSES"

find "$ROOT/tests/src" -name '*.java' -print0 \
    | xargs -0 javac --release 8 -Xlint:-options -encoding UTF-8 -d "$CLASSES"

PROPS="-Dregionfix.test.timezone=Asia/Shanghai -Dregionfix.test.country=CN"

echo "== negative control: without agent the stub reports mainland network"
java $PROPS -cp "$CLASSES" AgentSmokeTest true

echo "== reflection path: agent clears the cached field"
java $PROPS -javaagent:"$JAR" -cp "$CLASSES" AgentSmokeTest false

echo "== bytecode fallback: agent rewrites RegionUtils.class"
java $PROPS -Dhmcl.regionfix.no_reflect=true \
    -javaagent:"$JAR" -cp "$CLASSES" AgentSmokeTest false

echo "All agent tests passed."
