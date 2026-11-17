package com.example.phone

import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File

class RoutineImportActivity : LocalActionActivity() {
  private val picker=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    if(interrupted) return@registerForActivityResult
    val uri=result.data?.data
    if(result.resultCode!=RESULT_OK || uri==null) {finish();return@registerForActivityResult}
    try {
      val text=contentResolver.openInputStream(uri)!!.bufferedReader().use {it.readTextLimited(65536)}
      val routines=ConfiguredRoutineStore.decode(text)
      val preview=routines.joinToString("\n\n") {routine ->routine.name+"\n"+routine.commands.joinToString("\n",transform=PhoneCommandCodec::summary)}
      confirm("Reemplazar las rutinas configuradas por estas ${routines.size} plantillas:\n\n$preview") {
        ConfiguredRoutineStore(File(noBackupFilesDir,"configured-routines.json")).save(routines)
        report("Rutinas importadas. No se ejecutó ninguna.")
      }
    } catch(_:Exception) {report("El archivo no contiene rutinas válidas. Se conservó la configuración anterior.")}
  }
  override fun begin() {picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))}
  private fun java.io.Reader.readTextLimited(limit:Int):String {
    val out=StringBuilder();val chars=CharArray(4096)
    while(true) {val n=read(chars);if(n<0)break;require(out.length+n<=limit);out.append(chars,0,n)}
    return out.toString()
  }
}
