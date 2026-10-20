#!/bin/sh
set -eu
cd "$(dirname "$0")"

rm -rf build/classes
mkdir -p build/classes

javac --release 8 -encoding UTF-8 -d build/classes $(find src -name '*.java')

printf 'Main-Class: io.github.colorcard.hmcl.regionfix.RegionFixGui\nPremain-Class: io.github.colorcard.hmcl.regionfix.RegionFixAgent\n' > build/manifest.txt

(cd build/classes && jar cfm ../HMCL-Region-Fix.jar ../manifest.txt .)

echo "Built: build/HMCL-Region-Fix.jar"
