package com.example.textselectioncanvas

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CanvasMapDemo()
                }
            }
        }
    }
}

private data class DemoNode(
    val id: String,
    val text: String,
    val x: Float,
    val y: Float
)

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.awaitUpOrCancel():
        androidx.compose.ui.input.pointer.PointerInputChange? {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull() ?: return null
        if (!change.pressed) return change
        if (change.isConsumed) return null
    }
}

/**
 * Canvas "mapa" com zoom (pinça) e pan — os cartões vivem dentro dum graphicsLayer
 * escalado, exatamente como os nós do Global MindMap. Toque num cartão para editar;
 * toque no fundo para sair da edição (commit).
 */
@Composable
fun CanvasMapDemo() {
    var scale by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var texts by remember {
        mutableStateOf(
            listOf(
                DemoNode("n1", "Mind map node with custom text selection", 80f, 200f),
                DemoNode("n2", "Drag the handles: start and end", 80f, 480f),
                DemoNode("n3", "1 tap = cursor\n2 taps = word\n3 taps = select all", 80f, 720f),
                DemoNode("n4", "This technique ships in Global MindMap!\nDownload it on Google Play", 80f, 990f)
            )
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF2F4F7))
            // Pan + zoom (pinça) do fundo — os cartões consomem seus próprios toques.
            .pointerInput(Unit) {
                detectTransformGestures { _, panDelta, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.4f, 3f)
                    pan += panDelta
                }
            }
            // Toque no fundo encerra a edição. requireUnconsumed=true: eventos já
            // consumidos pelos cartões (texto/gotinhas) NÃO chegam aqui — só toque
            // no vazio desseleciona (mesma semântica do hit-test do app real).
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown() // requireUnconsumed = true
                    val up = awaitUpOrCancel()
                    if (up != null && (up.position - down.position).getDistance() < 20f) {
                        editingId = null
                    }
                }
            }
    ) {
        Text(
            text = "Text Selection on infinite canvas — demo\nPinch = zoom · Drag background = pan",
            fontSize = 12.sp,
            color = Color(0xFF667085),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 24.dp)
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = pan.x
                    translationY = pan.y
                }
        ) {
            texts.forEach { node ->
                val isEditing = editingId == node.id
                Box(
                    modifier = Modifier
                        .offset { IntOffset(node.x.roundToInt(), node.y.roundToInt()) }
                        .width(240.dp)
                ) {
                    SelectionCard(
                        id = node.id,
                        text = node.text,
                        onTextChange = { newText ->
                            texts = texts.map {
                                if (it.id == node.id) it.copy(text = newText) else it
                            }
                        },
                        scale = scale,
                        isEditing = isEditing,
                        onEditingChange = { editing ->
                            editingId = if (editing) node.id else null
                        }
                    )
                }
            }
        }
    }
}
