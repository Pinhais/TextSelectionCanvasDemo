package com.example.textselectioncanvas

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Aplica uma edição vinda do IME/teclado (`incoming`) substituindo a [customSelection] ativa
 * em [current], já que a seleção interna do `BasicTextField` permanece recolhida.
 *
 * PILAR 6 da técnica: o campo nunca sabe da seleção customizada (fica recolhida no fim),
 * então quando o IME digita com seleção ativa precisamos descobrir O QUE foi inserido
 * (diff de prefixo/sufixo) e substituir o trecho selecionado manualmente.
 */
fun replaceCustomSelectionOnEdit(
    current: TextFieldValue,
    incoming: TextFieldValue,
    customSelection: TextRange
): TextFieldValue {
    val oldText = current.text
    val newText = incoming.text
    val sMin = customSelection.min.coerceIn(0, oldText.length)
    val sMax = customSelection.max.coerceIn(0, oldText.length)

    val minLen = minOf(oldText.length, newText.length)
    var prefixLen = 0
    while (prefixLen < minLen && oldText[prefixLen] == newText[prefixLen]) {
        prefixLen++
    }

    val maxSuffixLen = minLen - prefixLen
    var suffixLen = 0
    while (
        suffixLen < maxSuffixLen &&
        oldText[oldText.length - 1 - suffixLen] == newText[newText.length - 1 - suffixLen]
    ) {
        suffixLen++
    }

    val inserted = newText.substring(prefixLen, newText.length - suffixLen)
    val resultText = oldText.replaceRange(sMin, sMax, inserted)
    val resultCursor = (sMin + inserted.length).coerceIn(0, resultText.length)
    return TextFieldValue(
        text = resultText,
        selection = TextRange(resultCursor),
        composition = null
    )
}
