package com.example.phone

import android.content.Context
import android.util.AtomicFile
import java.io.File

/**
 * «Instinct puede ejecutarla», routine by routine. Off by default. Stored in [Context.getNoBackupFilesDir] with an
 * atomic write, like the other switches of this feature: a restore never brings or turns on a grant. Each grant
 * keeps the hash of the routine's steps when it was enabled ([InstinctRoutines.hash]).
 */
class InstinctRoutineGrants(context: Context) {
  private val app = context.applicationContext
  private val file = AtomicFile(File(app.noBackupFilesDir, "instinct-routines.json"))
  private val routines = ConfiguredRoutineStore(File(app.noBackupFilesDir, "configured-routines.json"))

  @Synchronized fun read(): Map<String, String> = runCatching {
    if (!file.baseFile.exists()) emptyMap() else InstinctRoutines.decodeGrants(file.openRead().bufferedReader().use { it.readText() })
  }.getOrDefault(emptyMap())

  fun configured(): List<ConfiguredRoutine> = runCatching { routines.read() }.getOrDefault(emptyList())

  /** What the validator and the runner consult: read fresh every time. */
  fun access(): InstinctRoutineAccess = InstinctRoutineAccess(read()) { routines.read() }

  /** Enables [routine] with its current steps, only when it is eligible; disabling always works. */
  @Synchronized fun set(routine: ConfiguredRoutine, enabled: Boolean): Boolean {
    val key = InstinctRoutines.key(routine.name)
    val grants = read().toMutableMap()
    if (enabled) {
      if (InstinctRoutines.blocker(routine) != null) return false
      grants[key] = InstinctRoutines.hash(routine)
    } else grants.remove(key)
    val stream = file.startWrite()
    try { stream.write(InstinctRoutines.encodeGrants(grants).toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
    catch (t: Exception) { file.failWrite(stream); throw t }
    return true
  }
}
