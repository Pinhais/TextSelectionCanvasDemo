package com.example.textselectioncanvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.drawText
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.roundToInt

// Referência mutável a coordenadas (evita recomposição ao atualizar).
private class LayoutCoordsRef {
    var value: LayoutCoordinates? = null
}

/** Centro do círculo da gotinha (sempre abaixo da linha). */
private fun handleCircleCenter(x: Float, rect: Rect, gap: Float, radius: Float) =
    Offset(x, rect.bottom + gap + radius)

/** Foco da lupa: x preso aos limites da linha, y no centro da linha do offset. */
private fun loupeFocusFor(layout: TextLayoutResult, offset: Int, x: Float) =
    run {
        val line = layout.getLineForOffset(offset)
        val a = layout.getLineLeft(line)
        val b = layout.getLineRight(line)
        Offset(
            x.coerceIn(minOf(a, b), maxOf(a, b)),
            (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
        )
    }

/**
 * Cartão de texto editável sobre um canvas com zoom — demonstração da técnica de
 * seleção de texto customizada (6 pilares, ver README):
 *
 *  1. Toolbar nativo bloqueado (LocalTextToolbar vazio).
 *  2. Alças nativas transparentes; gotas ciano desenhadas por nós.
 *  3. Destaque da seleção via drawBehind/getPathForRange (aqui, drawPath no Canvas do overlay).
 *  4. Tap 1/2/3 com consumo de eventos (cursor / palavra / tudo).
 *  5. Hit-box honesta (reserva inferior durante edição p/ gotinhas caberem).
 *  6. Edição com seleção customizada: seleção interna recolhida NO FIM + composition=null,
 *     substituição por diff (replaceCustomSelectionOnEdit), Backspace/Delete via onPreviewKeyEvent.
 *
 * [scale] = escala atual do mapa/canvas ao redor (usada p/ slops e zoom da lupa).
 */
@Composable
fun SelectionCard(
    id: String,
    text: String,
    onTextChange: (String) -> Unit,
    scale: Float,
    modifier: Modifier = Modifier,
    isEditing: Boolean,
    onEditingChange: (Boolean) -> Unit,
) {
    var textFieldValue by remember(id) { mutableStateOf(TextFieldValue(text)) }
    var customSelection by remember(id) { mutableStateOf<TextRange?>(null) }
    var showCursorHandle by remember(id) { mutableStateOf(false) }
    var textLayoutResult by remember(id) { mutableStateOf<TextLayoutResult?>(null) }
    var loupeFocus by remember(id) { mutableStateOf<Offset?>(null) }
    val focusRequester = remember(id) { FocusRequester() }
    val currentScale by rememberUpdatedState(scale)

    LaunchedEffect(text) {
        if (text != textFieldValue.text) {
            textFieldValue = textFieldValue.copy(text = text)
            val cs = customSelection
            if (cs != null && cs.max > text.length) customSelection = null
        }
    }

    LaunchedEffect(isEditing) {
        if (isEditing) {
            // Ao entrar em edição: seleciona tudo (visual customizado), cursor no fim,
            // seleção interna recolhida (pilar 6).
            if (text.isNotEmpty()) {
                customSelection = TextRange(0, text.length)
                textFieldValue = textFieldValue.copy(
                    selection = TextRange(text.length),
                    composition = null
                )
            }
            focusRequester.requestFocus()
        } else {
            customSelection = null
            showCursorHandle = false
            loupeFocus = null
            if (textFieldValue.text != text) onTextChange(textFieldValue.text)
        }
    }

    val backgroundColor = Color(0xFFFFF3C4)
    val contentColor = Color(0xFF263238)
    val borderColor = if (isEditing) Color(0xFF007AFF) else Color(0x33000000)

    Box(
        modifier = modifier
            .background(backgroundColor, RoundedCornerShape(8.dp))
            .border(2.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(enabled = !isEditing) { onEditingChange(true) }
            .padding(horizontal = 6.dp, vertical = 5.dp)
            .widthIn(min = 125.dp, max = 220.dp)
            .defaultMinSize(minHeight = 38.dp)
    ) {
        Box(
            modifier = Modifier
                .wrapContentWidth()
                .align(Alignment.Center)
                .padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = if (isEditing) 32.dp else 8.dp)
        ) {
            if (isEditing) {
                // PILAR 1: toolbar nativo vazio — mata o popup de copy/paste da One UI.
                val noMenuTextToolbar = remember {
                    object : TextToolbar {
                        override val status: TextToolbarStatus = TextToolbarStatus.Hidden
                        override fun hide() {}
                        override fun showMenu(
                            rect: ComposeRect,
                            onCopyRequested: (() -> Unit)?,
                            onPasteRequested: (() -> Unit)?,
                            onCutRequested: (() -> Unit)?,
                            onSelectAllRequested: (() -> Unit)?
                        ) {}
                    }
                }
                // PILAR 2: alças nativas invisíveis (o destaque fica translúcido).
                val customSelectionColors = remember {
                    TextSelectionColors(
                        handleColor = Color.Transparent,
                        backgroundColor = Color(0x66007AFF)
                    )
                }
                val handleRadiusPx = with(LocalDensity.current) { 10.dp.toPx() }
                val handleGapPx = with(LocalDensity.current) { 6.dp.toPx() }
                val keyboardController = LocalSoftwareKeyboardController.current
                val textAreaCoords = remember { LayoutCoordsRef() }

                androidx.compose.runtime.CompositionLocalProvider(
                    LocalTextToolbar provides noMenuTextToolbar,
                    LocalTextSelectionColors provides customSelectionColors
                ) {
                    BasicTextField(
                        value = textFieldValue,
                        onValueChange = { newValue ->
                            val textChanged = newValue.text != textFieldValue.text
                            val activeSel = customSelection
                            if (!textChanged && !newValue.selection.collapsed) {
                                // Seleção nativa (long press do sistema): converte pra customizada;
                                // interna recolhida NO FIM (pilar 6) p/ Backspace disparar onValueChange.
                                customSelection = newValue.selection
                                textFieldValue = newValue.copy(
                                    selection = TextRange(newValue.selection.max),
                                    composition = null
                                )
                                showCursorHandle = false
                            } else if (textChanged && activeSel != null && !activeSel.collapsed) {
                                // Digitação com seleção customizada: substitui o trecho (pilar 6).
                                textFieldValue = replaceCustomSelectionOnEdit(
                                    current = textFieldValue,
                                    incoming = newValue,
                                    customSelection = activeSel
                                )
                                customSelection = null
                                showCursorHandle = false
                            } else {
                                textFieldValue = newValue
                                if (newValue.selection.collapsed) customSelection = null
                                if (textChanged) showCursorHandle = false
                            }
                        },
                        onTextLayout = { textLayoutResult = it },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = contentColor,
                            textAlign = TextAlign.Center
                        ),
                        cursorBrush = SolidColor(contentColor),
                        modifier = Modifier
                            .wrapContentWidth()
                            .padding(horizontal = 4.dp)
                            .focusRequester(focusRequester)
                            .onPreviewKeyEvent { keyEvent ->
                                // PILAR 6: Backspace/Delete físico apaga o trecho selecionado.
                                val activeSel = customSelection
                                if (activeSel != null && !activeSel.collapsed &&
                                    keyEvent.type == KeyEventType.KeyDown &&
                                    (keyEvent.key == Key.Backspace || keyEvent.key == Key.Delete)
                                ) {
                                    val t = textFieldValue.text
                                    val sMin = activeSel.min.coerceIn(0, t.length)
                                    val sMax = activeSel.max.coerceIn(0, t.length)
                                    textFieldValue = TextFieldValue(
                                        text = t.removeRange(sMin, sMax),
                                        selection = TextRange(sMin)
                                    )
                                    customSelection = null
                                    showCursorHandle = false
                                    true
                                } else {
                                    false
                                }
                            }
                            .onFocusChanged { state ->
                                if (!state.isFocused && textFieldValue.text != text) {
                                    onTextChange(textFieldValue.text)
                                }
                            },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onDone = { onEditingChange(false) }),
                        singleLine = false,
                        maxLines = 10,
                        decorationBox = { innerTextField ->
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.wrapContentWidth()
                            ) {
                                innerTextField()

                                // PILAR 4: overlay de gestos (taps 1/2/3 + arrasto do cursor).
                                Canvas(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .onGloballyPositioned { textAreaCoords.value = it }
                                        .pointerInput(id) {
                                            var lastTapUpTime = 0L
                                            var lastTapPos = Offset.Unspecified
                                            var tapCount = 0

                                            fun clampOffset(o: Int) = o.coerceIn(0, textFieldValue.text.length)

                                            awaitEachGesture {
                                                val down = awaitFirstDown(requireUnconsumed = false)
                                                val layout = textLayoutResult ?: return@awaitEachGesture
                                                val textLen = textFieldValue.text.length
                                                // Slop/timeout sensíveis ao zoom (nó encolhido no zoom out).
                                                val effectiveScale = currentScale.coerceAtLeast(0.4f)
                                                val multiTapSlop = 48.dp.toPx() / effectiveScale
                                                val dragSlop = maxOf(
                                                    viewConfiguration.touchSlop / effectiveScale,
                                                    12.dp.toPx() / effectiveScale
                                                )
                                                val multiTapTimeoutMs =
                                                    maxOf(viewConfiguration.doubleTapTimeoutMillis, 420L)

                                                val isTapContinuation = tapCount in 1..2 &&
                                                    down.uptimeMillis - lastTapUpTime <= multiTapTimeoutMs &&
                                                    lastTapPos.isSpecified &&
                                                    (down.position - lastTapPos).getDistance() <= multiTapSlop

                                                down.consume()
                                                tapCount = if (isTapContinuation) tapCount + 1 else 1
                                                focusRequester.requestFocus()
                                                keyboardController?.show()
                                                val tapOffset = clampOffset(layout.getOffsetForPosition(down.position))
                                                when (tapCount) {
                                                    1 -> { // cursor
                                                        customSelection = null
                                                        textFieldValue = textFieldValue.copy(
                                                            selection = TextRange(tapOffset),
                                                            composition = null
                                                        )
                                                        showCursorHandle = true
                                                    }
                                                    2 -> { // palavra
                                                        val word = layout.getWordBoundary(tapOffset)
                                                        if (!word.collapsed) {
                                                            customSelection = TextRange(word.min, word.max)
                                                            textFieldValue = textFieldValue.copy(
                                                                selection = TextRange(word.max),
                                                                composition = null
                                                            )
                                                            showCursorHandle = false
                                                        }
                                                    }
                                                    3 -> { // tudo
                                                        if (textLen > 0) {
                                                            customSelection = TextRange(0, textLen)
                                                            textFieldValue = textFieldValue.copy(
                                                                selection = TextRange(textLen),
                                                                composition = null
                                                            )
                                                            showCursorHandle = false
                                                        }
                                                    }
                                                }

                                                var upTime = down.uptimeMillis
                                                var released = false
                                                var dragCursor = false
                                                if (tapCount == 1) {
                                                    // Segurar/arrastar além do slop => arrasta o cursor com lupa.
                                                    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                                        while (true) {
                                                            val event = awaitPointerEvent()
                                                            val change = event.changes.firstOrNull { it.id == down.id }
                                                            if (change == null || !change.pressed) {
                                                                change?.let { it.consume(); upTime = it.uptimeMillis }
                                                                released = true
                                                                break
                                                            }
                                                            change.consume()
                                                            if ((change.position - down.position).getDistance() > dragSlop) break
                                                        }
                                                    }
                                                    dragCursor = !released
                                                }

                                                if (dragCursor) {
                                                    tapCount = 0
                                                    try {
                                                        loupeFocus = loupeFocusFor(layout, tapOffset, down.position.x)
                                                        while (true) {
                                                            val event = awaitPointerEvent()
                                                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                                            if (!change.pressed) { change.consume(); break }
                                                            if (change.positionChange() != Offset.Zero) {
                                                                val o = clampOffset(layout.getOffsetForPosition(change.position))
                                                                textFieldValue = textFieldValue.copy(selection = TextRange(o))
                                                                loupeFocus = loupeFocusFor(layout, o, change.position.x)
                                                            }
                                                            change.consume()
                                                        }
                                                    } finally {
                                                        loupeFocus = null
                                                    }
                                                } else if (!released) {
                                                    // Taps 2/3: espera soltar, consumindo tudo.
                                                    while (true) {
                                                        val event = awaitPointerEvent()
                                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                                        change.consume()
                                                        if (!change.pressed) { upTime = change.uptimeMillis; break }
                                                    }
                                                }
                                                lastTapUpTime = upTime
                                                lastTapPos = down.position
                                            }
                                        }
                                ) {
                                    val layout = textLayoutResult ?: return@Canvas
                                    val textLen = textFieldValue.text.length
                                    val handleColor = Color(0xFF007AFF)
                                    val lineStroke = 2.dp.toPx()

                                    // PILAR 3 + gotinha sempre abaixo da linha (pilar 5 garante espaço).
                                    fun DrawScope.drawHandle(x: Float, rect: Rect) {
                                        drawLine(
                                            color = handleColor,
                                            start = Offset(x, rect.bottom),
                                            end = Offset(x, rect.bottom + handleGapPx),
                                            strokeWidth = lineStroke
                                        )
                                        drawCircle(
                                            color = handleColor,
                                            radius = handleRadiusPx,
                                            center = handleCircleCenter(x, rect, handleGapPx, handleRadiusPx)
                                        )
                                    }

                                    val sel = customSelection
                                    if (sel != null && !sel.collapsed) {
                                        drawPath(
                                            path = layout.getPathForRange(sel.min.coerceIn(0, textLen), sel.max.coerceIn(0, textLen)),
                                            color = Color(0x66007AFF)
                                        )
                                        val startRect = layout.getCursorRect(sel.min.coerceIn(0, textLen))
                                        val endRect = layout.getCursorRect(sel.max.coerceIn(0, textLen))
                                        drawHandle(startRect.left, startRect)
                                        drawHandle(endRect.right, endRect)
                                    } else if (showCursorHandle && textFieldValue.selection.collapsed) {
                                        val cRect = layout.getCursorRect(textFieldValue.selection.start.coerceIn(0, textLen))
                                        drawHandle(cRect.center.x, cRect)
                                    }
                                }

                                // Alvos de toque das gotinhas (48dp, layout 0x0 externo).
                                val handleLayout = textLayoutResult
                                if (handleLayout != null) {
                                    val textLen = textFieldValue.text.length
                                    val sel = customSelection
                                    val touchSize = 48.dp
                                    val onHandleDrag: (Int, Offset) -> Unit = { newOffset, p ->
                                        loupeFocus = loupeFocusFor(handleLayout, newOffset, p.x)
                                    }
                                    if (sel != null && !sel.collapsed) {
                                        val sMin = sel.min.coerceIn(0, textLen)
                                        val sMax = sel.max.coerceIn(0, textLen)
                                        val startRect = handleLayout.getCursorRect(sMin)
                                        val endRect = handleLayout.getCursorRect(sMax)
                                        HandleTouchTarget(
                                            key = "${id}_start",
                                            center = handleCircleCenter(startRect.left, startRect, handleGapPx, handleRadiusPx),
                                            minTopY = startRect.bottom,
                                            refPoint = Offset(startRect.left, startRect.center.y),
                                            anchor = sMax,
                                            touchSize = touchSize,
                                            layout = handleLayout,
                                            textLength = textLen,
                                            modifier = Modifier.align(Alignment.TopStart),
                                            onStart = {
                                                focusRequester.requestFocus()
                                                onHandleDrag(sMin, Offset(startRect.left, startRect.center.y))
                                            },
                                            onMove = { anchorOffset, newOffset, p ->
                                                val lo = minOf(anchorOffset, newOffset)
                                                val hi = maxOf(anchorOffset, newOffset)
                                                customSelection = TextRange(lo, hi)
                                                textFieldValue = textFieldValue.copy(
                                                    selection = TextRange(hi),
                                                    composition = null
                                                )
                                                onHandleDrag(newOffset, p)
                                            },
                                            onEnd = { loupeFocus = null }
                                        )
                                        HandleTouchTarget(
                                            key = "${id}_end",
                                            center = handleCircleCenter(endRect.right, endRect, handleGapPx, handleRadiusPx),
                                            minTopY = endRect.bottom,
                                            refPoint = Offset(endRect.right, endRect.center.y),
                                            anchor = sMin,
                                            touchSize = touchSize,
                                            layout = handleLayout,
                                            textLength = textLen,
                                            modifier = Modifier.align(Alignment.TopStart),
                                            onStart = {
                                                focusRequester.requestFocus()
                                                onHandleDrag(sMax, Offset(endRect.right, endRect.center.y))
                                            },
                                            onMove = { anchorOffset, newOffset, p ->
                                                val lo = minOf(anchorOffset, newOffset)
                                                val hi = maxOf(anchorOffset, newOffset)
                                                customSelection = TextRange(lo, hi)
                                                textFieldValue = textFieldValue.copy(
                                                    selection = TextRange(hi),
                                                    composition = null
                                                )
                                                onHandleDrag(newOffset, p)
                                            },
                                            onEnd = { loupeFocus = null }
                                        )
                                    } else if (showCursorHandle && textFieldValue.selection.collapsed) {
                                        val cursor = textFieldValue.selection.start.coerceIn(0, textLen)
                                        val cRect = handleLayout.getCursorRect(cursor)
                                        HandleTouchTarget(
                                            key = "${id}_cursor",
                                            center = handleCircleCenter(cRect.center.x, cRect, handleGapPx, handleRadiusPx),
                                            minTopY = cRect.bottom,
                                            refPoint = Offset(cRect.center.x, cRect.center.y),
                                            anchor = -1,
                                            touchSize = touchSize,
                                            layout = handleLayout,
                                            textLength = textLen,
                                            modifier = Modifier.align(Alignment.TopStart),
                                            onStart = {
                                                focusRequester.requestFocus()
                                                onHandleDrag(cursor, Offset(cRect.center.x, cRect.center.y))
                                            },
                                            onMove = { _, newOffset, p ->
                                                textFieldValue = textFieldValue.copy(selection = TextRange(newOffset))
                                                onHandleDrag(newOffset, p)
                                            },
                                            onEnd = { loupeFocus = null }
                                        )
                                    }
                                }

                                // LUPA: Popup (outra janela, fora do zoom do mapa).
                                val focus = loupeFocus
                                val loupeLayout = textLayoutResult
                                if (focus != null && loupeLayout != null) {
                                    val sel = customSelection
                                    TextLoupe(
                                        layout = loupeLayout,
                                        focus = focus,
                                        anchorCoords = textAreaCoords.value,
                                        mapScale = scale,
                                        textColor = contentColor,
                                        backgroundColor = backgroundColor,
                                        selection = sel?.takeIf { !it.collapsed },
                                        cursorOffset = if (sel == null || sel.collapsed)
                                            textFieldValue.selection.start.coerceIn(0, textFieldValue.text.length)
                                        else null
                                    )
                                }
                            }
                        }
                    )
                }
            } else {
                Text(
                    text = text,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                    color = contentColor
                )
            }
        }
    }
}

