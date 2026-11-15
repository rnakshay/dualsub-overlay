#!/usr/bin/env bash
# Put a DeepL API key into the app's private storage on the TV.
#
# The key is never passed as an argument -- arguments land in shell history and
# are visible in `ps` -- and never written to a file on either side: it is piped
# over adb straight into files/deepl_key, which only the app can read.
#
#   ./set-key.sh                       prompt (nothing echoes)
#   ./set-key.sh --file ~/deepl.txt
#   pass show deepl | ./set-key.sh --stdin
#   ./set-key.sh --show                what the TV has now, masked
#
# Uses the only connected adb device, or TV_ADDR=192.168.0.50:5555 to connect
# to one over the network first. Needs the debug build (`run-as`), as built by
# ./gradlew assembleDebug.

set -euo pipefail

PKG="de.dualsub.overlay"
MODE="prompt"
KEYFILE=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --file) MODE="file"; KEYFILE="${2:-}"; shift 2 ;;
        --stdin) MODE="stdin"; shift ;;
        --show) MODE="show"; shift ;;
        -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

ADB=(adb)
if [[ -n "${TV_ADDR:-}" ]]; then
    # adb waits a long time for a TV that is off; give up after 10 s.
    if command -v timeout >/dev/null; then
        timeout 10 adb connect "$TV_ADDR" >/dev/null 2>&1 || true
    else
        adb connect "$TV_ADDR" >/dev/null 2>&1 || true
    fi
    ADB=(adb -s "$TV_ADDR")
fi
if ! "${ADB[@]}" get-state >/dev/null 2>&1; then
    echo "error: no adb device (connect one, or set TV_ADDR=ip:5555)" >&2
    exit 1
fi
if ! "${ADB[@]}" shell "run-as $PKG true" >/dev/null 2>&1; then
    echo "error: $PKG is not installed as a debug build on this device" >&2
    exit 1
fi

mask() {
    local k="$1"
    if [[ ${#k} -le 10 ]]; then echo "***"; else echo "${k:0:4}...${k: -3} (${#k} chars)"; fi
}

current() {
    "${ADB[@]}" shell "run-as $PKG cat files/deepl_key 2>/dev/null" | tr -d '\r\n' || true
}

if [[ "$MODE" == "show" ]]; then
    k=$(current)
    if [[ -z "$k" ]]; then echo "no key on the TV yet"; else echo "TV has: $(mask "$k")"; fi
    exit 0
fi

case "$MODE" in
    prompt) read -r -s -p "DeepL API key: " key; echo ;;
    file) [[ -r "$KEYFILE" ]] || { echo "error: cannot read $KEYFILE" >&2; exit 1; }
          key=$(head -n1 "$KEYFILE") ;;
    stdin) key=$(head -n1) ;;
esac
key=$(printf '%s' "$key" | tr -d '[:space:]')

if [[ -z "$key" ]]; then
    echo "error: empty key" >&2
    exit 1
fi
if [[ ! "$key" =~ ^[A-Za-z0-9:-]+$ ]]; then
    echo "error: that does not look like a DeepL key (letters, digits, '-' and ':' only)" >&2
    exit 1
fi

printf '%s' "$key" | "${ADB[@]}" shell "run-as $PKG sh -c 'mkdir -p files && cat > files/deepl_key'"

stored=$(current)
if [[ "$stored" != "$key" ]]; then
    echo "error: the key on the TV does not match what was sent" >&2
    exit 1
fi
echo "stored: $(mask "$stored")$([[ "$key" == *:fx ]] && echo ', free-tier key')"
echo "It is read when the overlay starts: turn it off and on again in the app if it is running."
