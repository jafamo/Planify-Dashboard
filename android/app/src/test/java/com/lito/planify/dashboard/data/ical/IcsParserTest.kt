package com.lito.planify.dashboard.data.ical

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.StringReader
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

class IcsParserTest {
    private val madrid = ZoneId.of("Europe/Madrid")
    private val from = LocalDate.of(2026, 10, 1)
    private val to = LocalDate.of(2026, 12, 1)
    private lateinit var defaultZone: TimeZone

    @Before
    fun setUp() {
        defaultZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(madrid))
    }

    @After
    fun tearDown() = TimeZone.setDefault(defaultZone)

    /** Calendario como el de Google: cabecera, VTIMEZONE de Madrid y los eventos dados. */
    private fun ics(vararg events: String) = """
        BEGIN:VCALENDAR
        PRODID:-//Google Inc//Google Calendar 70.9054//EN
        VERSION:2.0
        CALSCALE:GREGORIAN
        X-WR-CALNAME:Familia
        X-WR-TIMEZONE:Europe/Madrid
        BEGIN:VTIMEZONE
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
        END:VTIMEZONE
    """.trimIndent() + "\n" + events.joinToString("\n") { it.trimIndent() } + "\nEND:VCALENDAR\n"

    private fun parse(text: String, zone: ZoneId = madrid) =
        IcsParser.parse(StringReader(text.replace("\n", "\r\n")), from, to, zone)

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0) = LocalDateTime.of(y, m, d, h, min)

    @Test
    fun timedEventInItsZone() {
        val result = parse(ics("""
            BEGIN:VEVENT
            DTSTART;TZID=Europe/Madrid:20261014T160000
            DTEND;TZID=Europe/Madrid:20261014T170000
            UID:dentista@google.com
            SUMMARY:Dentista
            END:VEVENT
        """))
        assertEquals(listOf(IcsOccurrence("dentista@google.com", "Dentista", at(2026, 10, 14, 16), allDay = false)), result)
    }

    @Test
    fun utcTimesAreShownInTheFrameZone() {
        val result = parse(ics("""
            BEGIN:VEVENT
            DTSTART:20261014T140000Z
            DTEND:20261014T150000Z
            UID:utc@google.com
            SUMMARY:Reunión
            END:VEVENT
        """))
        assertEquals(at(2026, 10, 14, 16), result.single().start)
    }

    @Test
    fun weeklyRepeatKeepsLocalTimeAcrossDaylightSaving() {
        // El 25 de octubre de 2026 se pasa a horario de invierno: sigue siendo a las 17:00.
        val result = parse(ics("""
            BEGIN:VEVENT
            DTSTART;TZID=Europe/Madrid:20261019T170000
            DTEND;TZID=Europe/Madrid:20261019T180000
            RRULE:FREQ=WEEKLY;BYDAY=MO,WE
            UID:natacion@google.com
            SUMMARY:Natación
            END:VEVENT
        """))
        val october = result.filter { it.start.monthValue == 10 }.map { it.start }
        assertEquals(listOf(at(2026, 10, 19, 17), at(2026, 10, 21, 17), at(2026, 10, 26, 17), at(2026, 10, 28, 17)), october)
        assertTrue(result.all { it.start.hour == 17 })
        assertEquals(LocalDate.of(2026, 11, 30), result.last().start.toLocalDate())
    }

    @Test
    fun repeatStartedLongAgoOnlyInsideTheRange() {
        val result = parse(ics("""
            BEGIN:VEVENT
            DTSTART;TZID=Europe/Madrid:20200106T090000
            DTEND;TZID=Europe/Madrid:20200106T100000
            RRULE:FREQ=MONTHLY;BYDAY=1MO
            UID:primer-lunes@google.com
            SUMMARY:Primer lunes
            END:VEVENT
        """))
        assertEquals(listOf(at(2026, 10, 5, 9), at(2026, 11, 2, 9)), result.map { it.start })
    }

    @Test
    fun countAndUntilEndTheRepeat() {
        val result = parse(ics("""
            BEGIN:VEVENT
            DTSTART;TZID=Europe/Madrid:20261001T080000
            RRULE:FREQ=DAILY;COUNT=3
            UID:tres@google.com
            SUMMARY:Tres días
            END:VEVENT
        """, """
            BEGIN:VEVENT
            DTSTART;TZID=Europe/Madrid:20261110T200000
            RRULE:FREQ=WEEKLY;UNTIL=20261124T225959Z
            UID:hasta@google.com
            SUMMARY:Hasta el 24
            END:VEVENT
        """))
        assertEquals(3, result.count { it.uid == "tres@google.com" })
        assertEquals(
            listOf(at(2026, 11, 10, 20), at(2026, 11, 17, 20), at(2026, 11, 24, 20)),
            result.filter { it.uid == "hasta@google.com" }.map { it.start }
        )
    }

    @Test
    fun secondTuesdayOfTheMonth() {
        val result = parse(ics("""
            BEGIN:VEVENT
            DTSTART;TZID=Europe/Madrid:20260113T190000
            RRULE:FREQ=MONTHLY;BYDAY=2TU
            UID:ampa@google.com
            SUMMARY:Reunión AMPA
            END:VEVENT
        """))
        assertEquals(listOf(at(2026, 10, 13, 19), at(2026, 11, 10, 19)), result.map { it.start })
    }

    @Test
    fun excludedDateIsSkipped() {
        val result = parse(ics("""
            BEGIN:VEVENT
            DTSTART;TZID=Europe/Madrid:20261005T170000
            RRULE:FREQ=WEEKLY;COUNT=4
            EXDATE;TZID=Europe/Madrid:20261012T170000
            UID:ingles@google.com
            SUMMARY:Inglés
            END:VEVENT
        """))
        assertEquals(listOf(at(2026, 10, 5, 17), at(2026, 10, 19, 17), at(2026, 10, 26, 17)), result.map { it.start })
    }

    @Test
    fun movedAndCancelledOccurrences() {
        val result = parse(ics("""
            BEGIN:VEVENT
            DTSTART;TZID=Europe/Madrid:20261005T170000
            RRULE:FREQ=WEEKLY;COUNT=4
            UID:futbol@google.com
            SUMMARY:Fútbol
            END:VEVENT
        """, """
            BEGIN:VEVENT
            DTSTART;TZID=Europe/Madrid:20261013T180000
            RECURRENCE-ID;TZID=Europe/Madrid:20261012T170000
            UID:futbol@google.com
            SUMMARY:Fútbol (cambiado)
            END:VEVENT
        """, """
            BEGIN:VEVENT
            DTSTART;TZID=Europe/Madrid:20261019T170000
            RECURRENCE-ID;TZID=Europe/Madrid:20261019T170000
            STATUS:CANCELLED
            UID:futbol@google.com
            SUMMARY:Fútbol
            END:VEVENT
        """))
        assertEquals(
            listOf(at(2026, 10, 5, 17) to "Fútbol", at(2026, 10, 13, 18) to "Fútbol (cambiado)", at(2026, 10, 26, 17) to "Fútbol"),
            result.map { it.start to it.title }
        )
    }

    @Test
    fun cancelledEventIsSkipped() {
        val result = parse(ics("""
            BEGIN:VEVENT
            DTSTART;TZID=Europe/Madrid:20261014T160000
            STATUS:CANCELLED
            UID:cancelado@google.com
            SUMMARY:Cancelado
            END:VEVENT
        """))
        assertTrue(result.isEmpty())
    }

    @Test
    fun allDayEventSpanningSeveralDays() {
        // DTEND es exclusivo: del 30 de octubre al 1 de noviembre, ambos incluidos.
        val result = parse(ics("""
            BEGIN:VEVENT
            DTSTART;VALUE=DATE:20261030
            DTEND;VALUE=DATE:20261102
            UID:puente@google.com
            SUMMARY:Puente
            END:VEVENT
        """))
        assertEquals(
            listOf(LocalDate.of(2026, 10, 30), LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 1)),
            result.map { it.start.toLocalDate() }
        )
        assertTrue(result.all { it.allDay && it.start.toLocalTime().toSecondOfDay() == 0 })
    }

    @Test
    fun allDayEventStartedBeforeTheRangeKeepsItsLastDays() {
        val result = parse(ics("""
            BEGIN:VEVENT
            DTSTART;VALUE=DATE:20260928
            DTEND;VALUE=DATE:20261003
            UID:viaje@google.com
            SUMMARY:Viaje
            END:VEVENT
        """))
        assertEquals(listOf(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2)), result.map { it.start.toLocalDate() })
    }

    @Test
    fun yearlyAllDayEvent() {
        val result = parse(ics("""
            BEGIN:VEVENT
            DTSTART;VALUE=DATE:19850312
            DTEND;VALUE=DATE:19850313
            RRULE:FREQ=YEARLY
            UID:cumple-ana@google.com
            SUMMARY:Cumpleaños de Ana
            END:VEVENT
        """, """
            BEGIN:VEVENT
            DTSTART;VALUE=DATE:19901104
            DTEND;VALUE=DATE:19901105
            RRULE:FREQ=YEARLY
            UID:cumple-luis@google.com
            SUMMARY:Cumpleaños de Luis
            END:VEVENT
        """))
        assertEquals(listOf(IcsOccurrence("cumple-luis@google.com", "Cumpleaños de Luis", at(2026, 11, 4, 0), allDay = true)), result)
    }

    @Test
    fun allDayDatesDoNotDependOnTheDefaultZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        val result = parse(ics("""
            BEGIN:VEVENT
            DTSTART;VALUE=DATE:20261030
            DTEND;VALUE=DATE:20261031
            RRULE:FREQ=WEEKLY;COUNT=2
            UID:semanal@google.com
            SUMMARY:Semanal
            END:VEVENT
        """))
        assertEquals(listOf(LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 6)), result.map { it.start.toLocalDate() })
    }

    @Test
    fun eventsOutsideTheRangeAreLeftOut() {
        val result = parse(ics("""
            BEGIN:VEVENT
            DTSTART;TZID=Europe/Madrid:20190101T100000
            UID:viejo@google.com
            SUMMARY:Viejo
            END:VEVENT
        """, """
            BEGIN:VEVENT
            DTSTART;TZID=Europe/Madrid:20270101T100000
            UID:lejano@google.com
            SUMMARY:Lejano
            END:VEVENT
        """, """
            BEGIN:VEVENT
            DTSTART;TZID=Europe/Madrid:20261201T000000
            UID:justo-fuera@google.com
            SUMMARY:Justo fuera
            END:VEVENT
        """))
        assertTrue(result.isEmpty())
    }

    @Test
    fun foldedLinesAndEscapedText() {
        val result = parse(ics("""
            BEGIN:VEVENT
            DTSTART;TZID=Europe/Madrid:20261014T160000
            UID:largo@google.com
            SUMMARY:Recoger a los niños\, comprar pan y pasar por la farmacia antes de l
             as siete
            END:VEVENT
        """))
        assertEquals("Recoger a los niños, comprar pan y pasar por la farmacia antes de las siete", result.single().title)
    }

    @Test
    fun eventWithoutTitle() {
        val result = parse(ics("""
            BEGIN:VEVENT
            DTSTART;TZID=Europe/Madrid:20261014T160000
            UID:sin-titulo@google.com
            END:VEVENT
        """))
        assertEquals("(Sin título)", result.single().title)
    }

    @Test
    fun prefilterKeepsRepeatsAndDropsOldEvents() {
        val text = ics("""
            BEGIN:VEVENT
            DTSTART;TZID=Europe/Madrid:20190101T100000
            UID:viejo@google.com
            END:VEVENT
        """, """
            BEGIN:VEVENT
            DTSTART;TZID=Europe/Madrid:20190101T100000
            RRULE:FREQ=WEEKLY
            UID:repite@google.com
            END:VEVENT
        """)
        val filtered = IcsParser.prefilter(StringReader(text).buffered(), from, to)
        assertFalse("viejo@google.com" in filtered)
        assertTrue("repite@google.com" in filtered)
        assertTrue("BEGIN:VTIMEZONE" in filtered && filtered.trimEnd().endsWith("END:VCALENDAR"))
    }
}
