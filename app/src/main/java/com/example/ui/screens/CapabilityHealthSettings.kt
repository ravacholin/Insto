package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.phone.CapabilityHealthRuntime
import com.example.phone.HealthCheck
import com.example.phone.HealthEffect
import com.example.phone.HealthOverview
import com.example.phone.HealthState
import com.example.phone.healthSettings
import com.example.phone.sanitizedReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
internal fun CapabilityHealthSettings() {
  val context = LocalContext.current
  val runtime = remember(context) { CapabilityHealthRuntime.start(context) }
  val engine = runtime.engine
  val scope = rememberCoroutineScope()
  val snapshot by engine.snapshots.collectAsState()
  var job by remember { mutableStateOf<Job?>(null) }
  var consent by remember { mutableStateOf<HealthCheck?>(null) }
  var note by remember { mutableStateOf<String?>(null) }
  var filter by remember { mutableStateOf<HealthState?>(null) }
  var limit by remember(filter) { mutableIntStateOf(12) }
  var history by remember { mutableStateOf(false) }
  var showLimits by remember { mutableStateOf(false) }

  fun runCheck(work: suspend () -> String) {
    if (job != null) return
    job = scope.launch {
      try {
        note = work()
      } finally {
        job = null
      }
    }
  }

  fun openSettings(id: String) {
    runCatching { context.startActivity(healthSettings(context, id)) }.onFailure {
      note = "Android no pudo abrir los ajustes."
    }
  }

  val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
    if (uri == null) return@rememberLauncherForActivityResult
    scope.launch {
      val saved = withContext(Dispatchers.IO) {
        runCatching {
          context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
            it.write(engine.snapshots.value.sanitizedReport(System.currentTimeMillis(), engine.checks.keys))
          } != null
        }.getOrDefault(false)
      }
      note = if (saved) "Informe técnico guardado." else "No se pudo guardar el informe."
    }
  }

  LaunchedEffect(engine) { engine.load() }
  val now = System.currentTimeMillis()
  val checksById = engine.checks
  val checks = checksById.values.sortedBy { it.label }
  val rows = checks.associateWith { snapshot.diagnostics[it.id]?.at(now) ?: engine.result(it.id) }
  val overview = HealthOverview(rows.values)
  Text("Verificadas ${overview.verified} · Disponibles ${overview.available} · Avisos ${overview.issues.size} · " +
    "Pendientes o limitadas ${overview.pendingOrLimited} · Sin soporte ${overview.limits.size}")
  Button(onClick = { runCheck { engine.checkAll(); "Comprobación general terminada." } }, enabled = job == null) {
    Text("Ejecutar diagnóstico general")
  }
  OutlinedButton(onClick = { runCheck { "Recuperaciones verificadas: ${runtime.recover()}." } }, enabled = job == null) {
    Text("Intentar recuperación segura")
  }
  if (job != null) TextButton(onClick = { job?.cancel() }) { Text("Cancelar comprobación") }
  note?.let { Text(it) }

  HorizontalDivider()
  TextButton(onClick = { filter = if (filter == HealthState.UNKNOWN) null else HealthState.UNKNOWN }) {
    Text(if (filter == HealthState.UNKNOWN) "Mostrar todas las compatibles" else "Mostrar pendientes")
  }
  val groupedIssues = overview.issues.filter { it.capabilities.size > 1 }
  val groupedIds = groupedIssues.flatMap { issue -> issue.capabilities.map { it.id } }.toSet()
  if (filter == null) groupedIssues.forEach { issue ->
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Text(issue.label.orEmpty(), style = MaterialTheme.typography.titleSmall)
      Text(issue.capabilities.joinToString(" · ") { checksById.getValue(it.id).label })
      Row {
        TextButton(enabled = job == null, onClick = {
          runCheck {
            issue.capabilities.forEach { engine.check(it.id, refresh = true) }
            "Comprobación de calendario terminada."
          }
        }) { Text("Comprobar") }
        TextButton(onClick = { openSettings(issue.capabilities.first().id) }) { Text("Ajustes") }
      }
    }
  }
  val visible = rows.entries.filter { (spec, row) ->
    spec.id !in groupedIds && row.state != HealthState.UNSUPPORTED && (filter == null || row.state == filter)
  }
    .sortedBy { (_, row) -> when {
      row.state.available == false -> 0
      row.state == HealthState.LIMITED -> 1
      row.state == HealthState.VERIFIED -> 2
      else -> 3
    } }
  visible.take(limit).forEach { (spec, row) ->
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Text(spec.label, style = MaterialTheme.typography.titleSmall)
      Text("${row.state.label} · ${row.observation.code.detail}")
      if (!row.observation.code.confirmed && row.state.available == false) Text("Causa sin confirmar")
      row.observation.code.action?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
      if (row.checkedAt > 0) Text("Última comprobación ${formatHealthTime(row.checkedAt)}", style = MaterialTheme.typography.bodySmall)
      Row {
        TextButton(enabled = job == null, onClick = {
          if (spec.effect != HealthEffect.READ_ONLY) consent = spec
          else runCheck { engine.check(spec.id, refresh = true).observation.code.detail }
        }) { Text("Comprobar") }
        if (row.observation.code.action != null) TextButton(onClick = { openSettings(spec.id) }) { Text("Ajustes") }
      }
    }
  }
  if (visible.size > limit) TextButton(onClick = { limit += 12 }) { Text("Mostrar más capacidades") }

  if (overview.limits.isNotEmpty()) {
    HorizontalDivider()
    TextButton(onClick = { showLimits = !showLimits }) {
      Text(if (showLimits) "Ocultar límites del teléfono" else "Límites del teléfono (${overview.limits.size})")
    }
    if (showLimits) overview.limits.forEach { row -> Text(checksById.getValue(row.id).label) }
  }

  HorizontalDivider()
  TextButton(onClick = { history = !history }) { Text(if (history) "Ocultar incidentes" else "Historial de incidentes") }
  if (history) {
    val incidents = snapshot.reportedIncidents
    if (incidents.isEmpty()) Text("Todavía no hay incidentes registrados.")
    incidents.takeLast(12).asReversed().forEach { entry ->
      val label = engine.checks[entry.id]?.label ?: "Dispositivo"
      val result = when (entry.recovery) {
        true -> "Recuperación verificada."
        false -> "Recuperación sin verificar."
        null -> entry.code.detail
      }
      Text("${formatHealthTime(entry.at)} · $label · $result")
    }
  }
  TextButton(onClick = { export.launch("insto-salud.json") }) { Text("Exportar informe sanitizado") }

  consent?.let { spec ->
    val effect = when (spec.effect) {
      HealthEffect.RESOURCE -> "La prueba consume recursos locales o del proveedor."
      HealthEffect.TEMPORARY_STATE -> "La prueba ocupa brevemente el micrófono. El audio se descarta."
      HealthEffect.INTERACTION -> "La prueba requiere tu participación."
      HealthEffect.EXTERNAL -> "La prueba produce un efecto en un sistema externo."
      HealthEffect.READ_ONLY -> "La prueba lee el estado de la función."
    }
    AlertDialog(onDismissRequest = { consent = null }, title = { Text(spec.label) }, text = { Text(effect) },
      confirmButton = { TextButton(onClick = {
        consent = null
        runCheck { engine.check(spec.id, refresh = true, authorized = true).observation.code.detail }
      }) { Text("Autorizar prueba") } },
      dismissButton = { TextButton(onClick = { consent = null }) { Text("Cancelar") } })
  }
}

private fun formatHealthTime(at: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at))
