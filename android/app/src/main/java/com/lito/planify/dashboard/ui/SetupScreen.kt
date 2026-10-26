package com.lito.planify.dashboard.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lito.planify.dashboard.R
import com.lito.planify.dashboard.UiState
import com.lito.planify.dashboard.ui.theme.Palette
import com.lito.planify.dashboard.ui.theme.Type

/**
 * Sin calendarios configurados. Las direcciones secretas iCal son largas para teclearlas en el marco, así que se
 * añaden desde el ordenador (scripts/calendars.sh). Sin Internet, primero se espera a la red, con acceso al Wi-Fi.
 */
@Composable
fun SetupScreen(state: UiState, onWifi: () -> Unit) {
    if (!state.connected) {
        WaitingForNetwork(onWifi)
        return
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.width(1000.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Image(
                painterResource(R.drawable.planify_icon),
                contentDescription = "Planify",
                modifier = Modifier.size(144.dp).align(Alignment.CenterHorizontally).padding(bottom = 8.dp)
            )
            Text(
                "Añade tus calendarios",
                style = Type.Huge.copy(fontSize = Type.Title.fontSize * 2f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
            )
            Text(
                "En calendar.google.com, desde un ordenador: Configuración → el calendario → Integrar el calendario → " +
                    "Dirección secreta en formato iCal.",
                style = Type.Body.copy(color = Palette.InkSoft),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Apúntala en scripts/calendars.local y lanza scripts/calendars.sh con el marco conectado.",
                style = Type.Body.copy(color = Palette.InkSoft),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Arco que gira una vez por segundo. El marco tiene las escalas de animación del sistema a 0 (install.sh) y Compose
 * las respeta, así que el indicador indeterminado de Material se quedaría quieto: el giro va con el reloj de fotogramas.
 */
@Composable
private fun Spinner() {
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) withFrameMillis { angle = (it % 1000L) * 0.36f }
    }
    Canvas(Modifier.size(96.dp).graphicsLayer { rotationZ = angle }) {
        val stroke = 8.dp.toPx()
        val inset = stroke / 2
        val arc = Size(size.width - stroke, size.height - stroke)
        drawArc(Palette.Line, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
        drawArc(Palette.Ink, -90f, 110f, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

@Composable
private fun WaitingForNetwork(onWifi: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(40.dp)) {
            Spinner()
            Text("Esperando conexión a Internet…", style = Type.Title, textAlign = TextAlign.Center)
            PillButton("Ir a Ajustes", Modifier.width(520.dp), onClick = onWifi)
        }
    }
}
