package com.example.phone

import android.Manifest
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.provider.CalendarContract as CalendarData
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import org.json.JSONObject

class CalendarActionActivity : LocalActionActivity() {
  override val permissions get()=listOf(Manifest.permission.READ_CALENDAR) +
    if(command.action==PlatformAction.CALENDAR_DELETE) listOf(Manifest.permission.WRITE_CALENDAR) else emptyList()
  private data class Event(val id:Long,val title:String,val begin:Long,val end:Long,val recurring:Boolean)
  override fun begin() {
    if(command.action==PlatformAction.CALENDAR_QUERY) {
      val day=SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).apply {isLenient=false}.parse(command.argument!!)!!
      val end=Calendar.getInstance().apply {time=day;add(Calendar.DAY_OF_MONTH,1)}.timeInMillis
      val uri=CalendarData.Instances.CONTENT_URI.buildUpon().apply {ContentUris.appendId(this,day.time);ContentUris.appendId(this,end)}.build()
      work {
        val events=contentResolver.query(uri,arrayOf(CalendarData.Instances.TITLE,CalendarData.Instances.BEGIN,CalendarData.Instances.END),null,null,CalendarData.Instances.BEGIN+" ASC")?.use {cursor ->
          buildList {while(size<100 && cursor.moveToNext()) add("${format(cursor.getLong(1))} · ${cursor.getString(0)}")}
        }.orEmpty()
        if(events.isEmpty()) "No hay eventos disponibles para ${command.argument}." else events.joinToString("\n")
      }
      return
    }
    val title=command.argument!!
    val matches=contentResolver.query(CalendarData.Events.CONTENT_URI,arrayOf(CalendarData.Events._ID,CalendarData.Events.TITLE,CalendarData.Events.DTSTART,CalendarData.Events.DTEND,CalendarData.Events.RRULE),
      "${CalendarData.Events.TITLE} = ? AND ${CalendarData.Events.DELETED} = 0",arrayOf(title),CalendarData.Events.DTSTART+" DESC")?.use {cursor ->
      buildList {while(size<100 && cursor.moveToNext()) add(Event(cursor.getLong(0),cursor.getString(1),cursor.getLong(2),cursor.getLong(3),!cursor.getString(4).isNullOrBlank()))}
    }.orEmpty()
    if(matches.isEmpty()) {report("No encontré un evento con el título exacto «$title». No cambié tu agenda.");return}
    AlertDialog.Builder(this).setTitle("Elegí el evento exacto").setItems(matches.map {"${it.title} · ${format(it.begin)}${if(it.recurring) " · recurrente" else ""}"}.toTypedArray()) {_,index ->
      try {selected(matches[index])} catch(_:Exception) {report("No pude aplicar esos cambios al evento elegido. Revisá la fecha y reintentá el pedido completo.")}
    }.setNegativeButton("Cancelar") {_,_->finish()}.show()
  }
  private fun selected(event:Event) {
    val uri=ContentUris.withAppendedId(CalendarData.Events.CONTENT_URI,event.id)
    if(command.action==PlatformAction.CALENDAR_DELETE && !event.recurring) {
      confirm("¿Eliminar el evento «${event.title}» del ${format(event.begin)}?") {work {
        check(contentResolver.delete(uri,null,null)==1);"Evento eliminado."
      }}
    } else {
      val edit=Intent(Intent.ACTION_EDIT,uri)
      command.content?.let {raw ->
        val changes=JSONObject(raw)
        val begin=changes.getLong("beginMillis")
        require(begin>System.currentTimeMillis())
        val duration=if(changes.has("durationSeconds")) changes.getLong("durationSeconds")*1000 else event.end-event.begin
        require(duration>0)
        edit.putExtra(CalendarData.EXTRA_EVENT_BEGIN_TIME,begin).putExtra(CalendarData.EXTRA_EVENT_END_TIME,Math.addExact(begin,duration))
      }
      if(command.action==PlatformAction.CALENDAR_DELETE) confirm("Es un evento recurrente. Abrí Calendario para elegir qué ocurrencia o serie cancelar; Insto no eliminará la serie automáticamente.") {handoff(edit)}
      else handoff(edit)
    }
  }
  private fun format(time:Long)=SimpleDateFormat("dd/MM/yyyy HH:mm",Locale.getDefault()).format(java.util.Date(time))
}
