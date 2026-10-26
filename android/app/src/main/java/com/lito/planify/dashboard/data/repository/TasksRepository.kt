package com.lito.planify.dashboard.data.repository

import com.lito.planify.dashboard.data.HttpError
import com.lito.planify.dashboard.data.bodyOrThrow
import com.lito.planify.dashboard.data.cache.Cached
import com.lito.planify.dashboard.data.cache.DiskCache
import com.lito.planify.dashboard.data.tasks.BotAction
import com.lito.planify.dashboard.data.tasks.IncomingMessage
import com.lito.planify.dashboard.data.tasks.Task
import com.lito.planify.dashboard.data.tasks.TaskBoard
import com.lito.planify.dashboard.data.tasks.TaskState
import com.lito.planify.dashboard.data.telegram.TelegramApi
import com.lito.planify.dashboard.data.telegram.TgMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** El bot de Telegram del que salen las tareas: su token y el grupo de la familia. */
data class TelegramConfig(val token: String, val chat_id: Long)

/** Estado del bot, para Ajustes: el nombre del grupo, la última lectura buena y si falla. */
data class TasksStatus(val group: String? = null, val lastOk: Long? = null, val failing: Boolean = false)

/**
 * Las tareas del grupo de Telegram ([TaskBoard]). Las tareas viven aquí, en el marco: no hay otra copia que la del
 * disco (`tasks`), que se escribe antes de contestar en el grupo, con el `offset` de getUpdates en el mismo fichero.
 * Solo se atienden los mensajes del grupo configurado; los demás se saltan.
 */
class TasksRepository(
    private val api: TelegramApi,
    private val cache: DiskCache,
    private val config: TelegramConfig,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(cache.read<TaskState>(KEY, TaskState::class.java)?.value ?: TaskState())
    val tasks: StateFlow<TaskState> = _state

    private val _status = MutableStateFlow(TasksStatus())
    val status: StateFlow<TasksStatus> = _status

    /**
     * Una espera larga de getUpdates y lo que traiga. Vuelve cuando Telegram contesta (con mensajes o sin nada a los
     * [TelegramApi.POLL_SECONDS] s); si falla, lanza el error y el estado no cambia.
     */
    suspend fun poll() {
        val updates = try {
            api.getUpdates(url("getUpdates"), _state.value.offset).bodyOrThrow().also { check(it.ok) { it.description.orEmpty() } }.result.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _status.value = _status.value.copy(failing = true)
            throw e
        }
        val actions = mutex.withLock {
            var state = _state.value
            val actions = ArrayList<BotAction>()
            var group = _status.value.group
            updates.forEach { update ->
                state = state.copy(offset = update.update_id + 1)
                val message = update.message?.takeIf { it.chat.id == config.chat_id } ?: return@forEach
                group = message.chat.title ?: group
                incoming(message)?.let { incoming ->
                    val (next, more) = TaskBoard.handle(state, incoming)
                    state = next
                    actions += more
                }
            }
            if (updates.isNotEmpty()) save(state)
            _status.value = TasksStatus(group, now(), failing = false)
            actions
        }
        actions.forEach { perform(it) }
    }

    /** Pulsación larga en el marco: la tarea se completa y se avisa en el grupo. */
    suspend fun complete(id: Long) {
        val task = mutex.withLock {
            val (next, task) = TaskBoard.completeById(_state.value, id)
            if (task != null) save(next)
            task
        } ?: return
        send(BotAction.Reply(0, "👌 Hecho desde el marco: ${task.title}"))
    }

    private fun incoming(message: TgMessage): IncomingMessage? {
        val text = message.text ?: return null
        if (message.from?.is_bot == true) return null
        return IncomingMessage(message.message_id, text, message.from?.first_name, message.date, message.reply_to_message?.message_id)
    }

    private suspend fun save(state: TaskState) {
        // Si no se puede guardar, se sigue con lo de memoria: solo se pierde para el próximo arranque.
        runCatching { withContext(Dispatchers.IO) { cache.write(KEY, Cached(state, now()), TaskState::class.java) } }
        _state.value = state
    }

    /** Las respuestas son cortesía: si fallan (sin red, reacciones desactivadas en el grupo…), las tareas ya están. */
    private suspend fun perform(action: BotAction) = runCatching { send(action) }

    private suspend fun send(action: BotAction) {
        when (action) {
            is BotAction.React -> api.setMessageReaction(url("setMessageReaction"), config.chat_id, action.messageId, TelegramApi.reaction(action.emoji))
            is BotAction.Reply -> api.sendMessage(url("sendMessage"), config.chat_id, action.text, action.messageId.takeIf { it > 0 })
        }.let { if (!it.isSuccessful) throw HttpError(it.code()) }
    }

    private fun url(method: String) = TelegramApi.url(config.token, method)

    companion object {
        private const val KEY = "tasks"
        /** Espera tras un fallo: crece hasta [MAX_BACKOFF_MS] para no insistir sin red. */
        const val MIN_BACKOFF_MS = 15_000L
        const val MAX_BACKOFF_MS = 5 * 60_000L

        /** Un 409 es otro getUpdates con el mismo token (por ejemplo, scripts/telegram.sh desde el PC). */
        fun isConflict(e: Throwable) = e is HttpError && e.code == 409

        fun isNetwork(e: Throwable) = e is IOException && e !is HttpError
    }
}
