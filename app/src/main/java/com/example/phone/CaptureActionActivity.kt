package com.example.phone

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File

/** Captures through installed, authorized camera/recorder UIs; never operates an arbitrary screen. */
class CaptureActionActivity : LocalActionActivity() {
  private var output:File?=null
  private var outputUri:Uri?=null
  override val permissions get()=if(command.action==PlatformAction.CAPTURE_AUDIO) emptyList() else listOf(Manifest.permission.CAMERA)
  private val capture=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {result ->
    if(interrupted) return@registerForActivityResult
    if(result.resultCode!=RESULT_OK) {output?.delete();finish();return@registerForActivityResult}
    if(command.action==PlatformAction.CAPTURE_AUDIO) {
      val recorded=result.data?.data
      if(recorded==null) {report("La grabadora no devolvió una grabación disponible.");return@registerForActivityResult}
      work {
        val extension=when(contentResolver.getType(recorded)) {"audio/3gpp"->".3gp";"audio/mpeg"->".mp3";"audio/wav","audio/x-wav"->".wav";else->".m4a"}
        val target=newCapture(extension)
        try {
          checkNotNull(contentResolver.openInputStream(recorded)).use {input ->target.outputStream().use {input.copyTo(it)}}
          "Grabación recibida y guardada en Insto como ${target.name}."
        } catch(error:Exception) {target.delete();throw error}
      }
    } else {
      val file=output
      val uri=outputUri
      if(file==null || uri==null || file.length()==0L) {report("La cámara no confirmó un archivo capturado.");return@registerForActivityResult}
      val mime=if(command.action==PlatformAction.CAPTURE_PHOTO) "image/jpeg" else "video/mp4"
      AlertDialog.Builder(this).setTitle("Captura guardada en Insto").setMessage(file.name)
        .setPositiveButton("Ver") {_,_->handoff(Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))}
        .setNeutralButton("Compartir") {_,_->handoff(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM,uri)
          .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply {clipData=ClipData.newRawUri(file.name,uri)},"Compartir captura"))}
        .setNegativeButton("Cerrar") {_,_->finish()}.show()
    }
  }
  override fun begin() {
    val intent=if(command.action==PlatformAction.CAPTURE_AUDIO) Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION) else {
      val file=newCapture(if(command.action==PlatformAction.CAPTURE_PHOTO) ".jpg" else ".mp4")
      val uri=FileProvider.getUriForFile(this,"$packageName.fileprovider",file)
      output=file;outputUri=uri
      Intent(if(command.action==PlatformAction.CAPTURE_PHOTO) MediaStore.ACTION_IMAGE_CAPTURE else MediaStore.ACTION_VIDEO_CAPTURE)
        .putExtra(MediaStore.EXTRA_OUTPUT,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        .apply {clipData=ClipData.newRawUri("Captura de Insto",uri)}
    }
    if(intent.resolveActivity(packageManager)==null) {output?.delete();report("No hay una cámara o grabadora compatible instalada.")} else capture.launch(intent)
  }
  private fun newCapture(extension:String):File = File.createTempFile("Insto-",extension,File(filesDir,"captures").apply {mkdirs()})
}
