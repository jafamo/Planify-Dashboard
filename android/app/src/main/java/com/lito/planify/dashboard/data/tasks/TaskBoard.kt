package com.lito.planify.dashboard.data.tasks

import java.text.Normalizer
import java.util.Locale

/** Una tarea pendiente. [messageId] es el mensaje del grupo que la creó: responder "hecho" a él la completa. */
data class Task(
    val id: Long,
    val title: String,
    val list: String,
    val author: String?,
    /** Epoch s, la hora del mensaje en Telegram. */
    val createdAt: Long,
    val messageId: Long?
)

/**
 * Las tareas y por dónde va la lectura del grupo. Se guardan juntas: el [offset] de getUpdates solo avanza con las
 * tareas que salen de esos mensajes, así que un corte de luz no repite ni pierde ninguno.
 */
data class TaskState(val tasks: List<Task> = emptyList(), val offset: Long = 0, val nextId: Long = 1)

/** Un mensaje de texto del grupo, ya sin lo propio de Telegram. */
data class IncomingMessage(
    val messageId: Long,
    val text: String,
    val author: String?,
    val date: Long,
    val replyToMessageId: Long? = null
)

/** Lo que tiene que hacer el bot después de un mensaje. */
sealed interface BotAction {
    /** Reacción con un emoji al mensaje (de los que Telegram permite a los bots). */
    data class React(val messageId: Long, val emoji: String) : BotAction
    data class Reply(val messageId: Long, val text: String) : BotAction
}

/**
 * Las tareas del grupo de Telegram de la familia:
 * - cualquier texto es una tarea (una por línea), en la lista de su `#etiqueta` o en [DEFAULT_LIST];
 * - "-leche", "/hecho leche" o responder "hecho" al mensaje de la tarea la completan (y desaparece);
 * - "/lista" contesta con las pendientes y "/ayuda", con cómo se usa.
 * Lógica pura: [TasksRepository] la aplica a cada mensaje y ejecuta las [BotAction].
 */
object TaskBoard {
    const val DEFAULT_LIST = "General"
    const val ADDED = "✍"
    const val DONE = "👌"

    private val DONE_WORDS = setOf("hecho", "hecha", "ok", "listo", "lista", "done", "ya", "✅", "✔", "✔️", "👍")
    private val HASHTAG = Regex("""(?<![\p{L}\p{N}_])#([\p{L}\p{N}_]+)""")

    fun handle(state: TaskState, message: IncomingMessage): Pair<TaskState, List<BotAction>> {
        val text = message.text.trim()
        if (text.isEmpty()) return state to emptyList()
        if (text.startsWith("/")) return command(state, message, text)
        if (text.startsWith("-")) return complete(state, message, text.removePrefix("-"))
        if (message.replyToMessageId != null && normalize(text) in DONE_WORDS) {
            val task = state.tasks.firstOrNull { it.messageId == message.replyToMessageId }
                ?: return state to listOf(BotAction.Reply(message.messageId, "No encuentro esa tarea: puede que ya esté hecha."))
            return state.without(task) to listOf(BotAction.React(message.messageId, DONE))
        }
        return add(state, message, text)
    }

    /** Completada desde el marco (pulsación larga): mismo estado que con "-texto". */
    fun completeById(state: TaskState, id: Long): Pair<TaskState, Task?> {
        val task = state.tasks.firstOrNull { it.id == id } ?: return state to null
        return state.without(task) to task
    }

    /** Texto de "/lista": las pendientes por lista. Sin Markdown: un "*" o "_" en una tarea rompería el mensaje. */
    fun summary(tasks: List<Task>): String {
        if (tasks.isEmpty()) return "No hay tareas pendientes 🎉"
        return lists(tasks).entries.joinToString("\n\n") { (list, items) ->
            "${list.uppercase(Locale.ROOT)}\n" + items.joinToString("\n") { "• ${it.title}" }
        }
    }

    /** Las pendientes por lista: primero General y después por orden alfabético; dentro, por antigüedad. */
    fun lists(tasks: List<Task>): Map<String, List<Task>> =
        tasks.groupBy { it.list }
            .toSortedMap(compareBy<String> { it != DEFAULT_LIST }.thenBy { normalize(it) })
            .mapValues { (_, items) -> items.sortedBy { it.createdAt } }

    private fun add(state: TaskState, message: IncomingMessage, text: String): Pair<TaskState, List<BotAction>> {
        var next = state.nextId
        val added = text.lines().mapNotNull { line ->
            val list = HASHTAG.find(line)?.groupValues?.get(1)?.let(::listName) ?: DEFAULT_LIST
            val title = HASHTAG.replace(line, "").replace(Regex("\\s+"), " ").trim().removePrefix("•").removePrefix("*").trim()
            if (title.isEmpty()) return@mapNotNull null
            Task(next++, title, list, message.author, message.date, message.messageId)
        }
        if (added.isEmpty()) return state to emptyList()
        return state.copy(tasks = state.tasks + added, nextId = next) to listOf(BotAction.React(message.messageId, ADDED))
    }

    private fun complete(state: TaskState, message: IncomingMessage, query: String): Pair<TaskState, List<BotAction>> {
        val wanted = normalize(query)
        if (wanted.isEmpty()) return state to listOf(BotAction.Reply(message.messageId, "¿Cuál? Escribe, por ejemplo, «-leche»."))
        val exact = state.tasks.filter { normalize(it.title) == wanted }
        val matches = exact.ifEmpty { state.tasks.filter { wanted in normalize(it.title) } }
        return when {
            matches.isEmpty() -> state to listOf(BotAction.Reply(message.messageId, "No encuentro «${query.trim()}» entre las pendientes."))
            // Varias iguales (la misma tarea apuntada dos veces) cuentan como una: se completan todas.
            matches.size > 1 && matches.map { normalize(it.title) }.distinct().size > 1 -> state to listOf(
                BotAction.Reply(message.messageId, "Hay varias: " + matches.joinToString(", ") { "«${it.title}»" } + ". ¿Cuál?")
            )
            else -> state.copy(tasks = state.tasks - matches.toSet()) to listOf(BotAction.React(message.messageId, DONE))
        }
    }

    private fun command(state: TaskState, message: IncomingMessage, text: String): Pair<TaskState, List<BotAction>> {
        // "/hecho@MiBot leche" → "/hecho", "leche"
        val name = text.substringBefore(' ').substringBefore('@').lowercase(Locale.ROOT)
        val argument = text.substringAfter(' ', "").trim()
        return when (name) {
            "/hecho", "/hecha", "/borrar" -> complete(state, message, argument)
            "/lista", "/tareas" -> state to listOf(BotAction.Reply(message.messageId, summary(state.tasks)))
            "/start", "/ayuda", "/help" -> state to listOf(BotAction.Reply(message.messageId, HELP))
            else -> state to emptyList()
        }
    }

    private fun TaskState.without(task: Task) = copy(tasks = tasks - task)

    /** "compra" → "Compra"; los nombres de lista no distinguen mayúsculas. */
    private fun listName(tag: String) = tag.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }

    /** Para comparar: sin mayúsculas, tildes ni espacios de más. */
    internal fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    private const val HELP = "Apunto las tareas de este grupo y las enseño en el marco.\n\n" +
        "• Escribe la tarea: «comprar leche». Una por línea si son varias.\n" +
        "• Con #lista va a esa lista: «leche #compra».\n" +
        "• Para tacharla: «-leche», «/hecho leche» o responde «hecho» a su mensaje.\n" +
        "• /lista enseña las pendientes."
}
