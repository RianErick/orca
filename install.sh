#!/usr/bin/env bash
# Builds orca and installs the `orca` command.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PREFIX="${PREFIX:-$HOME/.local}"
BIN_DIR="$PREFIX/bin"
LIB_DIR="$PREFIX/share/orca"

find_jdk() {
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
        echo "$JAVA_HOME"
        return
    fi
    if command -v javac >/dev/null 2>&1; then
        dirname "$(dirname "$(readlink -f "$(command -v javac)")")"
        return
    fi
    # Portable JDK dropped into the project by a previous build.
    local bundled
    bundled="$(find "$ROOT/.jdk" -maxdepth 2 -name javac -type f 2>/dev/null | head -1 || true)"
    if [ -n "$bundled" ]; then
        dirname "$(dirname "$bundled")"
    fi
}

JDK="$(find_jdk)"
if [ -z "$JDK" ]; then
    echo "A JDK 21+ is required to build orca." >&2
    echo "Install it with:  sudo apt install openjdk-21-jdk" >&2
    exit 1
fi

echo "==> Building with JDK at $JDK"
JAVA_HOME="$JDK" "$ROOT/mvnw" -q package -DskipTests

JAR="$(ls -t "$ROOT"/target/orca-*.jar | grep -v original | head -1)"
if [ ! -f "$JAR" ]; then
    echo "Build finished but no jar was produced." >&2
    exit 1
fi

echo "==> Installing to $PREFIX"
install -Dm644 "$JAR" "$LIB_DIR/orca.jar"
install -Dm755 "$ROOT/bin/orca" "$BIN_DIR/orca"

echo "==> Done"
if ! command -v orca >/dev/null 2>&1; then
    echo
    echo "$BIN_DIR is not in your PATH. Add it with:"
    echo "  echo 'export PATH=\"\$HOME/.local/bin:\$PATH\"' >> ~/.bashrc && source ~/.bashrc"
else
    echo "Type 'orca' to start."
fi
