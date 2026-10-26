#!/bin/bash
# Entorno de demo para probar el dashboard en el emulador y hacer las capturas del README sin datos reales.
#
# Uso: scripts/demo.sh <orden>
#   up       calendarios de demo (iCal, como los de Google) servidos en el puerto 3001 desde .demo/www (git lo
#            ignora), con datos ficticios (scripts/demo/calendars.py). Las fechas son relativas a hoy: si son de
#            otro día, los vuelve a crear. Si ya está arrancado y al día, no hace nada.
#   app      además de up: arranca el emulador (si no lo está), le pone la hora del PC, compila e instala la
#            debug y le pone los calendarios de demo y Madrid como ubicación (scripts/calendars.sh).
#            Con --no-build, instala la última debug compilada.
#   down     para el servidor.
#   reset    borra .demo/ y repite up.
#
# Variables: DEMO_AVD (Frame_API_29), DEMO_EMULATOR_PORT (5556), ANDROID_SDK (~/Android/Sdk).
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
DEMO="$HERE/.demo"
AVD="${DEMO_AVD:-Frame_API_29}"
EMULATOR_PORT="${DEMO_EMULATOR_PORT:-5556}"
SDK="${ANDROID_SDK:-$HOME/Android/Sdk}"
PORT=3001
URL="http://127.0.0.1:$PORT"
# El PC visto desde el emulador
EMULATOR_URL="http://10.0.2.2:$PORT"
export ANDROID_SERIAL="emulator-$EMULATOR_PORT"

step() { echo "==> $*"; }
running() { curl -sf -o /dev/null "$URL/familia.ics" 2>/dev/null; }

stop() {
    pkill -f "[h]ttp.server $PORT" || true
}

up() {
    if running && [ "$(cat "$DEMO/seeded" 2>/dev/null)" = "$(date +%F)" ]; then
        step "Calendarios de demo en $URL (de hoy)"
        return
    fi
    stop
    step "Calendarios de demo"
    rm -rf "$DEMO/www"
    python3 -I "$HERE/scripts/demo/calendars.py" "$DEMO/www" "$EMULATOR_URL"
    # En su propia sesión y sin heredar la salida, para que no quede colgado de este script
    (exec setsid python3 -I -m http.server $PORT --bind 0.0.0.0 --directory "$DEMO/www") > "$DEMO/http.log" 2>&1 < /dev/null &
    for _ in $(seq 50); do running && break; sleep 0.1; done
    running || { echo "El servidor no arrancó:"; tail -5 "$DEMO/http.log"; exit 1; }
    date +%F > "$DEMO/seeded"
}

emulator() {
    if ! adb devices | grep -q "^$ANDROID_SERIAL\s*device"; then
        step "Arrancando el emulador $AVD"
        # Si el PC se apagó con el emulador abierto, quedan los bloqueos del AVD y no vuelve a arrancar
        pgrep -f "[q]emu.*-avd $AVD" >/dev/null || rm -f "$HOME/.android/avd/$AVD.avd/"*.lock
        setsid "$SDK/emulator/emulator" -avd "$AVD" -port "$EMULATOR_PORT" -no-snapshot-save > "$DEMO/emulator.log" 2>&1 < /dev/null &
        local pid=$!
        until adb devices | grep -q "^$ANDROID_SERIAL\s*device"; do
            kill -0 $pid 2>/dev/null || { echo "El emulador no arrancó:"; tail -5 "$DEMO/emulator.log"; exit 1; }
            sleep 1
        done
    fi
    until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 1; done
    adb root >/dev/null
    adb wait-for-device
    # La imagen del emulador no fija el límite de memoria de las apps y se queda en 16 MB; el marco tiene 128 MB
    if [ -z "$(adb shell getprop dalvik.vm.heapgrowthlimit | tr -d '\r')" ]; then
        adb shell setprop dalvik.vm.heapgrowthlimit 128m
        adb shell stop
        adb shell start
        # sys.boot_completed no vuelve a 0 al reiniciar el sistema: se espera a que responda el gestor de paquetes
        sleep 5
        until adb shell pm path android >/dev/null 2>&1; do sleep 1; done
    fi
    # Al arrancar de un snapshot, el emulador conserva la hora en la que se guardó
    adb shell date "$(date +%m%d%H%M%Y.%S)" >/dev/null
}

app() {
    local build=1
    for arg in "$@"; do
        case "$arg" in
            --no-build) build=0 ;;
            *) echo "Opción desconocida: $arg"; exit 1 ;;
        esac
    done
    up
    emulator
    local apk="$HERE/android/app/build/outputs/apk/debug/app-debug.apk"
    if [ $build = 1 ]; then
        step "Compilando la debug"
        (cd "$HERE/android" && ./gradlew -q assembleDebug)
    fi
    step "Instalando"
    adb install -r "$apk" >/dev/null

    step "Ubicación de demo"
    # shellcheck source=lib/prefs.sh
    source "$HERE/scripts/lib/prefs.sh"
    adb shell rm -rf "/data/data/$PREFS_PKG/files/cache"
    write_prefs "location={\"detail\": \"Comunidad Autónoma de Madrid, España\", \"latitude\": 40.4165, \"longitude\": -3.70256, \"name\": \"Madrid\"}"
    "$HERE/scripts/calendars.sh" "$DEMO/www/calendars.demo"
    step "Listo en $ANDROID_SERIAL"
}

mkdir -p "$DEMO"
case "${1:-}" in
    up) up ;;
    app) shift; app "$@" ;;
    down) stop; step "Servidor de demo parado" ;;
    reset) stop; rm -rf "$DEMO"; mkdir -p "$DEMO"; up ;;
    *) sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
