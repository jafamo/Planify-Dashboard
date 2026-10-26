package com.lito.planify.dashboard

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lito.planify.dashboard.data.Api
import com.lito.planify.dashboard.data.CalendarSource
import com.lito.planify.dashboard.data.CalendarStatus
import com.lito.planify.dashboard.data.Content
import com.lito.planify.dashboard.data.Forecast
import com.lito.planify.dashboard.data.GeoResult
import com.lito.planify.dashboard.data.Location
import com.lito.planify.dashboard.data.ReferenceClock
import com.lito.planify.dashboard.data.Schedule
import com.lito.planify.dashboard.data.Store
import com.lito.planify.dashboard.data.cache.DiskCache
import com.lito.planify.dashboard.data.HttpError
import com.lito.planify.dashboard.data.repository.EventsRepository
import com.lito.planify.dashboard.data.repository.WeatherRepository
import com.lito.planify.dashboard.power.ScreenSchedule
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.io.File
import java.time.LocalDateTime

data class UiState(
    /** Calendarios configurados (scripts/calendars.sh). Sin ninguno se explica cómo añadirlos. */
    val calendars: List<CalendarSource> = emptyList(),
    /** Cuándo se descargó cada calendario y si falla, para Ajustes. */
    val calendarStatus: List<CalendarStatus> = emptyList(),
    val now: LocalDateTime = LocalDateTime.now(),
    /** false mientras el reloj no se haya sincronizado (el RTC vuelve a la fecha de fábrica). */
    val clockValid: Boolean = false,
    val content: Content? = null,
    val forecast: Forecast? = null,
    val location: Location? = null,
    val schedule: Schedule = Schedule(),
    val brightness: Float? = null,
    /** false si el último intento de sincronizar falló: se muestra lo que hay en caché. */
    val online: Boolean = true,
    /** Hay una red con salida a Internet (sin ella y sin calendarios, se muestra la espera y "Ir a Ajustes"). */
    val connected: Boolean = false,
    /** Sincronización manual en curso (botón de la barra lateral). */
    val syncing: Boolean = false,
    val geoResults: List<GeoResult> = emptyList(),
    val geoSearching: Boolean = false,
    /** Tocar el reloj nocturno enseña el dashboard hasta esta hora. */
    val peekUntil: LocalDateTime? = null,
    /** Reloj nocturno pedido con la luna de la barra lateral: hasta que se toque o hasta el siguiente encendido. */
    val nightUntil: LocalDateTime? = null
) {
    /** Reloj nocturno: fuera de horario (salvo mientras se mira el dashboard un momento) o pedido con la luna. */
    val night: Boolean
        get() = clockValid && calendars.isNotEmpty() && (
            (nightUntil != null && now.isBefore(nightUntil)) ||
                (ScreenSchedule.isNight(schedule, now) && (peekUntil == null || !now.isBefore(peekUntil)))
            )
}

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val store = Store(application)
    private val api = Api.client
    private val cache = DiskCache(File(application.filesDir, "cache"))
    private val eventsRepository = EventsRepository(api, cache).apply { setSources(store.calendars) }
    private val weatherRepository = WeatherRepository(api, cache)
    private val clock = ReferenceClock(BuildConfig.BUILD_TIME, { store.lastValidTime }, { store.lastValidTime = it })

    private val _state = MutableStateFlow(
        clock.current().let { time ->
            UiState(
                calendars = store.calendars,
                calendarStatus = eventsRepository.status.value,
                now = time.now,
                clockValid = time.valid,
                content = eventsRepository.content.value,
                forecast = weatherRepository.forecast.value?.value,
                location = store.location,
                schedule = store.schedule,
                brightness = store.brightness
            )
        }
    )
    val state: StateFlow<UiState> = _state

    private val connectivity = application.getSystemService(ConnectivityManager::class.java)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            setConnected(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))

        override fun onLost(network: Network) = setConnected(false)
    }

    init {
        // Los repositorios publican la copia en disco y cada descarga buena: la pantalla siempre tiene lo último.
        viewModelScope.launch { eventsRepository.content.collect { c -> _state.update { it.copy(content = c) } } }
        viewModelScope.launch { eventsRepository.status.collect { s -> _state.update { it.copy(calendarStatus = s) } } }
        viewModelScope.launch { weatherRepository.forecast.collect { f -> _state.update { it.copy(forecast = f?.value) } } }
        setConnected(connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
        connectivity.registerDefaultNetworkCallback(networkCallback)
    }

    override fun onCleared() {
        connectivity.unregisterNetworkCallback(networkCallback)
    }

    /** Al recuperar la red se sincroniza enseguida, sin esperar al siguiente ciclo. */
    private fun setConnected(connected: Boolean) {
        val was = _state.value.connected
        _state.update { it.copy(connected = connected) }
        if (connected && !was) refreshNow()
    }

    /** Bucle mientras la actividad está visible: reloj cada minuto; contenido y tiempo cuando caducan (5 y 30 min). */
    suspend fun run() {
        while (true) {
            tick()
            if (_state.value.clockValid) {
                if (eventsRepository.isStale()) refreshContent()
                if (weatherRepository.isStale()) refreshWeather()
            }
            delay(60_000 - System.currentTimeMillis() % 60_000 + 50)
        }
    }

    private fun tick() {
        val time = clock.current()
        _state.update { it.copy(now = time.now, clockValid = time.valid) }
    }

    /** Fuerza la sincronización (al recuperar la red...). */
    fun refreshNow() {
        viewModelScope.launch {
            tick()
            if (!_state.value.clockValid) return@launch
            refreshContent()
            refreshWeather()
        }
    }

    /** Sin hora válida no se pide nada: las fechas de la petición serían las de fábrica. */
    private suspend fun refreshContent() {
        val results = eventsRepository.refresh(LocalDate.now())
        if (results.isNotEmpty()) report(results.firstOrNull { it.isFailure && it.exceptionOrNull() !is HttpError } ?: results.first())
    }

    private suspend fun refreshWeather() {
        val location = _state.value.location ?: return
        report(weatherRepository.refresh(location))
    }

    /**
     * Botón de sincronizar: vuelve a pedir los calendarios y el tiempo, aunque no hayan caducado. Dura al menos un segundo para que se vea el giro.
     */
    fun syncAll() {
        if (_state.value.syncing) return
        viewModelScope.launch {
            _state.update { it.copy(syncing = true) }
            val started = System.currentTimeMillis()
            tick()
            if (_state.value.clockValid) {
                refreshContent()
                refreshWeather()
            }
            delay((1000 - (System.currentTimeMillis() - started)).coerceAtLeast(0))
            _state.update { it.copy(syncing = false) }
        }
    }

    /**
     * Estado de la conexión según la última petición. Si falla, la pantalla sigue con la copia en caché. Un servidor
     * que responde con error (una URL de calendario restablecida en Google) no es falta de conexión: se ve en Ajustes.
     */
    private fun report(result: Result<*>) {
        val error = result.exceptionOrNull()
        _state.update { it.copy(online = error == null || error is HttpError) }
    }

    fun searchLocation(query: String) {
        if (query.isBlank()) return
        _state.update { it.copy(geoSearching = true) }
        viewModelScope.launch {
            val results = runCatching { api.geocode(Api.geocodeUrl(query.trim())).body()?.results }.getOrNull().orEmpty()
            _state.update { it.copy(geoResults = results, geoSearching = false) }
        }
    }

    fun selectLocation(result: GeoResult) {
        val location = Location(
            name = result.name,
            detail = listOfNotNull(result.admin1, result.country).joinToString(", "),
            latitude = result.latitude,
            longitude = result.longitude
        )
        store.location = location
        weatherRepository.clear()
        _state.update { it.copy(location = location, geoResults = emptyList()) }
        viewModelScope.launch { refreshWeather() }
    }

    fun setSchedule(schedule: Schedule) {
        store.schedule = schedule
        _state.update { it.copy(schedule = schedule) }
        ScreenSchedule.reschedule(getApplication())
    }

    /**
     * Desde el reloj nocturno: vuelve el dashboard. Fuera de horario, solo durante [ScreenSchedule.GRACE_MINUTES];
     * el reloj pedido con la luna se quita del todo.
     */
    fun peek() {
        _state.update { it.copy(peekUntil = LocalDateTime.now().plusMinutes(ScreenSchedule.GRACE_MINUTES), nightUntil = null) }
    }

    /**
     * La luna de la barra lateral. Con el horario y el reloj nocturno activos, pasa al reloj (hasta que se toque o
     * hasta el siguiente encendido) y devuelve true; si no, false y la pantalla se apaga de verdad.
     */
    fun nightNow(): Boolean {
        val schedule = _state.value.schedule
        if (!schedule.enabled || schedule.screenOff || !_state.value.clockValid) return false
        _state.update { it.copy(nightUntil = ScreenSchedule.nextOn(schedule, it.now), peekUntil = null) }
        return true
    }

    fun setBrightness(value: Float?) {
        store.brightness = value
        _state.update { it.copy(brightness = value) }
    }
}
