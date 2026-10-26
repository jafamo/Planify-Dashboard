package com.lito.planify.dashboard.data.repository

import com.google.gson.reflect.TypeToken
import com.lito.planify.dashboard.data.Api
import com.lito.planify.dashboard.data.CalendarSource
import com.lito.planify.dashboard.data.CalendarStatus
import com.lito.planify.dashboard.data.Content
import com.lito.planify.dashboard.data.Event
import com.lito.planify.dashboard.data.bodyOrThrow
import com.lito.planify.dashboard.data.cache.CachedResource
import com.lito.planify.dashboard.data.cache.DiskCache
import com.lito.planify.dashboard.data.ical.IcsOccurrence
import com.lito.planify.dashboard.data.ical.IcsParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId

/**
 * Eventos y rutinas de los calendarios configurados, desde el día 1 del mes de hace [PAST_MONTHS] meses hasta
 * [FUTURE_MONTHS] meses después (lo que se puede ver en el calendario de Hoy y en Calendario).
 *
 * El iCal trae el calendario entero, así que cada uno se descarga de una vez y tiene su copia en disco
 * (`calendar-<hash de la URL>`, para no dejar la URL secreta en el nombre del fichero). Si uno falla, se sigue
 * mostrando lo último que se descargó de él y los demás no se ven afectados. Las copias guardan las apariciones sin
 * el nombre ni el color del calendario: cambiarlos no obliga a descargar nada.
 */
class EventsRepository(
    private val api: Api,
    private val cache: DiskCache,
    private val now: () -> Long = System::currentTimeMillis
) {
    private var sources: List<CalendarSource> = emptyList()
    private val resources = mutableMapOf<String, CachedResource<List<IcsOccurrence>>>()
    private val failing = mutableSetOf<String>()

    private val _content = MutableStateFlow<Content?>(null)
    /** Lo de todos los calendarios, sin repetir (null sin ninguna copia todavía). */
    val content: StateFlow<Content?> = _content

    private val _status = MutableStateFlow<List<CalendarStatus>>(emptyList())
    /** Cuándo se descargó cada calendario y si está fallando. */
    val status: StateFlow<List<CalendarStatus>> = _status

    /** Fija los calendarios. Publica enseguida lo que haya en disco y borra las copias de los que ya no están. */
    @Synchronized
    fun setSources(sources: List<CalendarSource>) {
        this.sources = sources
        val urls = sources.map { it.url }.toSet()
        resources.keys.retainAll(urls)
        failing.retainAll(urls)
        sources.forEach { resource(it.url) }
        val keep = urls.map(::key).toSet()
        cache.keys(PREFIX).filter { it !in keep }.forEach(cache::remove)
        publish()
    }

    /** Alguno no tiene copia, o la tiene de hace más de [MAX_AGE_MS]. */
    @Synchronized
    fun isStale() = sources.any { resource(it.url).isStale() }

    /**
     * Descarga todos los calendarios. Devuelve un resultado por calendario; con alguno descargado, el contenido ya
     * está publicado.
     */
    suspend fun refresh(today: LocalDate): List<Result<*>> {
        val from = today.withDayOfMonth(1).minusMonths(PAST_MONTHS)
        val to = today.withDayOfMonth(1).plusMonths(FUTURE_MONTHS + 1)
        val zone = ZoneId.systemDefault()
        return synchronized(this) { sources.toList() }.map { source ->
            val result = resource(source.url).refresh {
                val body = api.calendar(source.url).bodyOrThrow()
                withContext(Dispatchers.Default) { body.charStream().use { IcsParser.parse(it, from, to, zone) } }
            }
            synchronized(this) {
                if (result.isSuccess) failing -= source.url else failing += source.url
                publish()
            }
            result
        }
    }

    @Synchronized
    private fun resource(url: String): CachedResource<List<IcsOccurrence>> = resources.getOrPut(url) {
        CachedResource(cache, key(url), OCCURRENCES, MAX_AGE_MS, now)
    }

    /**
     * Junta los calendarios en el orden configurado. Si un evento está en dos (por ejemplo, el común y el de alguien
     * invitado a él), se queda el del primero.
     */
    private fun publish() {
        val seen = HashSet<String>()
        val events = ArrayList<Event>()
        val sessions = ArrayList<Event>()
        var any = false
        sources.forEachIndexed { index, source ->
            val cached = resources[source.url]?.data?.value ?: return@forEachIndexed
            any = true
            cached.value.forEach { occurrence ->
                val id = "${occurrence.uid}@${occurrence.date_at}"
                if (!seen.add(id)) return@forEach
                val event = Event(
                    id = id,
                    calendar_id = index,
                    calendar_name = source.name,
                    calendar_color = source.color,
                    title = occurrence.title,
                    date_at = occurrence.date_at,
                    all_day = occurrence.allDay
                )
                if (source.routines) sessions += event else events += event
            }
        }
        _content.value = if (any) Content(events.sortedBy { it.date_at }, sessions.sortedBy { it.date_at }) else null
        _status.value = sources.map { CalendarStatus(it, resources[it.url]?.data?.value?.fetchedAt, it.url in failing) }
    }

    companion object {
        /** Meses de eventos pasados, para el calendario de Hoy. */
        const val PAST_MONTHS = 12L
        /** Meses de eventos futuros: lo que llegan a enseñar el calendario de Hoy y Calendario. */
        const val FUTURE_MONTHS = 12L
        const val MAX_AGE_MS = 5 * 60_000L
        private const val PREFIX = "calendar-"
        private val OCCURRENCES = object : TypeToken<List<IcsOccurrence>>() {}.type

        private fun key(url: String) = PREFIX + MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    }
}
