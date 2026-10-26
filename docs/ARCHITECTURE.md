# Arquitectura del dashboard

Cómo está hecha la app del marco: pantallas, datos, estructura y desarrollo. Para montar el marco, ver [SETUP.md](SETUP.md).

## Pantallas

La barra lateral izquierda tiene arriba, solo con iconos, Hoy, Calendario, Tiempo, Tareas (solo con el bot de Telegram configurado), **sincronizar** (vuelve a pedir los calendarios y el tiempo; el icono gira mientras dura), Ajustes y apagar la pantalla. Abajo del todo, la hora (horas y minutos en dos líneas, con "Sin conexión" debajo si falla la red o la sincronización). Tras 2 minutos sin tocar, vuelve a Hoy.

Sin calendarios configurados se muestra cómo añadirlos (`SetupScreen`: icono, dónde sacar la dirección secreta en Google y `scripts/calendars.sh`). Si no hay Internet, en su lugar aparece "Esperando conexión a Internet…" con "Ir a Ajustes" (ajustes de Wi-Fi).

- **Hoy:** rejilla de 3 columnas en dos filas (55 % y 45 % del alto).
  - Arriba, en 1/3, la hora y la fecha centradas y lo **siguiente**, sea evento o rutina (tarjeta del color de su calendario: a la derecha, lo que falta en una píldora, como en la lista de próximos eventos; debajo, el título y el día y la hora). Solo si es de hoy o de mañana, y nunca uno de todo el día (no tienen cuenta atrás): si es más tarde, no hay tarjeta y el evento sale en la lista como los demás. Si es un evento, no se repite en la lista de próximos eventos; una rutina sigue también en la suya. En 2/3, la **tarjeta del tiempo**: icono, temperatura y estado; lluvia del día, humedad y viento como iconos con su valor (Meteocons estáticos); la curva hora a hora del día de hoy, de 00 a 23 h (la hora actual marcada con una banda translúcida, amarilla de día y gris de noche; la temperatura de todas las horas, pequeña y sin «°») con barras de lluvia solo donde llueve (el alto es la cantidad y el relleno, la probabilidad; ver más abajo); debajo, el icono del tiempo de cada hora y, abajo del todo, la hora; y, separados por una línea, los días en columnas, que se deslizan para ver la semana siguiente (vuelven a la primera a los 2 minutos). Al tocar un día, la cabecera y la curva pasan a ese día; tocarlo otra vez, o tocar hoy, vuelve a hoy (y vuelve solo a los 2 minutos). Deslizar sobre la curva pasa al día anterior o al siguiente: la curva es un pager de días que sigue al dedo (los días de abajo pasan a la página del día).
  - Abajo, el **calendario del mes**, como el de Eventos de la app (las iniciales de los días; hoy en un círculo rojo suave; un punto por cada calendario con eventos ese día, hasta 3), que se desliza para cambiar de mes (12 hacia atrás y 12 hacia delante; el título lleva el año si no es el actual); **próximos eventos** con el formato del widget de la app (barra de color, "MIÉ 14 OCT · 16:00" o "· Todo el día" con lo que falta en una píldora —en días para los de todo el día: "HOY", "MAÑANA", "3D"—, y el título; los pasados en gris; solo los 40 siguientes, porque un año de repeticiones son cientos de filas y Compose las compone todas), y **rutinas de hoy** (la lista, con la siguiente resaltada y las pasadas atenuadas). Al tocar un día del calendario se marca con un aro gris y próximos eventos pasa a ser la lista de ese día ("Miércoles 21", incluido el siguiente evento); tocarlo otra vez vuelve a la lista. Tras 5 minutos sin tocar la pantalla, el calendario vuelve al mes actual y la lista a los próximos eventos. Las dos listas (próximos eventos y rutinas) se abren con un solo elemento pasado a la vista, el último, y los que faltan debajo; se recolocan cuando pasa otro y, si se han deslizado a mano, tras 1 minuto sin tocar la pantalla (`UpcomingList`).
