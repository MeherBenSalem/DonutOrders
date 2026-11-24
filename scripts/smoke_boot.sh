#!/usr/bin/env bash
# NightBeam smoke boot: starts a real Paper or Folia server with the plugin and fails unless it enables cleanly.
#
# Usage: scripts/smoke_boot.sh <paper|folia> <version|latest> <plugin.jar> [extra ...]
#   extra: more plugins to install next to it, each a local jar, an https:// URL, or modrinth:<slug>
#          (newest release for the server's loader, read from Modrinth's public API).
#   "latest" picks the newest version the project has on PaperMC's Fill API, preferring a 26.x version.
# Env:  JAVA_BIN (default: java), BOOT_TIMEOUT seconds (default 600), SMOKE_DIR (default: build/smoke/<project>-<version>)
#
# The server jar comes from https://fill.papermc.io/v3/projects/<project>/versions/<version>/builds/latest.
# Fails if the server never prints "Done (", the plugin doesn't enable (or is disabled again), the log has an ERROR
# line, or an exception mentions the plugin. bStats is switched off on the test server so CI boots aren't counted.
set -euo pipefail

PROJECT="${1:?project (paper or folia)}"
VERSION="${2:?version or latest}"
PLUGIN_JAR="${3:?plugin jar}"
shift 3
EXTRAS=("$@")

JAVA_BIN="${JAVA_BIN:-java}"
BOOT_TIMEOUT="${BOOT_TIMEOUT:-600}"
UA="nightbeam-plugin-smoke/1.0 (github.com/MeherBenSalem)"
FILL="https://fill.papermc.io/v3/projects/${PROJECT}"

say() { echo "[smoke] $*"; }
fail() { echo "::error title=Smoke ${PROJECT} ${VERSION}::$*"; exit 1; }

[[ -f "$PLUGIN_JAR" ]] || fail "plugin jar not found: $PLUGIN_JAR"
PLUGIN_JAR="$(cd "$(dirname "$PLUGIN_JAR")" && pwd)/$(basename "$PLUGIN_JAR")"

PLUGIN_YML="$(unzip -p "$PLUGIN_JAR" plugin.yml)"
PLUGIN_NAME="$(printf '%s\n' "$PLUGIN_YML" | sed -n -E "s/^name:[[:space:]]*['\"]?([^'\"#]+)['\"]?.*/\1/p" | head -1 | tr -d '[:space:]')"
MAIN_CLASS="$(printf '%s\n' "$PLUGIN_YML" | sed -n -E "s/^main:[[:space:]]*['\"]?([^'\"#[:space:]]+).*/\1/p" | head -1)"
PLUGIN_PKG="${MAIN_CLASS%.*}"
[[ -n "$PLUGIN_NAME" && -n "$MAIN_CLASS" ]] || fail "could not read name/main from plugin.yml"

fetch_json() { curl -fsSL --retry 3 -A "$UA" "$1"; }

if [[ "$VERSION" == "latest" ]]; then
  VERSION="$(fetch_json "$FILL" | python3 -c '
import json, re, sys
data = json.load(sys.stdin)
versions = data.get("versions", [])
if isinstance(versions, dict):
    versions = [v for group in versions.values() for v in group]
def key(v):
    return tuple(int(p) if p.isdigit() else -1 for p in re.split(r"[.-]", v))
stable = [v for v in versions if re.fullmatch(r"\d+(\.\d+)*", v)] or versions
modern = [v for v in stable if v.startswith("26.")]
print(max(modern or stable, key=key))
')"
  say "latest ${PROJECT} version on Fill: ${VERSION}"
fi

SMOKE_DIR="${SMOKE_DIR:-build/smoke/${PROJECT}-${VERSION}}"
rm -rf "$SMOKE_DIR"
mkdir -p "$SMOKE_DIR/plugins/bStats"
SMOKE_DIR="$(cd "$SMOKE_DIR" && pwd)"

BUILD_JSON="$(fetch_json "${FILL}/versions/${VERSION}/builds/latest")"
read -r BUILD_ID SERVER_URL < <(printf '%s' "$BUILD_JSON" | python3 -c '
import json, sys
b = json.load(sys.stdin)
d = b["downloads"].get("server:default") or next(iter(b["downloads"].values()))
print(b.get("id", "?"), d["url"])
')
say "${PROJECT} ${VERSION} build ${BUILD_ID}: ${SERVER_URL}"
curl -fsSL --retry 3 -A "$UA" -o "$SMOKE_DIR/server.jar" "$SERVER_URL"

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  echo "server=${PROJECT} ${VERSION} build ${BUILD_ID}" >> "$GITHUB_OUTPUT"
fi
if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  echo "Smoke boot: **${PROJECT} ${VERSION}** (build ${BUILD_ID}), $("$JAVA_BIN" -version 2>&1 | head -1)" >> "$GITHUB_STEP_SUMMARY"
fi

cp "$PLUGIN_JAR" "$SMOKE_DIR/plugins/"
LOADER="paper"   # Modrinth lists Folia-ready plugins under the paper loader too
for extra in "${EXTRAS[@]}"; do
  case "$extra" in
    modrinth:*)
      slug="${extra#modrinth:}"
      # Newest release that lists this Minecraft version, else the newest release for the loader.
      mr="https://api.modrinth.com/v2/project/${slug}/version?loaders=%5B%22${LOADER}%22%5D"
      url="$( { fetch_json "${mr}&game_versions=%5B%22${VERSION}%22%5D"; echo; fetch_json "$mr"; } | python3 -c '