/**
 * Alvo de toque invisível centrado na gotinha. Congela o offset visual durante
 * o arrasto; layout 0x0 externo (não infla a decorationBox em 1 linha).
 */
@Composable
private fun HandleTouchTarget(
    key: String,
    center: Offset,
    minTopY: Float,
    refPoint: Offset,
    anchor: Int,
    touchSize: androidx.compose.ui.unit.Dp,
    layout: TextLayoutResult,
    textLength: Int,
    modifier: Modifier = Modifier,
    onStart: () -> Unit,
    onMove: (anchorOffset: Int, newOffset: Int, textPos: Offset) -> Unit,
    onEnd: () -> Unit,
) {
    val touchPx = with(LocalDensity.current) { touchSize.roundToPx() }
    var frozenCenter by remember(key) { mutableStateOf<Offset?>(null) }
    val latestCenter = rememberUpdatedState(center) // sem `by`: usado dentro de pointerInput
    val latestRef = rememberUpdatedState(refPoint)
    val latestAnchor = rememberUpdatedState(anchor)
    val latestLayout = rememberUpdatedState(layout)
    val latestTextLen = rememberUpdatedState(textLength)
    val latestOnStart = rememberUpdatedState(onStart)
    val latestOnMove = rememberUpdatedState(onMove)
    val latestOnEnd = rememberUpdatedState(onEnd)

    val displayCenter = frozenCenter ?: center
    Box(
        modifier = modifier
            .layout { measurable, _ ->
                val placeable = measurable.measure(Constraints.fixed(touchPx, touchPx))
                layout(0, 0) {
                    placeable.place(
                        (displayCenter.x - touchPx / 2f).roundToInt(),
                        maxOf(displayCenter.y - touchPx / 2f, minTopY).roundToInt()
                    )
                }
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(key) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        frozenCenter = latestCenter.value
                        val startRef = latestRef.value
                        val downPos = down.position
                        val fixedAnchor = latestAnchor.value
                        latestOnStart.value()
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                                if (change.positionChange() != Offset.Zero) {
                                    val p = startRef + (change.position - downPos)
                                    val newOffset = latestLayout.value.getOffsetForPosition(p).coerceIn(0, latestTextLen.value)
                                    if (fixedAnchor < 0 || newOffset != fixedAnchor) {
                                        latestOnMove.value(fixedAnchor, newOffset, p)
                                    }
                                }
                                change.consume()
                            }
                        } finally {
                            frozenCenter = null
                            latestOnEnd.value()
                        }
                    }
                }
        )
    }
}

