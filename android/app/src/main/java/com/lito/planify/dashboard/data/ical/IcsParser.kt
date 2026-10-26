package com.lito.planify.dashboard.data.ical

import biweekly.Biweekly
import biweekly.ICalendar
import biweekly.component.VEvent
import biweekly.property.ICalProperty
import biweekly.util.ICalDate
import com.lito.planify.dashboard.data.SQL_DATE_TIME
import java.io.BufferedReader
import java.io.Reader
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.TimeZone

/**
 * Una aparición de un evento del iCal, en la hora local del marco. Los de todo el día, una por día y a las 00:00.
 * El inicio se guarda como texto (`date_at`, como Event) porque va a la caché en disco con Gson.
 */
data class IcsOccurrence(val uid: String, val title: String, val date_at: String, val allDay: Boolean) {
    constructor(uid: String, title: String, start: LocalDateTime, allDay: Boolean) : this(uid, title, start.format(SQL_DATE_TIME), allDay)

    val start: LocalDateTime get() = LocalDateTime.parse(date_at, SQL_DATE_TIME)
}

/**
 * Lee un calendario iCal (la «dirección secreta» de Google Calendar) y devuelve las apariciones de sus eventos entre
 * [from] y [to] (sin incluir [to]): las repeticiones (RRULE, RDATE, EXDATE), las apariciones movidas o canceladas
 * (RECURRENCE-ID) y los eventos de varios días. Las horas se pasan a [zone].
 *
 * El fichero trae todo el historial del calendario, así que antes de dárselo a biweekly se recorre línea a línea y
 * se descartan los eventos sueltos que caen fuera del rango: con 1 GB de RAM no se puede cargar entero.
 */
object IcsParser {
    fun parse(reader: Reader, from: LocalDate, to: LocalDate, zone: ZoneId): List<IcsOccurrence> {
        val text = prefilter(BufferedReader(reader), from, to)
        val calendar = Biweekly.parse(text).first() ?: return emptyList()
        val events = calendar.events.filter { it.uid?.value != null && it.dateStart?.value != null }
        // Apariciones sueltas que sustituyen a una de la repetición (movida, cambiada o cancelada), por UID e inicio original.
        val overrides = events.filter { it.recurrenceId != null }
            .groupBy { it.uid.value }
            .mapValues { (_, list) -> list.mapTo(HashSet()) { it.recurrenceId.value.time } }
        val occurrences = ArrayList<IcsOccurrence>()
        events.forEach { event ->
            if (event.status?.isCancelled == true) return@forEach
            val replaced = if (event.recurrenceId == null) overrides[event.uid.value].orEmpty() else emptySet()
            expand(calendar, event, replaced, from, to, zone, occurrences)
        }
        return occurrences.sortedBy { it.date_at }
    }

    private fun expand(
        calendar: ICalendar,
        event: VEvent,
        replaced: Set<Long>,
        from: LocalDate,
        to: LocalDate,
        zone: ZoneId,
        out: MutableList<IcsOccurrence>
    ) {
        val uid = event.uid.value
        val title = event.summary?.value?.trim().orEmpty().ifEmpty { "(Sin título)" }
        val start = event.dateStart.value
        val allDay = !start.hasTime()
        val days = if (allDay) durationDays(event) else 1L
        // Los de todo el día son fechas sin zona; los demás se repiten en la zona de su DTSTART (a la misma hora
        // local también tras el cambio de hora) y se muestran en la del marco.
        val tz = if (allDay) TimeZone.getDefault() else timezoneOf(calendar, event.dateStart)
        val dates: Iterator<Date> =
            if (event.recurrenceRule != null || event.recurrenceDates.isNotEmpty()) {
                event.getDateIterator(tz).apply {
                    // Un evento de varios días que empezó antes de [from] sigue viéndose en sus últimos días.
                    advanceTo(Date.from(from.minusDays(days).atStartOfDay(tz.toZoneId()).toInstant()))
                }
            } else {
                listOf<Date>(start).iterator()
            }
        val first = from.minusDays(days)
        while (dates.hasNext()) {
            val date = dates.next()
            if (date.time in replaced) continue
            if (allDay) {
                val day = date.toInstant().atZone(tz.toZoneId()).toLocalDate()
                if (day >= to) break
                if (day < first) continue
                for (i in 0 until days) {
                    val d = day.plusDays(i)
                    if (d >= from && d < to) out += IcsOccurrence(uid, title, d.atStartOfDay(), allDay = true)
                }
            } else {
                val local = date.toInstant().atZone(zone).toLocalDateTime()
                if (local.toLocalDate() >= to) break
                if (local.toLocalDate() >= from) out += IcsOccurrence(uid, title, local, allDay = false)
            }
        }
    }

