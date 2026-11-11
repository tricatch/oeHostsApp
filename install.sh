#!/bin/sh
# Installs (or updates) oeHubApp from the GitHub release.
#
#   curl -fsSL https://raw.githubusercontent.com/tricatch/oeHubApp/master/install.sh | sh
#   curl -fsSL https://raw.githubusercontent.com/tricatch/oeHubApp/master/install.sh | sh -s -- --uninstall
#
# Environment:
#   OEHUBAPP_VERSION  release tag to install (default: the latest release)
#   OEHUBAPP_HOME     where the jar goes   (default: ~/.oehubapp)
#   OEHUBAPP_BIN      where the command goes (default: ~/.local/bin)
set -eu

REPO="tricatch/oeHubApp"
APP_HOME="${OEHUBAPP_HOME:-$HOME/.oehubapp}"
BIN_DIR="${OEHUBAPP_BIN:-$HOME/.local/bin}"
LAUNCHER="$BIN_DIR/oehubapp"
MIN_JAVA=21

say() { printf '%s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

if [ "${1:-}" = "--uninstall" ]; then
    rm -f "$APP_HOME/oehubapp.jar" "$APP_HOME/oehubapp.log" "$LAUNCHER"
    rmdir "$APP_HOME" 2>/dev/null || true
    say "oeHubApp removed. Your data folder was kept:"
    case "$(uname -s)" in
        Darwin) say "  ~/Library/Application Support/oeHubApp" ;;
        *) say "  ~/.config/oeHubApp" ;;
    esac
    exit 0
fi

command -v curl >/dev/null 2>&1 || die "curl is required"

if [ -n "${OEHUBAPP_VERSION:-}" ]; then
    BASE="https://github.com/$REPO/releases/download/$OEHUBAPP_VERSION"
else
    BASE="https://github.com/$REPO/releases/latest/download"
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT INT TERM

say "Downloading oeHubApp${OEHUBAPP_VERSION:+ $OEHUBAPP_VERSION} ..."
curl -fL --progress-bar -o "$TMP/oehubapp.jar" "$BASE/oehubapp.jar" || die "download failed: $BASE/oehubapp.jar"
curl -fsSL -o "$TMP/SHA256SUMS.txt" "$BASE/SHA256SUMS.txt" || die "download failed: $BASE/SHA256SUMS.txt"

expected="$(awk '$2 ~ /oehubapp\.jar$/ { print $1 }' "$TMP/SHA256SUMS.txt")"
[ -n "$expected" ] || die "no checksum for oehubapp.jar in SHA256SUMS.txt"
if command -v shasum >/dev/null 2>&1; then
    actual="$(shasum -a 256 "$TMP/oehubapp.jar" | awk '{ print $1 }')"
elif command -v sha256sum >/dev/null 2>&1; then
    actual="$(sha256sum "$TMP/oehubapp.jar" | awk '{ print $1 }')"
else
    die "shasum or sha256sum is required to verify the download"
fi
[ "$actual" = "$expected" ] || die "checksum mismatch (expected $expected, got $actual)"

mkdir -p "$APP_HOME" "$BIN_DIR"
mv -f "$TMP/oehubapp.jar" "$APP_HOME/oehubapp.jar"

# The launcher looks for Java each time it runs, so a Java installed or upgraded later is found.
# Homebrew's openjdk is keg-only (not on PATH), hence the extra places.
cat > "$LAUNCHER" <<'LAUNCHER_EOF'
#!/bin/sh
# oeHubApp launcher (written by install.sh). Starts the app in the background; the log goes to
# __APP_HOME__/oehubapp.log. Arguments are passed to the app (e.g. --data DIR).
JAR="__APP_HOME__/oehubapp.jar"
MIN_JAVA=__MIN_JAVA__

java_major() {
    v="$("$1" -version 2>&1 | sed -n 's/.*version "\([^"]*\)".*/\1/p' | head -n 1)"
    case "$v" in
        1.*) v="${v#1.}" ;;
    esac
    v="${v%%[!0-9]*}"
    echo "${v:-0}"
}

find_java() {
    for j in \
        "${JAVA_HOME:+$JAVA_HOME/bin/java}" \
        "$(/usr/libexec/java_home -v "$MIN_JAVA+" 2>/dev/null)/bin/java" \
        /opt/homebrew/opt/openjdk/bin/java \
        /usr/local/opt/openjdk/bin/java \
        "$(command -v java 2>/dev/null)"; do
        [ -n "$j" ] && [ -x "$j" ] || continue
        [ "$(java_major "$j")" -ge "$MIN_JAVA" ] 2>/dev/null && { echo "$j"; return 0; }
    done
    return 1
}

if [ "${1:-}" = "--find-java" ]; then
    find_java
    exit
fi

JAVA="$(find_java)" || {
    echo "oeHubApp needs Java $MIN_JAVA or later." >&2
    echo "  macOS: brew install openjdk   (or https://adoptium.net/)" >&2
    exit 1
}
[ -f "$JAR" ] || { echo "oeHubApp is not installed: $JAR is missing" >&2; exit 1; }

OPTS="-Dapple.awt.application.name=oeHubApp"
[ "$(uname -s)" = "Darwin" ] && OPTS="$OPTS -Xdock:name=oeHubApp"

# Detached so closing the terminal does not close the app. A second start just brings the
# running window to the front (the app allows one instance per data folder).
nohup "$JAVA" $OPTS -jar "$JAR" "$@" >"__APP_HOME__/oehubapp.log" 2>&1 &
LAUNCHER_EOF

esc_home="$(printf '%s' "$APP_HOME" | sed 's/[&|\\]/\\&/g')"
sed -e "s|__APP_HOME__|$esc_home|g" -e "s|__MIN_JAVA__|$MIN_JAVA|g" "$LAUNCHER" > "$TMP/launcher"
mv -f "$TMP/launcher" "$LAUNCHER"
chmod +x "$LAUNCHER"

version="$(unzip -p "$APP_HOME/oehubapp.jar" META-INF/MANIFEST.MF 2>/dev/null | sed -n 's/^Implementation-Version: *//p' | tr -d '\r')"
say "Installed oeHubApp ${version:-} to $APP_HOME"
say "Command: $LAUNCHER"

if ! java_path="$("$LAUNCHER" --find-java)"; then
    say ""
    say "Java $MIN_JAVA or later was not found. Install it, then run oehubapp:"
    say "  brew install openjdk        (or https://adoptium.net/)"
else
    say "Java: $java_path"
fi

case ":$PATH:" in
    *":$BIN_DIR:"*) ;;
    *)
        say ""
        say "$BIN_DIR is not on your PATH. Add it (zsh):"
        say "  echo 'export PATH=\"$BIN_DIR:\$PATH\"' >> ~/.zshrc && source ~/.zshrc"
        ;;
esac
say ""
say "Run: oehubapp"
