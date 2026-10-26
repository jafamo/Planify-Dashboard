package com.lito.planify.dashboard.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lito.planify.dashboard.UiState
import com.lito.planify.dashboard.data.tasks.Task
import com.lito.planify.dashboard.data.tasks.TaskBoard
import com.lito.planify.dashboard.ui.theme.Palette
import com.lito.planify.dashboard.ui.theme.Type
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Colores de las listas, por orden: General, y las demás alfabéticamente. */
private val LIST_COLORS = listOf("#6923E2", "#0EA5E9", "#16A34A", "#EA580C", "#DB2777", "#CA8A04")

/**
 * Las tareas del grupo de Telegram, una columna por lista (con desplazamiento lateral si no caben). Una pulsación
 * larga en una tarea la tacha (y el bot lo cuenta en el grupo): larga para que un roce no borre nada.
 */
@Composable
fun TasksScreen(state: UiState, onComplete: (Long) -> Unit) {
    val lists = TaskBoard.lists(state.tasks.orEmpty()).entries.toList()
    ScreenPadding {
        if (lists.isEmpty()) {
            EmptyState(
                Icons.Outlined.TaskAlt, Palette.Online, "Todo hecho",
                "Escribe en el grupo de Telegram para apuntar una tarea", Modifier.fillMaxSize(), large = true
            )
            return@ScreenPadding
        }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            val width = if (lists.size <= 4) null else 480
            Row(
                Modifier.weight(1f).fillMaxWidth().then(if (width != null) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
                horizontalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                lists.forEachIndexed { index, (list, tasks) ->
                    Panel((if (width != null) Modifier.width(width.dp) else Modifier.weight(1f)).fillMaxHeight()) {
                        CardHeader(list, Icons.Outlined.Checklist, parseColor(LIST_COLORS[index % LIST_COLORS.size]), count = tasks.size)
                        Column(Modifier.padding(top = 8.dp).fadingScroll(rememberScrollState())) {
                            tasks.forEach { task ->
                                TaskRow(task, state) { onComplete(task.id) }
                                Hairline()
                            }
                        }
                    }
                }
            }
            Text("Mantén pulsada una tarea para tacharla", style = Type.Small.copy(color = Palette.InkMuted))
        }
    }
}

@Composable
private fun TaskRow(task: Task, state: UiState, onComplete: () -> Unit) {
    // Sin casilla: se tacha con una pulsación larga
    Column(
        Modifier
            .fillMaxWidth()
            .pointerInput(task.id) { detectTapGestures(onLongPress = { onComplete() }) }
            .padding(vertical = 14.dp)
    ) {
        Text(task.title, style = Type.Body, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val detail = listOfNotNull(task.author, if (state.clockValid) age(task, state) else null).joinToString(" · ")
        if (detail.isNotEmpty()) Text(detail, style = Type.Tiny.copy(color = Palette.InkMuted), maxLines = 1)
    }
}

/** "hoy", "ayer" o "hace 5 días". */
private fun age(task: Task, state: UiState): String {
    val created = Instant.ofEpochSecond(task.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()
    return when (val days = ChronoUnit.DAYS.between(created, state.now.toLocalDate())) {
        0L -> "hoy"
        1L -> "ayer"
        else -> "hace $days días"
    }
}