import json, sys
for line in sys.stdin.read().splitlines():
    line = line.strip()
    if not line:
        continue
    vs = json.loads(line)
    v = next((v for v in vs if v.get("version_type") == "release"), vs[0] if vs else None)
    if v:
        f = next((f for f in v["files"] if f.get("primary")), v["files"][0])
        print(f["url"])
        break
else:
    sys.exit("no Modrinth version found")
')"
      say "extra plugin ${slug}: ${url}"
      curl -fsSL --retry 3 -A "$UA" -o "$SMOKE_DIR/plugins/${slug}.jar" "$url"
      ;;
    https://*)
      say "extra plugin: ${extra}"
      curl -fsSL --retry 3 -A "$UA" -o "$SMOKE_DIR/plugins/$(basename "${extra%%\?*}")" "$extra"
      ;;
    *)
      [[ -f "$extra" ]] || fail "extra plugin not found: $extra"
      cp "$extra" "$SMOKE_DIR/plugins/"
      ;;
  esac
done

echo "eula=true" > "$SMOKE_DIR/eula.txt"
cat > "$SMOKE_DIR/server.properties" <<EOF
online-mode=false
server-port=25565
spawn-protection=0
max-players=2
view-distance=4
simulation-distance=4
EOF
printf 'enabled: false\nserverUuid: 00000000-0000-0000-0000-000000000000\nlogFailedRequests: false\n' > "$SMOKE_DIR/plugins/bStats/config.yml"

LOG="$SMOKE_DIR/server.log"
: > "$LOG"
mkfifo "$SMOKE_DIR/stdin"
say "booting with ${JAVA_BIN}"
( cd "$SMOKE_DIR" && exec "$JAVA_BIN" -Xms1G -Xmx2G -Dterminal.jline=false -Dterminal.ansi=false -jar server.jar --nogui < stdin ) >> "$LOG" 2>&1 &
PID=$!
exec 3> "$SMOKE_DIR/stdin"   # keep the server's stdin open until we send stop

booted=0
for _ in $(seq 1 "$BOOT_TIMEOUT"); do
  if grep -q "Done (" "$LOG"; then booted=1; break; fi
  kill -0 "$PID" 2>/dev/null || break
  sleep 1
done
sleep 5   # let tasks scheduled for the first ticks run
cp "$LOG" "$SMOKE_DIR/boot.log"

echo "stop" >&3 || true
for _ in $(seq 1 90); do kill -0 "$PID" 2>/dev/null || break; sleep 1; done
if kill -0 "$PID" 2>/dev/null; then say "server did not stop in 90 s, killing it"; kill -9 "$PID" 2>/dev/null || true; fi
exec 3>&-
wait "$PID" 2>/dev/null || true

problems=()
[[ "$booted" -eq 1 ]] || problems+=("the server never finished starting (no \"Done (\" within ${BOOT_TIMEOUT} s)")
if ! grep -Eq "Enabling ${PLUGIN_NAME} v" "$SMOKE_DIR/boot.log"; then
  problems+=("${PLUGIN_NAME} was never enabled")
fi
if grep -Eq "Disabling ${PLUGIN_NAME} v" "$SMOKE_DIR/boot.log"; then
  problems+=("${PLUGIN_NAME} disabled itself during startup")
fi
error_lines="$(grep -E '(\[[0-9:]+ (ERROR|SEVERE)\]|/(ERROR|SEVERE)\]|^\[?(ERROR|SEVERE)[]:])' "$LOG" || true)"
if [[ -n "$error_lines" ]]; then
  problems+=("the log has ERROR lines")
fi
plugin_exceptions="$(grep -nE "(Exception|Error)\b.*(${PLUGIN_NAME}|${PLUGIN_PKG//./\\.})|at ${PLUGIN_PKG//./\\.}\.|(${PLUGIN_NAME}|${PLUGIN_PKG//./\\.}).*(Exception|Error)\b" "$LOG" || true)"
if [[ -n "$plugin_exceptions" ]]; then
  problems+=("an exception mentions ${PLUGIN_NAME}")
fi

echo "----- ${PLUGIN_NAME} lines -----"
grep -E "${PLUGIN_NAME}" "$LOG" || true
if [[ ${#problems[@]} -gt 0 ]]; then
  echo "----- ERROR lines -----"; printf '%s\n' "$error_lines"
  echo "----- plugin exceptions -----"; printf '%s\n' "$plugin_exceptions"
  echo "----- last 80 log lines -----"; tail -n 80 "$LOG"
  for p in "${problems[@]}"; do echo "::error title=Smoke ${PROJECT} ${VERSION}::$p"; done
  exit 1
fi
say "PASS: ${PLUGIN_NAME} enabled cleanly on ${PROJECT} ${VERSION} (build ${BUILD_ID})"
