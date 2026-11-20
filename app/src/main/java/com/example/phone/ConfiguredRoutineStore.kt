package com.example.phone

import java.io.File
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONObject

data class ConfiguredRoutine(val name:String,val commands:List<PhoneCommand>)

/** Explicitly imported templates only; never learns a routine from a casual request. */
class ConfiguredRoutineStore(private val file:File) {
  @Synchronized fun read():List<ConfiguredRoutine> = if(!file.exists()) emptyList() else decode(file.readText())
  @Synchronized fun save(routines:List<ConfiguredRoutine>) {
    val data=encode(routines)
    decode(data) // Validate every command before replacing the existing configuration.
    file.parentFile?.mkdirs()
    val temporary=File.createTempFile("insto-routines-",".tmp",file.absoluteFile.parentFile)
    try {
      FileOutputStream(temporary).use {it.write(data.toByteArray(Charsets.UTF_8));it.fd.sync()}
      check(temporary.renameTo(file)) {"No se pudieron guardar las rutinas"}
    } finally {temporary.delete()}
  }
  companion object {
    fun decode(text:String):List<ConfiguredRoutine> {
      require(text.length<=65536)
      val array=JSONArray(text);require(array.length()<=50)
      val values=(0 until array.length()).map { index ->
        val o=array.getJSONObject(index)
        require(o.keys().asSequence().toSet()==setOf("name","commands"))
        val name=o.get("name");require(name is String && name.isNotBlank() && name.length<=100)
        val commands=o.getJSONArray("commands");require(commands.length() in 1..10)
        ConfiguredRoutine(name,(0 until commands.length()).map {PhoneCommandCodec.decode(commands.getJSONObject(it))}.also { steps ->
          require(steps.none {it is PhoneCommand.Platform && it.action in setOf(PlatformAction.ROUTINE_RUN,PlatformAction.ROUTINE_IMPORT)}) {"No se admiten rutinas anidadas ni importaciones dentro de rutinas"}
          require(steps.none(WorkflowRunner::isWeatherQuery)) {"Las consultas al SMN no forman parte de rutinas"}
        })
      }
      require(values.map {PhoneCommandParser.normalize(it.name)}.distinct().size==values.size) {"Nombres de rutina duplicados"}
      return values
    }
    fun encode(values:List<ConfiguredRoutine>):String = JSONArray(values.map {routine ->
      JSONObject().put("name",routine.name).put("commands",JSONArray(routine.commands.map(PhoneCommandCodec::encode)))
    }).toString()
  }
}
