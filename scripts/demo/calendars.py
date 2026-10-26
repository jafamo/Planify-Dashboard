"""
Calendarios ficticios para la demo (scripts/demo.sh), como los que da Google Calendar en su dirección secreta iCal:
Familia y Colegio con eventos alrededor de hoy (también de todo el día y uno en los dos calendarios) y Rutinas, que se
repiten cada semana. Las fechas son relativas al día en que se ejecuta.

Uso: python3 scripts/demo/calendars.py <carpeta> <url-base>
Deja en la carpeta familia.ics, colegio.ics y rutinas.ics, y calendars.demo (el fichero de scripts/calendars.sh) con
sus direcciones en <url-base>.
"""
import datetime, pathlib, sys
OUT = pathlib.Path(sys.argv[1])
BASE = sys.argv[2].rstrip("/")
T = datetime.date.today()
STAMP = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
MADRID = """BEGIN:VTIMEZONE
TZID:Europe/Madrid
X-LIC-LOCATION:Europe/Madrid
BEGIN:DAYLIGHT
TZOFFSETFROM:+0100
TZOFFSETTO:+0200
TZNAME:CEST
DTSTART:19700329T020000
RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU
END:DAYLIGHT
BEGIN:STANDARD
TZOFFSETFROM:+0200
TZOFFSETTO:+0100
TZNAME:CET
DTSTART:19701025T030000
RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU
END:STANDARD
END:VTIMEZONE"""

def day(n): return T + datetime.timedelta(days=n)
def timed(n, hm): return f"DTSTART;TZID=Europe/Madrid:{day(n):%Y%m%d}T{hm.replace(':', '')}00"
def all_day(n, days=1): return f"DTSTART;VALUE=DATE:{day(n):%Y%m%d}\nDTEND;VALUE=DATE:{day(n + days):%Y%m%d}"

def calendar(name, events):
    body = "\n".join(f"BEGIN:VEVENT\n{start}\nDTSTAMP:{STAMP}\nUID:{uid}@demo\nSUMMARY:{title}\n{extra}END:VEVENT"
                     for uid, title, start, extra in events)
    text = f"BEGIN:VCALENDAR\nPRODID:-//Planify Dashboard//Demo//ES\nVERSION:2.0\nCALSCALE:GREGORIAN\nX-WR-CALNAME:{name}\n" \
           f"X-WR-TIMEZONE:Europe/Madrid\n{MADRID}\n{body}\nEND:VCALENDAR\n"
    return text.replace("\n", "\r\n")

familia = [
    ("revision", "Revisión médica", timed(-12, "10:00"), ""),
    ("cumple-abuelo", "Cumpleaños del abuelo", all_day(-5), "RRULE:FREQ=YEARLY\n"),
    ("mercadillo", "Mercadillo", timed(-2, "11:00"), ""),
    ("desayuno", "Desayuno con la vecina", timed(0, "09:00"), ""),
    ("cena", "Cena con los abuelos", timed(0, "20:30"), ""),
    ("dentista", "Dentista", timed(1, "17:00"), ""),
    ("futbol", "Partido de fútbol", timed(2, "10:30"), ""),
    ("comida", "Comida familiar", timed(3, "14:00"), ""),
    ("coche", "Revisión del coche", timed(5, "09:00"), ""),
    ("padres", "Reunión de padres", timed(6, "18:00"), ""),
    ("cumple-lucia", "Cumpleaños de Lucía", all_day(7), "RRULE:FREQ=YEARLY\n"),
    ("cine", "Cine en familia", timed(9, "19:00"), ""),
    ("puente", "Escapada a la sierra", all_day(11, 3), ""),
]
colegio = [
    ("tutoria", "Tutoría", timed(-8, "17:00"), ""),
    # También en Familia: sale una vez, con el color de Familia (el primero de la lista)
    ("padres", "Reunión de padres", timed(6, "18:00"), ""),
    ("museo", "Excursión al museo", timed(8, "09:30"), ""),
    ("festivo", "No lectivo", all_day(4), ""),
]
DAYS = ["MO", "TU", "WE", "TH", "FR", "SA", "SU"]
rutinas = [
    (uid, title, timed(-30, hm), f"RRULE:FREQ=WEEKLY;BYDAY={','.join(DAYS[d - 1] for d in days)}\n")
    for uid, title, days, hm in [
        ("recoger", "Recoger a los niños", [1, 2, 3, 4, 5], "16:00"),
        ("piano", "Piano", [4], "16:45"),
        ("natacion", "Natación", [2, 4], "18:00"),
        ("ingles", "Inglés", [1, 3], "17:30"),
        ("futbol-entreno", "Fútbol", [5], "17:00"),
        ("perro", "Sacar al perro", [1, 2, 3, 4, 5, 6, 7], "19:30"),
        ("bici", "Paseo en bici", [6, 7], "11:00"),
    ]
]

OUT.mkdir(parents=True, exist_ok=True)
for file, name, events in [("familia", "Familia", familia), ("colegio", "Colegio", colegio), ("rutinas", "Rutinas", rutinas)]:
    (OUT / f"{file}.ics").write_text(calendar(name, events), encoding="utf-8")
(OUT / "calendars.demo").write_text(
    f"Familia | #6923E2 | {BASE}/familia.ics\n"
    f"Colegio | #0EA5E9 | {BASE}/colegio.ics\n"
    f"Rutinas | #EA580C | {BASE}/rutinas.ics | rutinas\n", encoding="utf-8")
print("ok", len(familia) + len(colegio), "eventos y", len(rutinas), "rutinas")
