package com.lito.planify.dashboard.data

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lito.planify.dashboard.data.repository.TelegramConfig

/** Horario de pantalla en minutos desde medianoche, por tipo de día. */
data class Schedule(
    val enabled: Boolean = false,
    val weekdayOn: Int = 7 * 60,
    val weekdayOff: Int = 23 * 60,
    val weekendOn: Int = 8 * 60 + 30,
    val weekendOff: Int = 23 * 60 + 30,
    /**
     * Fuera de horario: false (por defecto) muestra el reloj nocturno con el brillo al mínimo; true apaga la pantalla.
     * En negativo para que los horarios guardados antes de existir (Gson deja el campo a false) usen el reloj.
     */
    val screenOff: Boolean = false
)

/**
 * Calendarios, bot de tareas y ajustes del dispositivo (SharedPreferences). Los datos descargados van en DiskCache.
 * Los calendarios y el bot los escriben `scripts/calendars.sh` y `scripts/telegram.sh` por ADB (las URLs y el token
 * son largos para teclearlos en el marco).
 */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("dashboard", Context.MODE_PRIVATE)
    private val gson = Gson()

    val calendars: List<CalendarSource>
        get() = prefs.getString("calendars", null)
            ?.let { runCatching { gson.fromJson<List<CalendarSource>>(it, CALENDARS) }.getOrNull() }
            .orEmpty()
            .filter { it.url.isNotBlank() }

    /** El bot de Telegram de las tareas (scripts/telegram.sh); null sin configurar: no hay pantalla de Tareas. */
    val telegram: TelegramConfig?
        get() = read<TelegramConfig>("telegram")?.takeIf { it.token.isNotBlank() && it.chat_id != 0L }

    var location: Location?
        get() = read("location")
        set(value) = write("location", value)

    var schedule: Schedule
        get() = read("schedule") ?: Schedule()
        set(value) = write("schedule", value)

    /** Brillo de la ventana (0.05–1); null = el del sistema. */
    var brightness: Float?
        get() = prefs.getFloat("brightness", -1f).takeIf { it > 0f }
        set(value) = prefs.edit { putFloat("brightness", value ?: -1f) }

    /** Última hora válida (epoch ms), para ReferenceClock. */
    var lastValidTime: Long
        get() = prefs.getLong("last_valid_time", 0L)
        set(value) = prefs.edit { putLong("last_valid_time", value) }

    init {
        // La caché de datos estaba aquí antes de pasar a disco (DiskCache): se descarta, se vuelve a descargar.
        if (prefs.contains("content") || prefs.contains("forecast") || prefs.contains("weeks")) {
            prefs.edit {
                remove("content")
                remove("forecast")
                remove("weeks")
            }
        }
        // La sesión de Planify, de antes de leer los calendarios de Google.
        if (prefs.contains("token")) prefs.edit {
            remove("token")
            remove("email")
        }
    }

    private inline fun <reified T> read(key: String): T? =
        prefs.getString(key, null)?.let { runCatching { gson.fromJson(it, T::class.java) }.getOrNull() }

    private fun write(key: String, value: Any?) = prefs.edit { if (value == null) remove(key) else putString(key, gson.toJson(value)) }

    private companion object {
        val CALENDARS = object : TypeToken<List<CalendarSource>>() {}.type
    }
}
