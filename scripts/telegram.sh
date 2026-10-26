#!/bin/bash
# Configura el bot de Telegram de las tareas: lee scripts/telegram.local (ver telegram.example), comprueba el token
# y escribe el bot y el grupo en la app por ADB, que se reinicia con ellos.
# Sin CHAT_ID, enseña los grupos donde está el bot para elegir uno (no toca el marco).
#
# Uso: scripts/telegram.sh [fichero]   (por defecto scripts/telegram.local)
#      ANDROID_SERIAL=<ip-del-marco>:5555 scripts/telegram.sh   (por Wi-Fi)
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
FILE="${1:-$HERE/scripts/telegram.local}"
# shellcheck source=lib/prefs.sh
source "$HERE/scripts/lib/prefs.sh"

[ -f "$FILE" ] || { echo "No existe $FILE: copia scripts/telegram.example como scripts/telegram.local y rellénalo."; exit 1; }
TOKEN="$(sed -n 's/^TOKEN=//p' "$FILE" | tr -d '[:space:]')"
CHAT_ID="$(sed -n 's/^CHAT_ID=//p' "$FILE" | tr -d '[:space:]')"
[ -n "$TOKEN" ] || { echo "Falta TOKEN en $FILE (te lo da @BotFather)."; exit 1; }
[ -z "$CHAT_ID" ] || [[ "$CHAT_ID" =~ ^-?[0-9]+$ ]] || { echo "CHAT_ID tiene que ser un número (el de un grupo empieza por «-»)."; exit 1; }

# Las llamadas a Telegram, con el token fuera de la línea de órdenes (se pasa a Python por el entorno)
export TOKEN CHAT_ID
python3 -I - <<'PY'
import json, os, sys, urllib.error, urllib.parse, urllib.request
token, chat_id = os.environ["TOKEN"], os.environ["CHAT_ID"]
def call(method, **params):
    data = urllib.parse.urlencode(params).encode() if params else None
    try:
        with urllib.request.urlopen(f"https://api.telegram.org/bot{token}/{method}", data=data, timeout=20) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        body = json.load(e)
        sys.exit(f"Telegram contesta {e.code} a {method}: {body.get('description')}" +
                 ("\n(otro programa está leyendo el bot: si el marco ya lo usa, no hace falta buscar el grupo)" if e.code == 409 else ""))
me = call("getMe")["result"]
print(f"Bot: @{me['username']}")
if not me.get("can_read_all_group_messages"):
    print("AVISO: el bot tiene el modo privacidad activado y en el grupo solo verá los mensajes que empiezan por «/».\n"
          "       En @BotFather: /setprivacy → @" + me["username"] + " → Disable, y vuelve a añadirlo al grupo.")
if not chat_id:
    chats = {}
    for update in call("getUpdates")["result"]:
        message = update.get("message") or update.get("my_chat_member") or {}
        chat = message.get("chat")
        if chat and chat["type"] in ("group", "supergroup"):
            chats[chat["id"]] = chat.get("title", "")
    if not chats:
        sys.exit("No veo ningún grupo: añade el bot al grupo, escribe algo en él y vuelve a lanzarlo.")
    print("Grupos donde está el bot (pon el identificador en CHAT_ID):")
    for cid, title in chats.items():
        print(f"  {cid}  {title}")
    sys.exit(2)
chat = call("getChat", chat_id=chat_id)["result"]
print(f"Grupo: {chat.get('title', chat_id)}")
PY

echo "==> Bot en ${ANDROID_SERIAL:-el dispositivo USB}"
write_prefs "telegram={\"token\": \"$TOKEN\", \"chat_id\": $CHAT_ID}"
echo "==> Listo: la app se ha reiniciado con el bot (Tareas en la barra lateral; estado en Ajustes)"
