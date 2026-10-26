package com.lito.planify.dashboard.data.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskBoardTest {
    private var nextMessage = 100L

    private fun message(text: String, replyTo: Long? = null, author: String = "Ana") =
        IncomingMessage(nextMessage++, text, author, 1_790_000_000, replyTo)

    /** Aplica los mensajes en orden y devuelve el estado final y las acciones del último. */
    private fun run(vararg texts: String, from: TaskState = TaskState()): Pair<TaskState, List<BotAction>> {
        var state = from
        var actions = emptyList<BotAction>()
        texts.forEach { text -> TaskBoard.handle(state, message(text)).let { state = it.first; actions = it.second } }
        return state to actions
    }

    private fun TaskState.titles() = tasks.map { it.title }

    @Test
    fun plainTextIsATaskInTheGeneralList() {
        val (state, actions) = run("Comprar leche")
        val task = state.tasks.single()
        assertEquals("Comprar leche", task.title)
        assertEquals(TaskBoard.DEFAULT_LIST, task.list)
        assertEquals("Ana", task.author)
        assertEquals(listOf(BotAction.React(task.messageId!!, TaskBoard.ADDED)), actions)
    }

    @Test
    fun oneTaskPerLineAndHashtagChoosesTheList() {
        val (state, _) = run("leche #compra\npan #Compra\n\nllamar al fontanero")
        assertEquals(listOf("leche", "pan", "llamar al fontanero"), state.titles())
        assertEquals(listOf("Compra", "Compra", "General"), state.tasks.map { it.list })
        assertEquals(listOf(1L, 2L, 3L), state.tasks.map { it.id })
        assertEquals(4L, state.nextId)
    }

    @Test
    fun hashtagInTheMiddleAndBulletsAreCleanedUp() {
        val (state, _) = run("• revisar #casa la caldera")
        assertEquals("revisar la caldera", state.tasks.single().title)
        assertEquals("Casa", state.tasks.single().list)
    }

    @Test
    fun onlyAHashtagIsNotATask() {
        val (state, actions) = run("#compra")
        assertTrue(state.tasks.isEmpty())
        assertTrue(actions.isEmpty())
    }

    @Test
    fun dashCompletesIgnoringCaseAndAccents() {
        val (state, actions) = run("Llamar a la Abuela", "comprar pan", "-llamar a la abuéla")
        assertEquals(listOf("comprar pan"), state.titles())
        assertTrue(actions.single() is BotAction.React)
        assertEquals(TaskBoard.DONE, (actions.single() as BotAction.React).emoji)
    }

    @Test
    fun partialMatchCompletesWhenThereIsOnlyOne() {
        val (state, _) = run("comprar leche", "llamar al fontanero", "/hecho fontan")
        assertEquals(listOf("comprar leche"), state.titles())
    }

    @Test
    fun ambiguousMatchAsksWhichOne() {
        val (state, actions) = run("leche entera", "leche de avena", "-leche")
        assertEquals(2, state.tasks.size)
        val reply = actions.single() as BotAction.Reply
        assertTrue("leche entera" in reply.text && "leche de avena" in reply.text)
    }

    @Test
    fun exactMatchWinsOverPartial() {
        val (state, _) = run("leche", "leche de avena", "-leche")
        assertEquals(listOf("leche de avena"), state.titles())
    }

    @Test
    fun theSameTaskTwiceIsCompletedTogether() {
        val (state, _) = run("pan", "pan", "-pan")
        assertTrue(state.tasks.isEmpty())
    }

    @Test
    fun unknownTaskSaysSo() {
        val (state, actions) = run("pan", "-leche")
        assertEquals(listOf("pan"), state.titles())
        assertTrue((actions.single() as BotAction.Reply).text.contains("leche"))
    }

    @Test
    fun replyingDoneToTheTaskMessageCompletesIt() {
        val (added, _) = run("pan\nleche")
        val messageId = added.tasks.first().messageId
        val (state, actions) = TaskBoard.handle(added, message("Hecho", replyTo = messageId))
        // Las dos líneas venían en el mismo mensaje: responder completa la primera de ese mensaje
        assertEquals(listOf("leche"), state.titles())
        assertTrue(actions.single() is BotAction.React)
    }

    @Test
    fun replyThatIsNotDoneIsANewTask() {
        val (added, _) = run("pan")
        val (state, _) = TaskBoard.handle(added, message("y también leche", replyTo = added.tasks.single().messageId))
        assertEquals(listOf("pan", "y también leche"), state.titles())
    }

    @Test
    fun commandsWithTheBotNameWork() {
        val (state, actions) = run("pan #compra", "leche #compra", "lavar el coche", "/lista@FamiliaBot")
        assertEquals(3, state.tasks.size)
        val text = (actions.single() as BotAction.Reply).text
        assertEquals("GENERAL\n• lavar el coche\n\nCOMPRA\n• pan\n• leche", text)
    }

    @Test
    fun emptyListSaysSo() {
        val (_, actions) = run("/lista")
        assertTrue((actions.single() as BotAction.Reply).text.startsWith("No hay tareas"))
    }

    @Test
    fun unknownCommandsAreIgnored() {
        val (state, actions) = run("/foto")
        assertTrue(state.tasks.isEmpty() && actions.isEmpty())
    }

    @Test
    fun completeByIdFromTheFrame() {
        val (added, _) = run("pan", "leche")
        val (state, task) = TaskBoard.completeById(added, added.tasks.first().id)
        assertEquals("pan", task?.title)
        assertEquals(listOf("leche"), state.titles())
        assertEquals(null, TaskBoard.completeById(state, 999).second)
    }
}
