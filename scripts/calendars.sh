#!/bin/bash
# Configura los calendarios de Google del dashboard: los lee de scripts/calendars.local (ver calendars.example) y los
# escribe en la app por ADB, que se reinicia con ellos. Para añadir, quitar o cambiar uno, se edita el fichero y se
# vuelve a lanzar. Las copias de los calendarios quitados las borra la propia app.
#
# Uso: scripts/calendars.sh [fichero]   (por defecto scripts/calendars.local)
#      ANDROID_SERIAL=<ip-del-marco>:5555 scripts/calendars.sh   (por Wi-Fi)
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
FILE="${1:-$HERE/scripts/calendars.local}"
# shellcheck source=lib/prefs.sh
source "$HERE/scripts/lib/prefs.sh"

[ -f "$FILE" ] || { echo "No existe $FILE: copia scripts/calendars.example como scripts/calendars.local y rellénalo."; exit 1; }

# El JSON va por la salida y el resumen (sin las URLs, que son secretas) por la de errores.
json="$(python3 -I - "$FILE" <<'PY'
import json, re, sys
PALETTE = ["#6923E2", "#0EA5E9", "#16A34A", "#EA580C", "#DB2777", "#CA8A04"]
calendars = []
for n, line in enumerate(open(sys.argv[1], encoding="utf-8"), 1):
    line = line.strip()
    if not line or line.startswith("#"):
        continue
    parts = [p.strip() for p in line.split("|")]
    if len(parts) < 3 or not parts[0] or not re.match(r"https?://", parts[2]):
        sys.exit(f"Línea {n}: tiene que ser «nombre | color | https://… | rutinas»")
    name, color, url = parts[:3]
    routines = len(parts) > 3 and parts[3].lower() == "rutinas"
    if color and not re.fullmatch(r"#[0-9A-Fa-f]{6}", color):
        sys.exit(f"Línea {n}: el color «{color}» tiene que ser #RRGGBB (o vacío)")
    if any(c["url"] == url for c in calendars):
        sys.exit(f"Línea {n}: la dirección de «{name}» ya está en otro calendario")
    calendars.append({"name": name, "url": url, "color": color.upper(), "routines": routines})
if not calendars:
    sys.exit("No hay ningún calendario en el fichero")
# Sin color: el primero de la paleta que no tenga ya otro calendario
used = {c["color"] for c in calendars}
for c in calendars:
    if not c["color"]:
        c["color"] = next((p for p in PALETTE if p not in used), PALETTE[0])
        used.add(c["color"])
    print(f"  {c['name']} ({c['color']}){' · rutinas' if c['routines'] else ''}", file=sys.stderr)
print(json.dumps(calendars, ensure_ascii=False))
PY
)"

echo "==> Calendarios en ${ANDROID_SERIAL:-el dispositivo USB}"
write_prefs "calendars=$json"
echo "==> Listo: la app se ha reiniciado con ellos (estado en Ajustes → Calendarios)"
