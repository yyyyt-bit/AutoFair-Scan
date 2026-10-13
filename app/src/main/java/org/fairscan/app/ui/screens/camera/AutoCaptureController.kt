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
 */
package org.fairscan.app.ui.screens.camera

import org.fairscan.imageprocessing.Quad
import kotlin.math.abs

/**
 * Decide quando disparar a captura automática.
 *
 * SEARCHING: espera o documento ficar estável por [stableMs] e dispara uma vez.
 * LOCKED: não dispara mais nada até detectar que a página mudou, ou seja,
 *   o documento sumiu por [lostMs] ou a imagem mudou mais que [changeTol].
 *
 * Lógica pura (sem Android), chamada uma vez por frame analisado.
 */
class AutoCaptureController(
    private val stableMs: Long = 800,
    private val lostMs: Long = 400,
    private val changeTol: Float = 0.04f,
) {
    private enum class State { SEARCHING, LOCKED }

    private var state = State.SEARCHING
    private var stableSince = 0L
    private var lostSince = 0L
