#!/bin/sh
# Linux: chmod +x 双击启动.sh && ./双击启动.sh
cd "$(dirname "$0")" || exit 1

if [ ! -f "HMCL-Region-Fix.jar" ]; then
    echo "HMCL-Region-Fix.jar not found. Keep it next to this script."
    printf "Press Enter to exit..."
    read -r _
    exit 1
fi

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    exec "$JAVA_HOME/bin/java" -jar "HMCL-Region-Fix.jar"
fi

if command -v java >/dev/null 2>&1; then
    exec java -jar "HMCL-Region-Fix.jar"
fi

echo "Java not found. Please install Java 21 first:"
echo "https://adoptium.net/temurin/releases/?version=21"
printf "Press Enter to exit..."
read -r _
exit 1
