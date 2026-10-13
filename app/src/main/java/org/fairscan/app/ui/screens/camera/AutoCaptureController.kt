/*
 * Copyright 2026 The AutoFair-Scan authors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
package org.fairscan.app.ui.screens.camera

import org.fairscan.imageprocessing.Quad
import kotlin.math.abs

/**
 * Decide quando disparar a captura automática, pelo ciclo:
 *
 * COUNTING: o documento (contorno) precisa estar presente e sem movimento.
 *   Qualquer movimento zera o contador. Quando ele completa [quietMs] sem
 *   movimento, dispara a captura e passa para WAIT_MOTION.
 * WAIT_MOTION: depois da captura não faz nada até haver movimento (contorno
 *   ausente por [lostMs] ou imagem diferente da capturada em mais que
 *   [changeTol]). Aí volta para COUNTING.
 *
 * Movimento = contorno instável/ausente, ou diferença média entre este frame
 * e o anterior maior que [motionTol]. Lógica pura, chamada por frame analisado.
 */
class AutoCaptureController(
    var quietMs: Long = 1500,
    var motionTol: Float = 0.015f,
    var changeTol: Float = 0.04f,
    private val lostMs: Long = 400,
) {
    private enum class State { COUNTING, WAIT_MOTION }

    private var state = State.COUNTING
    private var quietSince = 0L
    private var lostSince = 0L
    private var lockedThumb: FloatArray? = null
    private var prevThumb: FloatArray? = null

    /** Diferença do último frame para o anterior (para exibir no modo debug). */
    var lastMotion = 0f
        private set

    /** Diferença do último frame para a imagem capturada (modo debug). */
    var lastChange = 0f
        private set

    /**
     * @param now relógio monotônico em ms
     * @param stableQuad quad já estabilizado pelo QuadStabilizer (null se instável)
     * @param rawQuad quad bruto do frame (null se não há documento)
     * @param thumb miniatura em tons de cinza (valores 0..1) do frame
     * @return true se a captura deve ser disparada agora
     */
    fun onFrame(now: Long, stableQuad: Quad?, rawQuad: Quad?, thumb: FloatArray): Boolean {
        val prev = prevThumb
        prevThumb = thumb
        val frameDiff = if (prev == null) 0f else meanDiff(thumb, prev)
        lastMotion = frameDiff

        when (state) {
            State.COUNTING -> {
                if (stableQuad == null || frameDiff > motionTol) {
                    quietSince = 0L
                    return false
                }
                if (quietSince == 0L) quietSince = now
                if (now - quietSince >= quietMs) {
                    state = State.WAIT_MOTION
                    lockedThumb = thumb
                    quietSince = 0L
                    lostSince = 0L
                    return true
                }
            }
            State.WAIT_MOTION -> {
                if (rawQuad == null) {
                    lastChange = 0f
                    if (lostSince == 0L) lostSince = now
                    if (now - lostSince >= lostMs) startCounting()
                } else {
                    lostSince = 0L
                    val locked = lockedThumb
                    lastChange = if (locked == null) Float.MAX_VALUE else meanDiff(thumb, locked)
                    if (lastChange > changeTol) startCounting()
                }
            }
        }
        return false
    }

    /** A captura falhou: volta a contar para tentar de novo com a página parada. */
    fun onCaptureFailed() {
        startCounting()
    }

    /** Volta ao início do ciclo (ao ligar/desligar o modo). */
    fun reset() {
        startCounting()
    }

    private fun startCounting() {
        state = State.COUNTING
        quietSince = 0L
        lostSince = 0L
        lockedThumb = null
        prevThumb = null
    }

    /** Diferença média ignorando mudança geral de brilho (exposição automática). */
    private fun meanDiff(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return Float.MAX_VALUE
        var ma = 0f
        var mb = 0f
        for (i in a.indices) { ma += a[i]; mb += b[i] }
        ma /= a.size
        mb /= b.size
        var sum = 0f
        for (i in a.indices) sum += abs((a[i] - ma) - (b[i] - mb))
        return sum / a.size
    }
}
