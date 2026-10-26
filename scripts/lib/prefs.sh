# Escribe valores en las preferencias de la app (shared_prefs/dashboard.xml) por ADB, conservando el resto, y la
# reinicia para que los lea. Necesita ADB como root: el marco lo es de fábrica; en el emulador, `adb root`.
# Uso (con source): write_prefs nombre=valor...   (como texto; JSON en los que la app guarda JSON)

PREFS_PKG=com.lito.planify.dashboard

# Para la app. Siendo Device Owner (en el marco), Android ignora `am force-stop` ("protected package"), así que
# además se mata el proceso; como es el launcher, Android la vuelve a abrir sola.
stop_app() {
    local pid
    adb shell am force-stop $PREFS_PKG
    pid="$(adb shell pidof $PREFS_PKG | tr -d '\r' || true)"
    if [ -n "$pid" ]; then adb shell kill "$pid"; fi
}

write_prefs() {
    local dir="/data/data/$PREFS_PKG" tmp prefs owner
    tmp="$(mktemp)"
    prefs="$(adb shell cat "$dir/shared_prefs/dashboard.xml" 2>/dev/null | tr -d '\r' || true)"
    python3 -I - "$prefs" "$@" > "$tmp" <<'PY'
import sys, xml.etree.ElementTree as ET
text = sys.argv[1].strip()
root = ET.fromstring(text.encode()) if "<map" in text else ET.Element("map")
for pair in sys.argv[2:]:
    name, value = pair.split("=", 1)
    node = root.find(f"*[@name='{name}']")
    if node is None:
        node = ET.SubElement(root, "string", name=name)
    node.text = value
print("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>")
print(ET.tostring(root, encoding="unicode"))
PY
    owner="$(adb shell stat -c %u:%g "$dir" | tr -d '\r')"
    # Parada antes y después de copiar: es el launcher y Android la vuelve a abrir sola, y al guardar algo (la última
    # hora válida, cada minuto) escribiría encima sus preferencias de antes.
    stop_app
    adb shell mkdir -p "$dir/shared_prefs"
    adb push "$tmp" "$dir/shared_prefs/dashboard.xml" >/dev/null 2>&1
    rm -f "$tmp"
    adb shell chown -R "$owner" "$dir/shared_prefs"
    adb shell chmod 660 "$dir/shared_prefs/dashboard.xml"
    stop_app
    adb shell am start -n "$PREFS_PKG/.MainActivity" >/dev/null 2>&1
}
