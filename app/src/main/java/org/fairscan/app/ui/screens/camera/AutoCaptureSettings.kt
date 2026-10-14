/*
 * Copyright 2025-2026 The FairScan authors
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

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale

/** Onde focar antes de a captura automática tirar a foto. */
enum class AutoFocusMode(val label: String) {
    PAGE_CENTER("Centro da página detectada"),
    SCREEN_CENTER("Centro da tela"),
    LAST_TAP("Último ponto tocado"),
    OFF("Desligado (só o foco automático)"),
}

/** Valores ajustáveis da captura automática. */
data class AutoCaptureConfig(
    val quietMs: Long = 1500,
    val motionTol: Float = 0.015f,
    val changeTol: Float = 0.04f,
    val beepEnabled: Boolean = true,
    val focusMode: AutoFocusMode = AutoFocusMode.PAGE_CENTER,
)

private const val QUIET_MIN = 0.3
private const val QUIET_MAX = 10.0
private const val MOTION_MIN = 0.002
private const val MOTION_MAX = 0.2
private const val CHANGE_MIN = 0.005
private const val CHANGE_MAX = 0.3

/** Guarda os valores no celular (SharedPreferences). */
class AutoCaptureSettings(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("autofair_auto_capture", Context.MODE_PRIVATE)

    fun load() = AutoCaptureConfig(
        quietMs = prefs.getLong("quietMs", 1500)
            .coerceIn((QUIET_MIN * 1000).toLong(), (QUIET_MAX * 1000).toLong()),
        motionTol = prefs.getFloat("motionTol", 0.015f)
            .coerceIn(MOTION_MIN.toFloat(), MOTION_MAX.toFloat()),
        changeTol = prefs.getFloat("changeTol", 0.04f)
            .coerceIn(CHANGE_MIN.toFloat(), CHANGE_MAX.toFloat()),
        beepEnabled = prefs.getBoolean("beepEnabled", true),
        focusMode = runCatching {
            AutoFocusMode.valueOf(prefs.getString("focusMode", null) ?: AutoFocusMode.PAGE_CENTER.name)
        }.getOrDefault(AutoFocusMode.PAGE_CENTER),
    )

    fun save(config: AutoCaptureConfig) {
        prefs.edit()
            .putLong("quietMs", config.quietMs)
            .putFloat("motionTol", config.motionTol)
            .putFloat("changeTol", config.changeTol)
            .putBoolean("beepEnabled", config.beepEnabled)
            .putString("focusMode", config.focusMode.name)
            .apply()
    }
}

/** Seção "Captura automática" da tela de Configurações. Salva ao digitar. */
@Composable
fun AutoCaptureSettingsSection() {
    val context = LocalContext.current
    val store = remember { AutoCaptureSettings(context) }
    var cfg by remember { mutableStateOf(store.load()) }
    fun update(c: AutoCaptureConfig) {
        cfg = c
        store.save(c)
    }

    Column {
        Text("Captura automática", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))

        NumberField(
            label = "Tempo de quietude (segundos)",
            initial = cfg.quietMs / 1000.0,
            min = QUIET_MIN, max = QUIET_MAX, decimals = 1,
            help = "Tempo sem movimento antes de capturar. Qualquer movimento zera a contagem.",
        ) { update(cfg.copy(quietMs = (it * 1000).toLong())) }

        NumberField(
            label = "Sensibilidade ao movimento",
            initial = cfg.motionTol.toDouble(),
            min = MOTION_MIN, max = MOTION_MAX, decimals = 3,
            help = "Menor = mais sensível. Acima disso, entre um frame e outro, conta como movimento.",
        ) { update(cfg.copy(motionTol = it.toFloat())) }

        NumberField(
            label = "Sensibilidade à troca de página",
            initial = cfg.changeTol.toDouble(),
            min = CHANGE_MIN, max = CHANGE_MAX, decimals = 3,
            help = "Menor = mais sensível. Diferença em relação à foto tirada que libera a próxima captura.",
        ) { update(cfg.copy(changeTol = it.toFloat())) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Bipe a cada captura")
            Switch(
                checked = cfg.beepEnabled,
                onCheckedChange = { update(cfg.copy(beepEnabled = it)) },
            )
        }

        Spacer(Modifier.height(16.dp))
        Text("Foco antes de capturar", style = MaterialTheme.typography.titleMedium)
        Text(
            "Pede foco antes da foto, para evitar fotos borradas. Desligado: a foto é tirada na hora.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(4.dp))
        AutoFocusMode.values().forEach { mode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = cfg.focusMode == mode,
                        onClick = { update(cfg.copy(focusMode = mode)) },
                        role = Role.RadioButton,
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = cfg.focusMode == mode, onClick = null)
                Text(mode.label, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    initial: Double,
    min: Double,
    max: Double,
    decimals: Int,
    help: String,
    onValid: (Double) -> Unit,
) {
    var text by remember { mutableStateOf(String.format(Locale.US, "%.${decimals}f", initial)) }
    val parsed = text.replace(',', '.').toDoubleOrNull()
    val valid = parsed != null && parsed in min..max
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            val p = it.replace(',', '.').toDoubleOrNull()
            if (p != null && p in min..max) onValid(p)
        },
        label = { Text(label) },
        isError = !valid,
        supportingText = { Text(if (valid) help else "Use um valor entre $min e $max") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
}
