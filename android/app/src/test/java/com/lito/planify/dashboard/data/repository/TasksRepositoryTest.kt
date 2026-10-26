package com.lito.planify.dashboard.data.repository

import com.lito.planify.dashboard.data.HttpError
import com.lito.planify.dashboard.data.cache.DiskCache
import com.lito.planify.dashboard.data.tasks.TaskBoard
import com.lito.planify.dashboard.data.telegram.TelegramApi
import com.lito.planify.dashboard.data.telegram.TgChat
import com.lito.planify.dashboard.data.telegram.TgMessage
import com.lito.planify.dashboard.data.telegram.TgResponse
import com.lito.planify.dashboard.data.telegram.TgUpdate
import com.lito.planify.dashboard.data.telegram.TgUser
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Response
import java.io.IOException

class TasksRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val config = TelegramConfig("123:ABC", chat_id = -1001)

    /** Telegram falso: entrega los updates pendientes desde el offset pedido y apunta lo que contesta el bot. */
    private class FakeTelegram : TelegramApi {
        val pending = mutableListOf<TgUpdate>()
        val offsets = mutableListOf<Long>()
        val sent = mutableListOf<String>()
        val reactions = mutableListOf<Pair<Long, String>>()
        var failure: Exception? = null
        var status = 200

        override suspend fun getUpdates(url: String, offset: Long, timeout: Int, allowedUpdates: String): Response<TgResponse<List<TgUpdate>>> {
            failure?.let { throw it }
            if (status != 200) return Response.error(status, "".toResponseBody())
            offsets += offset
            return Response.success(TgResponse(true, pending.filter { it.update_id >= offset }))
        }

        override suspend fun sendMessage(url: String, chatId: Long, text: String, replyTo: Long?, silent: Boolean): Response<TgResponse<TgMessage>> {
            sent += text
            return Response.success(TgResponse(true, null))
        }

        override suspend fun setMessageReaction(url: String, chatId: Long, messageId: Long, reaction: String): Response<TgResponse<Boolean>> {
            reactions += messageId to reaction
            return Response.success(TgResponse(true, true))
        }
    }

    private val telegram = FakeTelegram()
    private var nextUpdate = 10L

    private fun say(text: String, chat: Long = config.chat_id, bot: Boolean = false) {
        val id = nextUpdate++
        telegram.pending += TgUpdate(id, TgMessage(id * 10, TgUser(7, bot, "Ana"), TgChat(chat, "supergroup", "Casa"), 1_790_000_000, text))
    }

    private fun repository() = TasksRepository(telegram, DiskCache(folder.root.resolve("cache")), config) { 1_000L }

    @Test
    fun messagesBecomeTasksAndTheBotReacts() = runBlocking {
        val repository = repository()
        say("leche #compra")
        say("pan")
        repository.poll()

        assertEquals(listOf("leche", "pan"), repository.tasks.value.tasks.map { it.title })
        assertEquals(listOf(100L to TelegramApi.reaction(TaskBoard.ADDED), 110L to TelegramApi.reaction(TaskBoard.ADDED)), telegram.reactions)
        assertEquals(TasksStatus("Casa", 1_000L, failing = false), repository.status.value)
    }

    @Test
    fun offsetAdvancesSoNothingIsProcessedTwice() = runBlocking {
        val repository = repository()
        say("pan")
        repository.poll()
        repository.poll()
        assertEquals(listOf(0L, 11L), telegram.offsets)
        assertEquals(1, repository.tasks.value.tasks.size)
    }

    @Test
    fun tasksAndOffsetSurviveARestart() = runBlocking {
        say("pan")
        repository().poll()
        val restarted = repository()
        assertEquals(listOf("pan"), restarted.tasks.value.tasks.map { it.title })
        restarted.poll()
        assertEquals(11L, telegram.offsets.last())
        assertEquals(1, restarted.tasks.value.tasks.size)
    }

    @Test
    fun otherChatsAndBotsAreIgnoredButSkipped() = runBlocking {
        val repository = repository()
        say("spam", chat = -999)
        say("soy un bot", bot = true)
        say("pan")
        repository.poll()
        assertEquals(listOf("pan"), repository.tasks.value.tasks.map { it.title })
        assertEquals(13L, repository.tasks.value.offset)
    }

    @Test
    fun failureKeepsTheTasksAndMarksTheStatus() = runBlocking {
        val repository = repository()
        say("pan")
        repository.poll()
        telegram.failure = IOException("sin red")
        val error = runCatching { repository.poll() }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(repository.status.value.failing)
        assertEquals(1, repository.tasks.value.tasks.size)
        assertTrue(TasksRepository.isNetwork(error!!))
    }

    @Test
    fun conflictIsRecognised() = runBlocking {
        val repository = repository()
        telegram.status = 409
        val error = runCatching { repository.poll() }.exceptionOrNull()!!
        assertTrue(TasksRepository.isConflict(error))
        assertFalse(TasksRepository.isNetwork(error))
        assertTrue(error is HttpError)
    }

    @Test
    fun completingFromTheFrameTellsTheGroup() = runBlocking {
        val repository = repository()
        say("pan")
        say("leche")
        repository.poll()
        repository.complete(repository.tasks.value.tasks.first().id)
        assertEquals(listOf("leche"), repository.tasks.value.tasks.map { it.title })
        assertEquals(listOf("👌 Hecho desde el marco: pan"), telegram.sent)
        // Sigue guardado tras reiniciar
        assertEquals(listOf("leche"), repository().tasks.value.tasks.map { it.title })
    }
}
