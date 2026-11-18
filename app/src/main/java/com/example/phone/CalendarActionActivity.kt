package com.example.phone

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.provider.CalendarContract
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CalendarActionActivity : LocalActionActivity() {
  override val permissions get() = listOf(Manifest.permission.READ_CALENDAR) +
    if (command.action == PlatformAction.CALENDAR_QUERY) emptyList() else listOf(Manifest.permission.WRITE_CALENDAR)

  override fun begin() {
    val selection = CalendarSelection.decode(command)
    val calendar = DeviceCalendar(contentResolver)
    if (command.action == PlatformAction.CALENDAR_QUERY) {
      work { calendar.agenda(selection) }
      return
    }
    lifecycleScope.launch {
      val matches = try {
        withContext(Dispatchers.IO) { calendar.find(selection) }
      } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        report("No se pudo consultar el calendario del dispositivo.")
        return@launch
      }
      when (matches.size) {
        0 -> report("No encontré eventos que coincidan con el pedido.")
        1 -> review(calendar, matches.single(), selection)
        in 2..100 -> {
          val labels = matches.map { calendar.label(it) }
          if (labels.distinct().size != labels.size) {
            report("Hay eventos indistinguibles por título, fecha y calendario. Identificá el evento en tu app de calendario antes de pedir el cambio.")
          } else {
            AlertDialog.Builder(this@CalendarActionActivity).setTitle("Elegí el evento")
              .setItems(labels.toTypedArray()) { _, index -> review(calendar, matches[index], selection) }
              .setNegativeButton("Cancelar") { _, _ -> finish() }
              .setOnCancelListener { finish() }.show()
          }
        }
        else -> report("Hay demasiadas coincidencias. Indicá el título, día y horario del evento.")
      }
    }
  }

  private fun review(calendar: DeviceCalendar, event: DeviceCalendar.Occurrence, selection: CalendarSelection) {
    if (!event.writable) {
      report("El evento está en un calendario de solo lectura.")
      return
    }
    val label = calendar.label(event)
    val deleting = command.action == PlatformAction.CALENDAR_DELETE
    val hasChange = selection.newDate != null || selection.newTime != null ||
      selection.legacyBegin != null || selection.durationSeconds != null
    if (event.recurring && (!event.series || selection.durationSeconds != null) || !deleting && !hasChange) {
      val reason = if (event.recurring) "La modificación o eliminación de una ocurrencia depende de tu app de calendario. Elegí allí el alcance y revisá el resultado."
        else "Completá los cambios en tu app de calendario."
      confirm("$label\n\n$reason ¿Abrir el evento seleccionado?") {
        handoff(Intent(Intent.ACTION_EDIT, DeviceCalendar.eventUri(event.id))
          .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.begin)
          .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end))
      }
      return
    }
    if (deleting) {
      val scope = if (event.recurring) "solo esta ocurrencia" else "este evento"
      confirm("¿Eliminar $scope?\n\n$label") {
        work {
          val deleted = if (event.recurring) calendar.changeOccurrence(event, selection, null) else calendar.delete(event)
          if (deleted) "Evento eliminado."
          else "No pude verificar la eliminación. Revisá el calendario antes de reintentar."
        }
      }
    } else {
      val change = try { calendar.change(event, selection) } catch (_: IllegalArgumentException) {
        report("El horario o la duración solicitados no se pueden aplicar a ese evento.")
        return
      }
      if (change == DeviceCalendar.Change(event.begin, event.end)) {
        report("El evento ya tiene ese horario y duración.")
        return
      }
      val scope = if (event.recurring) " (solo esta ocurrencia)" else ""
      confirm("Evento actual$scope\n$label\n\nCambio propuesto\n${calendar.label(event, change.begin, change.end)}") {
        work {
          val updated = if (event.recurring) calendar.changeOccurrence(event, selection, change) else calendar.update(event, change)
          if (updated) "Evento actualizado."
          else "No pude verificar el cambio. Revisá el calendario antes de reintentar."
        }
      }
    }
  }
}
