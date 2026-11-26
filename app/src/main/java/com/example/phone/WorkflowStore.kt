package com.example.phone

import android.content.Context
import android.util.AtomicFile
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** [name] separates the panel's workflow from a routine Instinct runs in the background. */
class WorkflowStore(context: Context, name: String = "phone-workflow.json") {
 private val file = AtomicFile(File(context.noBackupFilesDir, name))
 @Synchronized fun read(): WorkflowSnapshot? {
  if (!file.baseFile.exists()) return null
  return try {
   val o=JSONObject(file.openRead().bufferedReader().use { it.readText() })
   val list=o.getJSONArray("steps"); require(list.length() in 1..10)
   val steps=(0 until list.length()).map { i -> val step=list.getJSONObject(i); WorkflowStep(PhoneCommandCodec.decode(step.getJSONObject("command")),StepStatus.valueOf(step.getString("status")),step.optString("message"),
     step.optInt("dispatchAttempt").also { require(it >= 0) }) }
   val cursor=o.getInt("cursor"); require(cursor in 0..steps.size)
   WorkflowSnapshot(o.getString("id"),steps,cursor,o.getBoolean("confirmed"),o.getBoolean("cancelled"))
  } catch (_: Exception) { throw IllegalStateException("No se pudo recuperar el pedido anterior. No se ejecutó ningún paso.") }
 }
 @Synchronized fun write(s: WorkflowSnapshot?) {
  if(s==null) {file.delete();return}
  val o=JSONObject().put("id",s.id).put("cursor",s.cursor).put("confirmed",s.confirmed).put("cancelled",s.cancelled)
   .put("steps",JSONArray(s.steps.map { JSONObject().put("command",PhoneCommandCodec.encode(it.command)).put("status",it.status.name).put("message",it.message)
     .apply { if (it.dispatchAttempt > 0) put("dispatchAttempt", it.dispatchAttempt) } }))
  val stream=file.startWrite()
  try {stream.write(o.toString().toByteArray(Charsets.UTF_8));file.finishWrite(stream)} catch(t:Exception) {file.failWrite(stream);throw t}
 }
}
