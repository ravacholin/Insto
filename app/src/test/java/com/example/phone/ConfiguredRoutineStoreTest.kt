package com.example.phone
import java.io.File
import org.junit.Assert.*
import org.junit.Test
class ConfiguredRoutineStoreTest {
 @Test fun storesOnlyExplicitValidatedTemplatesAndKeepsPreviousConfigurationOnInvalidImport() {
  val file=File.createTempFile("insto-routines",".json").apply {delete()}
  try {
   val store=ConfiguredRoutineStore(file)
   val routine=ConfiguredRoutine("Estudio",listOf(PhoneCommand.Timer(1500),PhoneCommand.Volume(VolumeOp.SET,20)))
   store.save(listOf(routine));assertEquals(listOf(routine),store.read())
   try {store.save(listOf(routine,routine.copy(name="estudio")));fail("duplicate")} catch(_:IllegalArgumentException) {}
   assertEquals(listOf(routine),store.read())
  } finally {file.delete()}
 }
 @Test fun rejectsRecursiveRoutineAndUnknownFields() {
  for(text in listOf("[{\"name\":\"X\",\"commands\":[{\"type\":\"platform\",\"action\":\"ROUTINE_RUN\",\"argument\":\"X\"}]}]", "[{\"name\":\"X\",\"commands\":[],\"extra\":true}]")) {
   try {ConfiguredRoutineStore.decode(text);fail("invalid")} catch(_:IllegalArgumentException) {}
  }
 }
}
