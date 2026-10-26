package com.lito.planify.dashboard.data

import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Streaming
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

/** Calendarios iCal (solo lectura) y Open-Meteo. */
interface Api {
    /** La dirección secreta iCal de un calendario de Google, entera: se lee a trozos (IcsParser). */
    @Streaming
    @GET
    suspend fun calendar(@Url url: String): Response<ResponseBody>

    @GET
    suspend fun forecast(@Url url: String): Response<Forecast>

    @GET
    suspend fun geocode(@Url url: String): Response<GeoResponse>

    companion object {
        val client: Api by lazy {
            val http = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            Retrofit.Builder()
                .baseUrl("https://api.open-meteo.com/") // Todas las peticiones llevan su URL completa
                .client(http)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(Api::class.java)
        }

        fun forecastUrl(location: Location) = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${location.latitude}&longitude=${location.longitude}&timezone=auto&past_days=1&forecast_days=14" +
            "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m,wind_direction_10m,is_day" +
            "&hourly=temperature_2m,precipitation_probability,precipitation,weather_code,is_day" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum,wind_speed_10m_max,sunrise,sunset"

        fun geocodeUrl(query: String) = "https://geocoding-api.open-meteo.com/v1/search?count=8&language=es&format=json&name=" +
            java.net.URLEncoder.encode(query, "UTF-8")
    }
}
