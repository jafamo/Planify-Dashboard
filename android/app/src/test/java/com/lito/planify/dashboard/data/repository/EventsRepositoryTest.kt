package com.lito.planify.dashboard.data.repository

import com.lito.planify.dashboard.data.Api
import com.lito.planify.dashboard.data.CalendarSource
import com.lito.planify.dashboard.data.Forecast
import com.lito.planify.dashboard.data.GeoResponse
import com.lito.planify.dashboard.data.HttpError
import com.lito.planify.dashboard.data.cache.DiskCache
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Response
import java.io.IOException
import java.time.LocalDate

class EventsRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val today = LocalDate.of(2026, 10, 26)

    /** API falsa: un iCal por URL, o falla como se le diga. */
    private class FakeApi(val calendars: MutableMap<String, String>) : Api {
        val failures = mutableMapOf<String, Exception>()

        override suspend fun calendar(url: String): Response<ResponseBody> {
            failures[url]?.let { throw it }
            val ics = calendars[url] ?: return Response.error(404, "".toResponseBody())
            return Response.success(ics.toResponseBody())
        }

        override suspend fun forecast(url: String): Response<Forecast> = error("no se usa")
        override suspend fun geocode(url: String): Response<GeoResponse> = error("no se usa")
    }

    private fun ics(vararg events: Pair<String, String>) = buildString {
        append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n")
        events.forEach { (uid, start) -> append("BEGIN:VEVENT\r\nUID:$uid\r\nDTSTART:$start\r\nSUMMARY:$uid\r\nEND:VEVENT\r\n") }
        append("END:VCALENDAR\r\n")
    }

    private val family = CalendarSource("Familia", "https://example.com/familia.ics", "#3B82F6")
    private val ana = CalendarSource("Ana", "https://example.com/ana.ics", "#EF4444")
    private val routines = CalendarSource("Rutinas", "https://example.com/rutinas.ics", "#F59E0B", routines = true)

    private val api = FakeApi(
        mutableMapOf(
            family.url to ics("cena" to "20261027T200000", "compartido" to "20261028T100000"),
            ana.url to ics("compartido" to "20261028T100000", "dentista" to "20261029T090000"),
            routines.url to ics("cole" to "20261027T083000")
        )
    )

    private fun repository(cacheDir: String = "cache", now: () -> Long = System::currentTimeMillis) =
        EventsRepository(api, DiskCache(folder.root.resolve(cacheDir)), now)

    @Test
    fun joinsCalendarsInOrderWithoutDuplicates() = runBlocking {
        val repository = repository()
        repository.setSources(listOf(family, ana, routines))
        repository.refresh(today)

        val content = repository.content.value!!
        assertEquals(listOf("cena", "compartido", "dentista"), content.events.map { it.title })
        // El compartido sale con el primer calendario que lo tiene.
        assertEquals("Familia", content.events.single { it.title == "compartido" }.calendar_name)
        assertEquals("#EF4444", content.events.single { it.title == "dentista" }.calendar_color)
        assertEquals(listOf("cole"), content.sessions.map { it.title })
        assertEquals("2026-10-27 08:30:00", content.sessions.single().date_at)
    }

    @Test
    fun failingCalendarKeepsItsLastCopyAndTheOthersUpdate() = runBlocking {
        val repository = repository()
        repository.setSources(listOf(family, ana))
        repository.refresh(today)

        api.failures[ana.url] = IOException("sin red")
        api.calendars[family.url] = ics("nuevo" to "20261030T120000")
        val results = repository.refresh(today)

        assertTrue(results[0].isSuccess)
        assertTrue(results[1].isFailure)
        assertEquals(listOf("compartido", "dentista", "nuevo"), repository.content.value!!.events.map { it.title })
        val status = repository.status.value
        assertFalse(status[0].failing)
        assertTrue(status[1].failing)
        assertTrue(status[1].fetchedAt != null)
    }

    @Test
    fun resetUrlIsAnHttpError() = runBlocking {
        val repository = repository()
        repository.setSources(listOf(family.copy(url = "https://example.com/restablecida.ics")))
        val result = repository.refresh(today).single()
        assertTrue(result.exceptionOrNull() is HttpError)
        assertNull(repository.content.value)
    }

    @Test
    fun copiesSurviveARestartAndFollowNameAndColor() = runBlocking {
        repository().apply {
            setSources(listOf(family))
            refresh(today)
        }
        // Sin red y con otro nombre y color: se pinta la copia del disco, con lo nuevo.
        api.failures[family.url] = IOException("sin red")
        val restarted = repository()
        restarted.setSources(listOf(family.copy(name = "Casa", color = "#10B981")))
        val events = restarted.content.value!!.events
        assertEquals(listOf("cena", "compartido"), events.map { it.title })
        assertTrue(events.all { it.calendar_name == "Casa" && it.calendar_color == "#10B981" })
    }

    @Test
    fun removedCalendarLeavesNoCopy() = runBlocking {
        val repository = repository()
        repository.setSources(listOf(family, ana))
        repository.refresh(today)
        repository.setSources(listOf(family))

        assertEquals(1, folder.root.resolve("cache").listFiles()!!.count { it.name.startsWith("calendar-") })
        assertEquals(listOf("cena", "compartido"), repository.content.value!!.events.map { it.title })
        // El nombre del fichero no lleva la URL secreta.
        assertTrue(folder.root.resolve("cache").listFiles()!!.none { "example" in it.name })
    }

    @Test
    fun staleAfterFiveMinutes() = runBlocking {
        var clock = 1_000_000L
        val repository = repository(now = { clock })
        repository.setSources(listOf(family))
        assertTrue(repository.isStale())
        repository.refresh(today)
        assertFalse(repository.isStale())
        clock += EventsRepository.MAX_AGE_MS
        assertTrue(repository.isStale())
    }
}