    /** Días que ocupa un evento de todo el día: DTEND es exclusivo (un evento de un día termina al día siguiente). */
    private fun durationDays(event: VEvent): Long {
        val start = event.dateStart.value.localDate()
        val end = event.dateEnd?.value?.localDate()
            ?: event.duration?.value?.let { start.plusDays(it.toMillis() / 86_400_000L) }
        return end?.let { ChronoUnit.DAYS.between(start, it) }?.coerceAtLeast(1) ?: 1
    }

    private fun timezoneOf(calendar: ICalendar, property: ICalProperty): TimeZone =
        calendar.timezoneInfo.getTimezone(property)?.timeZone
            ?: if (calendar.timezoneInfo.isFloating(property)) TimeZone.getDefault() else TimeZone.getTimeZone("UTC")

    private fun ICalDate.localDate(): LocalDate = rawComponents.let { LocalDate.of(it.year, it.month, it.date) }

    /**
     * Copia el calendario sin los eventos sueltos que caen fuera del rango (con un día de margen por las zonas
     * horarias). Los que se repiten, o sustituyen a una aparición de una repetición, se quedan siempre.
     */
    internal fun prefilter(reader: BufferedReader, from: LocalDate, to: LocalDate): String {
        val out = StringBuilder()
        val event = ArrayList<String>()
        var inEvent = false
        var pending: String? = null

        fun emit(line: String) {
            when {
                line == "BEGIN:VEVENT" -> { inEvent = true; event.clear(); event += line }
                inEvent -> {
                    event += line
                    if (line == "END:VEVENT") {
                        inEvent = false
                        if (keep(event, from.minusDays(1), to.plusDays(1))) event.forEach { out.append(it).append("\r\n") }
                    }
                }
                else -> out.append(line).append("\r\n")
            }
        }

        // Las líneas largas vienen partidas: las que empiezan por espacio o tabulador siguen a la anterior.
        reader.forEachLine { raw ->
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && pending != null) {
                pending += raw.substring(1)
            } else {
                pending?.let(::emit)
                pending = raw
            }
        }
        pending?.let(::emit)
        return out.toString()
    }

    private fun keep(lines: List<String>, from: LocalDate, to: LocalDate): Boolean {
        if (lines.any { it.startsWith("RRULE") || it.startsWith("RDATE") || it.startsWith("RECURRENCE-ID") }) return true
        val start = dateOf(lines, "DTSTART") ?: return true
        val end = dateOf(lines, "DTEND") ?: start
        return !end.isBefore(from) && !start.isAfter(to)
    }

    /** La fecha (yyyyMMdd) de una propiedad como `DTSTART;TZID=Europe/Madrid:20261026T170000`. */
    private fun dateOf(lines: List<String>, name: String): LocalDate? {
        val line = lines.firstOrNull { it.startsWith("$name:") || it.startsWith("$name;") } ?: return null
        val value = line.substringAfter(':').take(8)
        return runCatching { LocalDate.parse(value, BASIC_DATE) }.getOrNull()
    }

    private val BASIC_DATE: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
}