- **Calendario:** dos filas, la semana actual y la siguiente, de lunes a domingo, sin título (como el diseño de referencia). Se desliza en vertical de semana en semana (`VerticalPager` con páginas de media pantalla; un año hacia cada lado; "Volver a hoy", flotando abajo a la derecha, regresa a la actual). Cada día es una tarjeta (hoy, más clara): arriba el día de la semana ("Hoy" en rojo; el día 1, también el mes) y el número a la derecha; debajo, en una línea de alto fijo, el icono del tiempo, máxima y mínima y la lluvia con su gota; luego los eventos (barra de color, hora o "Todo el día", y título; los de varios días, en cada uno) y abajo, en una caja, las rutinas (hora y nombre, que sigue en la línea de debajo). Los días pasados, atenuados. Sale de los mismos datos que Hoy: los calendarios ya están enteros en memoria.
- **Tiempo:** dos páginas que se deslizan. La primera es la tarjeta del tiempo a pantalla completa: el mismo día de hoy de 00 a 23 h que en Hoy (la temperatura de cada hora, también sin «°»), con el icono de cada hora encima de la hora, y 7 días. La segunda, la **semana siguiente** (días 8 a 14): gráfica de máximas y mínimas, lluvia (% y mm) y viento, y las columnas de cada día. En las dos páginas, tocar un día abre sus 24 horas (en la segunda, en lugar de la gráfica semanal) y tocarlo otra vez lo cierra. Deslizar sobre la gráfica cambia de día sin cambiar de página, con la gráfica siguiendo al dedo (en la segunda: semana → lunes → … → domingo); al pasar del último día de la primera sigue la segunda, y antes de su semana se vuelve a la primera. Deslizar fuera de la gráfica, entre las dos páginas.
- **Iconos del tiempo:** [Meteocons](https://github.com/basmilius/meteocons) de Bas Milius (estilo *line*, licencia MIT, en `third_party/meteocons/`), uno por código WMO de Open-Meteo, de día o de noche. `scripts/meteocons.py` los descarga en la versión fijada, cambia los colores pensados para fondo oscuro (nubes, niebla y viento casi blancos) por tonos pizarra y los deja en `res/raw` (animados, Lottie) y `res/drawable` (los estáticos de la cabecera: nube con lluvia para la probabilidad, gota para la humedad y viento, con la unidad más pequeña). El código diario es el tiempo más significativo de todo el día (si hay niebla por la mañana, el día sale con niebla), así que la columna de hoy, en el tiempo y en el calendario, usa el tiempo de ahora, como la cabecera.
  - **Animados:** el icono de la cabecera y los de las columnas de días (Hoy, Tiempo y Calendario); la fila de horas, estática. Dibujar los Lottie en cada fotograma costaba 1–2 núcleos y subía el marco a 72 °C, así que cada icono se renderiza una vez en segundo plano a su tamaño exacto (90 fotogramas: su bucle de 6 s a 15 fps, caché de 48 MB) y animarlo es pintar un bitmap, todos sincronizados con un único reloj. Medido en el marco: Hoy 31 % de un núcleo, Tiempo 37 %, Calendario 25 %, sin pasar de 61 °C; la app ocupa unos 175 MB y quedan unos 400 MB libres. Con la pantalla apagada no se anima nada.
- **Tareas:** las del grupo de Telegram, una columna por lista (General primero y las demás por orden alfabético; con más de 4, desplazamiento lateral), cada una con quién la apuntó y "hoy", "ayer" o "hace N días". Pulsación larga para tacharla (`detectTapGestures(onLongPress)`; un toque no hace nada, para que un roce no borre). Sin tareas, "Todo hecho".
- **Reloj:** en Hoy, un reloj de paletas (`FlipClock`): cada cifra en una placa grafito partida por la mitad y, al cambiar, la mitad de arriba cae y descubre la nueva (medio segundo, solo las cifras que cambian). En la barra lateral, las cifras se deslizan (`RollingText`). Las escalas de animación del sistema están a 0 en el marco, así que estas animaciones fuerzan su propia escala para verse.
- **Máximas y mínimas:** salen de las 24 horas de cada día (`withDailyFromHourly`, una vez al recibir la previsión), para que el resumen, las cabeceras y las gráficas digan siempre lo mismo. Las diarias de Open-Meteo se calculan con más detalle que la hora en punto y no coincidían.

Las tarjetas llevan una cabecera común (`CardHeader`): icono en un cuadro tintado, título y el total en una píldora del mismo color. En las gráficas del tiempo, la barra de lluvia mide la **cantidad**, no la probabilidad: el alto parte del justo para el número de mm, que va dentro, abajo y en blanco, y crece en proporción a la cantidad hasta llenarse con 10 mm en una hora (30 mm en un día; desde 15 mm en una hora, un azul más intenso). La opacidad del relleno es la probabilidad, sin número: maciza al 100 % y vacía al 0 %, con un contorno suave siempre para que se vea la barra. En Hoy, la lluvia se lleva más alto de la gráfica (el 60 %) para que las barras se distingan. La curva de temperatura solo deja abajo el alto de la barra más alta (sin lluvia, ninguno), así que usa todo el alto que puede y se ve bien la diferencia entre la mínima y la máxima. Sin lluvia apreciable (menos de 0,1 mm) no hay barra. Open-Meteo da la lluvia de la hora anterior, así que cada columna enseña la de la hora que empieza en ella (la de las 05, de 05 a 06). La cabecera y los días llevan también el total del día en mm: primero los mm y después el % (en los días, nada si es un 0 %). Las listas que no caben se desvanecen en el borde (`fadingScroll`) en vez de cortar el texto. Las tarjetas sin contenido muestran un estado vacío (`EmptyState`): icono en círculos del color de la tarjeta, título y una línea.
- **Reloj nocturno** (`NightScreen`): fuera de horario, si no se ha elegido apagar la pantalla, la hora, la fecha y el tiempo de ahora en negrita y en blanco sobre negro (el icono del tiempo, teñido de blanco), estáticos, con el brillo al mínimo (`MainActivity` pone `screenBrightness` a 1/255 mientras `UiState.night`). Tocar enseña el dashboard 5 minutos (`peekUntil`). La luna de la barra lateral también lo activa si está el reloj nocturno (`nightNow`, hasta que se toque o hasta el siguiente encendido); si no, apaga la pantalla con `lockNow()`.
- **Ajustes:**
  - tres columnas que caben sin desplazamiento;
  - ubicación (Open-Meteo); horario de pantalla y, debajo, **Calendarios** (cada uno con su color y "Al día", "Hace N min" o "No responde"; con el horario activado caben unos 5); **brillo** (en el título, el porcentaje elegido o «automático»; deslizador propio, del 5 al 100 % de 5 en 5, y a su derecha «A», el brillo automático del sistema, que deja el deslizador desactivado y translúcido) y **Conexión** (estado del Wi-Fi) en una caja, y debajo, en otra, Wi-Fi, Ajustes de Android y Apagar o reiniciar;
  - todos los botones, a lo ancho y del mismo alto.

## Cómo funciona

- **App nativa** con Jetpack Compose, minSdk 29 y sin librerías nativas (el marco es de 32 bits).
- **Píxeles nativos:** la densidad del sistema no se toca. La app fija `Density(1f)`, así que todo se diseña en los 1920×1200 píxeles del panel (1 dp = 1 px).
- **Datos:**
  - cada calendario iCal (su dirección secreta de Google) entero cada 5 minutos, con sus repeticiones expandidas de 12 meses atrás a 12 meses adelante; Hoy y Calendario salen de ahí;
  - Open-Meteo cada 30 minutos: el día anterior (para las horas previas de madrugada) y 14 días;
  - **Sin red, todo como la última vez.** Cada dato remoto (cada calendario y la previsión) tiene su copia en disco y se pinta desde ella al arrancar; si una descarga falla, se sigue mostrando la última buena y la barra indica "Sin conexión". Al volver la red se actualiza solo. Ver [Capa de datos](#capa-de-datos).
  - al recuperar la red se sincroniza enseguida;
  - con la pantalla apagada no se refresca. Lo último se guarda en caché: sin red se sigue mostrando y la barra lateral dice "Sin conexión".
- **Hora:** el RTC del marco no la conserva. Hasta que NTP la corrige, cualquier hora anterior a la compilación de la app se trata como falsa ("Sincronizando hora…").
- **Quiosco** con Device Owner: lock task, HOME persistente, sin keyguard ni barra de estado. El horario apaga la pantalla (`lockNow`, como pulsar el botón de encendido: el marco sigue encendido y conectado) y la enciende con una alarma. "Apagar" en la barra lateral hace lo mismo al momento.
- **Tareas:** un bot de Telegram en el grupo de la familia, leído con `getUpdates` en espera larga (50 s) sin parar, también con la pantalla apagada (Telegram solo guarda 24 h lo no leído); tras un fallo, espera de 15 s a 5 min. Ver [SETUP.md, Tareas con Telegram](SETUP.md#tareas-con-telegram).
- **Sin cuentas:** los calendarios se leen por su dirección secreta iCal, que `scripts/calendars.sh` escribe en las preferencias de la app. El dashboard solo hace peticiones de lectura.
- **Diseño:** fondo crema, tarjetas blancas sin borde y el color solo en los calendarios, con las fuentes Geist y Geist Mono (OFL, `licenses/Geist-OFL.txt`).

## Estructura

```
android/                     proyecto Gradle (mismas versiones que Planify-APP)
  app/src/main/java/com/lito/planify/dashboard/
    MainActivity.kt          launcher, inmersivo, Density(1f), quiosco y brillo
    DashboardViewModel.kt    estado, refrescos, calendarios, ubicación, horario
    data/                    Api (iCal + Open-Meteo), modelos, Store (calendarios y ajustes), ReferenceClock
    data/ical/               IcsParser: iCal → apariciones en un rango (biweekly para las repeticiones)
    data/telegram/           TelegramApi: getUpdates, sendMessage y setMessageReaction
    data/tasks/              TaskBoard: un mensaje del grupo → tareas nuevas, tachadas o la respuesta del bot
    data/cache/              DiskCache y CachedResource (copia en disco, stale-if-error)
    data/repository/         EventsRepository (calendarios), TasksRepository (bot) y WeatherRepository
    admin/Kiosk.kt           Device Owner: lock task, HOME, lockNow, salir y quitar
    power/ScreenSchedule.kt  horario de pantalla (alarmas exactas, wakelock, apagado de cortesía)
    ui/                      barra lateral y pantallas; WeatherUi (HourlyForecast en Canvas), WeatherIcons y
                             MeteoconFrames (Meteocons pre-renderizados), FlipClock y RollingText
scripts/install.sh           instala y prepara el marco (ver SETUP.md); se configura en scripts/config.local
scripts/calendars.sh         calendarios de la app, desde scripts/calendars.local; usa scripts/lib/prefs.sh
scripts/telegram.sh          bot de las tareas, desde scripts/telegram.local; usa scripts/lib/prefs.sh
scripts/frame/               copia de la eMMC, cortafuegos de arranque e init .rc
scripts/boot/                logo del bootloader y animación de arranque (boot.html → make.sh → install.sh)
scripts/meteocons.py         iconos del tiempo (Meteocons) para res/raw y res/drawable
third_party/meteocons/       licencia MIT de Meteocons
docs/                        FRAME (ficha del dispositivo), ANDROID (versiones), SETUP (instalación)
```

## Desarrollo

Para el emulador hace falta un AVD con las medidas del marco (API 29, 1920×1200, 320 dpi, 1 GB):

```sh
sdkmanager "platforms;android-37.0" "build-tools;37.0.0" "platform-tools" "emulator" "system-images;android-29;default;x86"
avdmanager create avd -n Frame_API_29 -k "system-images;android-29;default;x86" -d pixel_c
# En ~/.android/avd/Frame_API_29.avd/config.ini:
#   hw.lcd.width=1920  hw.lcd.height=1200  hw.lcd.density=320  hw.ramSize=1024  hw.initialOrientation=landscape
emulator -avd Frame_API_29
```

El emulador pide unos 2 GB de RAM (sube solo de 1 GB) y la imagen no fija el límite de memoria por app (`dalvik.vm.heapgrowthlimit`), así que se queda en 16 MB; el marco tiene 128 MB. `demo.sh` lo pone a 128 MB. En un PC de 16 GB, mejor no tener a la vez el emulador y los daemons de Gradle y Kotlin (unos 4,5 GB): `./gradlew --stop` antes de arrancarlo. Si el emulador se cuelga al arrancar ("detected a hanging thread"), suele ser falta de memoria; `-gpu swiftshader_indirect` evita además la GPU del PC.

- La build **debug** permite HTTP (los calendarios de la demo se sirven desde el PC); la **release**, solo HTTPS. Ninguna lleva URLs dentro.
- Pruebas: `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease` (en `android/`, con JDK 17).

**Entorno de demo** (`scripts/demo.sh`), para probar sin datos reales y hacer las capturas del README:

- `scripts/demo.sh app [--no-build]` deja el emulador listo en un paso: sirve los calendarios de demo, arranca el AVD si no lo está, le pone la hora del PC y el límite de memoria del marco, compila e instala la debug y le pasa los calendarios y Madrid como ubicación (`scripts/calendars.sh`, sin teclear).
- Los calendarios son iCal ficticios de `scripts/demo/calendars.py` (Familia y Colegio, con eventos de todo el día y uno en los dos; Rutinas, semanales), en `.demo/www` (git lo ignora), servidos con `python3 -m http.server` en el puerto 3001 (`http://10.0.2.2:3001` desde el emulador). Las fechas son relativas al día, así que se vuelven a crear si son de otro día.
- `up` solo los sirve, `down` para el servidor y `reset` los crea desde cero.

Para probar el quiosco en el emulador basta con `adb shell dpm set-device-owner com.lito.planify.dashboard/.admin.AdminReceiver` en un AVD sin cuentas.

## Capa de datos

La pantalla nunca habla con la red directamente: `DashboardViewModel` usa un repositorio por tipo de dato y pinta lo que publican.

- **`DiskCache`** (`data/cache/`): un fichero JSON por clave en `files/cache/`. Cada escritura va a un temporal, se sincroniza con el disco (`fsync`) y se renombra encima del anterior: un corte de luz deja la versión vieja o la nueva, nunca una a medias. Un fichero ilegible cuenta como vacío y se vuelve a descargar.
- **`CachedResource<T>`**: un dato remoto con su copia (`Cached(value, fetchedAt)`), con la estrategia *stale-if-error*. Al crearse publica la copia de disco (el primer fotograma ya sale completo); `refresh` descarga, guarda y publica, y si falla deja la copia tal cual y devuelve el error; dos refrescos del mismo dato nunca se solapan; `isStale()` dice si ha caducado.
- **`IcsParser`** (`data/ical/`): lee el iCal línea a línea y descarta los eventos sueltos fuera del rango antes de dárselo a [biweekly](https://github.com/mangstadt/biweekly) (Java puro, sin librerías nativas), porque el fichero trae años de historial. Devuelve las apariciones del rango en la hora local del marco: repeticiones (RRULE, RDATE, EXDATE, a la misma hora local también tras el cambio de hora), apariciones movidas o canceladas (RECURRENCE-ID) y eventos de todo el día, uno por cada día que ocupan.
- **Repositorios** (`data/repository/`): `EventsRepository` (cada calendario con su copia `calendar-<hash de la URL>`, 5 min; junta los calendarios en su orden sin repetir eventos, por UID e inicio; los marcados como rutinas van a `sessions`; borra las copias de los que ya no están) y `WeatherRepository` (Open-Meteo; 30 min; se borra al cambiar de ubicación). Las copias guardan las apariciones sin nombre ni color del calendario: cambiarlos no obliga a descargar. Las respuestas HTTP sin éxito son `HttpError`: un calendario que responde con error (por ejemplo, con la dirección restablecida en Google) sale como "No responde" en Ajustes, pero no cuenta como falta de conexión.
- **`ReferenceClock`**: el RTC del marco no guarda la hora y, sin red, arranca en la fecha de fábrica. Mientras tanto todo se pinta con la última hora válida (guardada como mucho una vez por minuto), así que la caché se ve igual que la última vez; el reloj muestra "--:--". Sin hora válida no se pide nada a la red, porque las fechas de la petición serían las de fábrica.
- **`TaskBoard`** (`data/tasks/`): lógica pura de las tareas. Recibe un mensaje y el estado, y devuelve el estado nuevo y lo que hace el bot (reaccionar o contestar).
- **`TasksRepository`**: aplica cada mensaje del grupo configurado (los de otros chats y de bots se saltan) y guarda las tareas con el `offset` de getUpdates en un único fichero (`tasks`) antes de contestar en el grupo: un corte de luz no repite ni pierde mensajes. Las respuestas del bot son cortesía: si fallan, las tareas ya están. Un 409 es otro `getUpdates` con el mismo token.
- `Store` (SharedPreferences) solo guarda los calendarios (que escribe `scripts/calendars.sh`), el bot (`scripts/telegram.sh`) y los ajustes del dispositivo.

Tests (`./gradlew :app:testDebugUnitTest`): `IcsParserTest` (repeticiones, cambio de hora, excepciones, todo el día…), `EventsRepositoryTest` (con una API falsa), `TaskBoardTest`, `TasksRepositoryTest` (con un Telegram falso), `DiskCacheTest`, `CachedResourceTest`, `ReferenceClockTest`, `ForecastTest` y `ScreenScheduleTest`.