/**
 * Lupa desenhada num [Popup] (outra janela: não é cortada pelo nó e não sofre o zoom
 * do mapa). Amplia o próprio [TextLayoutResult] do campo (sem bitmap).
 */
@Composable
private fun TextLoupe(
    layout: TextLayoutResult,
    focus: Offset,
    anchorCoords: LayoutCoordinates?,
    mapScale: Float,
    textColor: Color,
    backgroundColor: Color,
    selection: TextRange?,
    cursorOffset: Int?,
) {
    val coords = anchorCoords?.takeIf { it.isAttached } ?: return
    val density = LocalDensity.current
    val loupeWidth = 128.dp
    val loupeHeight = 56.dp
    val shape = RoundedCornerShape(loupeHeight / 2)

    val widthPx = with(density) { loupeWidth.toPx() }
    val heightPx = with(density) { loupeHeight.toPx() }
    val gapPx = with(density) { 14.dp.toPx() }
    val fingerPx = with(density) { 44.dp.toPx() }

    val line = layout.getLineForVerticalPosition(focus.y)
    val lineTopWin = coords.localToWindow(Offset(focus.x, layout.getLineTop(line)))
    val lineBottomWin = coords.localToWindow(Offset(focus.x, layout.getLineBottom(line)))
    var y = lineTopWin.y - gapPx - heightPx
    if (y < 0f) y = lineBottomWin.y + fingerPx
    val position = IntOffset((lineTopWin.x - widthPx / 2f).roundToInt(), y.roundToInt())

    val positionProvider = remember(position) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset = position
        }
    }

    // Zoom relativo ao texto na tela, com limites de legibilidade.
    val zoom = (mapScale * 1.25f).coerceIn(1.5f, 2.2f)

    Popup(
        popupPositionProvider = positionProvider,
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            clippingEnabled = true
        )
    ) {
        Box(
            modifier = Modifier
                .size(loupeWidth, loupeHeight)
                .shadow(6.dp, shape)
                .background(backgroundColor.copy(alpha = 1f), shape)
                .border(1.dp, Color.Gray.copy(alpha = 0.5f), shape)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // p' = centro + zoom * (p - focus)
                withTransform({
                    translate(size.width / 2f, size.height / 2f)
                    scale(zoom, zoom, pivot = Offset.Zero)
                    translate(-focus.x, -focus.y)
                }) {
                    if (selection != null && !selection.collapsed) {
                        drawPath(
                            path = layout.getPathForRange(selection.min, selection.max),
                            color = Color(0x66007AFF)
                        )
                    }
                    drawText(textLayoutResult = layout, color = textColor)
                    if (cursorOffset != null) {
                        val r = layout.getCursorRect(cursorOffset)
                        drawLine(
                            color = Color(0xFF007AFF),
                            start = Offset(r.center.x, r.top),
                            end = Offset(r.center.x, r.bottom),
                            strokeWidth = 2.dp.toPx() / zoom
                        )
                    }
                }
            }
        }
    }
}
