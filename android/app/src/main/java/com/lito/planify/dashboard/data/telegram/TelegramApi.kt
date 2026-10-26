package com.lito.planify.dashboard.data.telegram

import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

/**
 * Bot API de Telegram (lo justo para las tareas). Cada llamada lleva su URL completa, con el token:
 * `https://api.telegram.org/bot<token>/<método>` ([url]).
 */
interface TelegramApi {
    /** Espera larga: Telegram contesta en cuanto hay mensajes o a los [timeout] segundos sin nada. */
    @FormUrlEncoded
    @POST
    suspend fun getUpdates(
        @Url url: String,
        @Field("offset") offset: Long,
        @Field("timeout") timeout: Int = POLL_SECONDS,
        @Field("allowed_updates") allowedUpdates: String = """["message"]"""
    ): Response<TgResponse<List<TgUpdate>>>

    @FormUrlEncoded
    @POST
    suspend fun sendMessage(
        @Url url: String,
        @Field("chat_id") chatId: Long,
        @Field("text") text: String,
        @Field("reply_to_message_id") replyTo: Long? = null,
        @Field("disable_notification") silent: Boolean = true
    ): Response<TgResponse<TgMessage>>

    /** [reaction] en JSON: `[{"type":"emoji","emoji":"👌"}]`. */
    @FormUrlEncoded
    @POST
    suspend fun setMessageReaction(
        @Url url: String,
        @Field("chat_id") chatId: Long,
        @Field("message_id") messageId: Long,
        @Field("reaction") reaction: String
    ): Response<TgResponse<Boolean>>

    companion object {
        const val POLL_SECONDS = 50

        fun url(token: String, method: String) = "https://api.telegram.org/bot$token/$method"

        fun reaction(emoji: String) = """[{"type":"emoji","emoji":"$emoji"}]"""

        val client: TelegramApi by lazy {
            // La lectura tiene que durar más que la espera larga de getUpdates
            val http = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(POLL_SECONDS + 20L, TimeUnit.SECONDS)
                .build()
            Retrofit.Builder()
                .baseUrl("https://api.telegram.org/")
                .client(http)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(TelegramApi::class.java)
        }
    }
}

/** Respuesta de la Bot API: `ok` y `result`, o `description` con el error. */
data class TgResponse<T>(val ok: Boolean, val result: T?, val description: String? = null, val error_code: Int? = null)

data class TgUpdate(val update_id: Long, val message: TgMessage? = null)

data class TgMessage(
    val message_id: Long,
    val from: TgUser? = null,
    val chat: TgChat,
    val date: Long,
    val text: String? = null,
    val reply_to_message: TgMessage? = null
)

data class TgUser(val id: Long, val is_bot: Boolean, val first_name: String)

data class TgChat(val id: Long, val type: String, val title: String? = null)
