package com.example.phone

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract as Documents
import androidx.activity.result.contract.ActivityResultContracts

/** All document access starts in Android's picker; no global storage permission or guessed paths. */
class FileActionActivity : LocalActionActivity() {
  private var source:Uri?=null
  private var sourceParent:Uri?=null
  private val filePicker=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    if(!interrupted && result.resultCode==RESULT_OK) result.data?.data?.let {uri ->
      try {selected(uri)} catch(_:Exception) {report("No pude acceder al archivo elegido.")}
    } else if(!interrupted) finish()
  }
  private val sourceFolder=registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) {tree ->
    if(interrupted) return@registerForActivityResult
    if(tree==null) {finish();return@registerForActivityResult}
    try {
      val parent=Documents.buildDocumentUriUsingTree(tree,Documents.getTreeDocumentId(tree))
      val children=Documents.buildChildDocumentsUriUsingTree(tree,Documents.getTreeDocumentId(tree))
      val options=contentResolver.query(children,arrayOf(Documents.Document.COLUMN_DOCUMENT_ID,Documents.Document.COLUMN_DISPLAY_NAME,Documents.Document.COLUMN_MIME_TYPE),null,null,null)?.use {cursor ->
        buildList {
          var scanned=0
          while(scanned++<500 && size<200 && cursor.moveToNext()) {
            val name=cursor.getString(1)
            if(cursor.getString(2)!=Documents.Document.MIME_TYPE_DIR && (command.argument==null || sameName(name,command.argument!!)))
              add(name to Documents.buildDocumentUriUsingTree(tree,cursor.getString(0)))
          }
        }
      }.orEmpty()
      if(options.isEmpty()) report("No encontré el archivo entre los documentos consultados de esa carpeta. No se movió ningún documento.")
      else AlertDialog.Builder(this).setTitle("Elegí el archivo a mover").setItems(options.map {it.first}.toTypedArray()) {_,index ->
        sourceParent=parent;source=options[index].second;destination.launch(null)
      }.setNegativeButton("Cancelar") {_,_->finish()}.show()
    } catch(_:Exception) {report("No pude consultar la carpeta seleccionada.")}
  }
  private val destination=registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) {tree ->
    if(interrupted) return@registerForActivityResult
    if(tree==null) {finish();return@registerForActivityResult}
    try {
      val target=Documents.buildDocumentUriUsingTree(tree,Documents.getTreeDocumentId(tree))
      val original=checkNotNull(source)
      val info=metadata(original)
      val folder=metadata(target)
      confirm("${if(command.action==PlatformAction.FILE_MOVE) "Mover" else "Copiar"} «${info.name}» a «${folder.name}».") {
        work {
          if(command.action==PlatformAction.FILE_MOVE) {
            require(info.flags and Documents.Document.FLAG_SUPPORTS_MOVE !=0) {"El proveedor no permite mover este archivo"}
            val moved=Documents.moveDocument(contentResolver,original,checkNotNull(sourceParent),target)
            check(moved!=null)
            "Archivo movido a «${folder.name}»."
          } else {
            require(info.mime!=Documents.Document.MIME_TYPE_DIR)
            val created=checkNotNull(Documents.createDocument(contentResolver,target,info.mime,info.name))
            try {
              checkNotNull(contentResolver.openInputStream(original)).use {input ->
                checkNotNull(contentResolver.openOutputStream(created,"w")).use {output ->input.copyTo(output)}
              }
            } catch(error:Exception) {
              runCatching {Documents.deleteDocument(contentResolver,created)}
              throw error
            }
            "Archivo copiado como «${metadata(created).name}» en «${folder.name}»."
          }
        }
      }
    } catch(_:Exception) {report("No pude preparar el destino. No se movió ni copió el archivo.")}
  }
  override fun begin() {
    if(command.action==PlatformAction.FILE_MOVE) {
      AlertDialog.Builder(this).setTitle("Carpeta de origen").setMessage("Elegí la carpeta que contiene ${command.argument?.let {"«$it»"} ?: "el archivo a mover"}.")
        .setPositiveButton("Elegir") {_,_->sourceFolder.launch(null)}.setNegativeButton("Cancelar") {_,_->finish()}.show()
    } else {
      filePicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or if(command.action in setOf(PlatformAction.FILE_RENAME,PlatformAction.FILE_DELETE)) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0))
    }
  }
  private data class Info(val name:String,val mime:String,val flags:Int)
  private fun metadata(uri:Uri):Info = contentResolver.query(uri,arrayOf(Documents.Document.COLUMN_DISPLAY_NAME,Documents.Document.COLUMN_MIME_TYPE,Documents.Document.COLUMN_FLAGS),null,null,null)!!.use {
    check(it.moveToFirst());Info(it.getString(0),it.getString(1) ?: "application/octet-stream",it.getInt(2))
  }
  private fun sameName(a:String,b:String)=DocumentNamePolicy.matches(a,b)
  private fun selected(uri:Uri) {
    val info=metadata(uri)
    if(command.argument!=null && !sameName(info.name,command.argument!!)) {
      report("Elegiste «${info.name}», pero el pedido indica «${command.argument}». No ejecuté la operación sobre otro archivo.");return
    }
    when(command.action) {
      PlatformAction.FILE_SEARCH -> report("Archivo encontrado: ${info.name}.")
      PlatformAction.FILE_OPEN -> handoff(Intent(Intent.ACTION_VIEW).setDataAndType(uri,info.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
      PlatformAction.FILE_SHARE -> handoff(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(info.mime)
        .putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply {clipData=android.content.ClipData.newRawUri(info.name,uri)},"Compartir ${info.name}"))
      PlatformAction.FILE_DELETE -> {
        if(info.flags and Documents.Document.FLAG_SUPPORTS_DELETE==0) {report("El proveedor no permite eliminar este archivo.");return}
        confirm("¿Eliminar «${info.name}»? Esta operación puede ser permanente.") {work {
          check(Documents.deleteDocument(contentResolver,uri));"Archivo «${info.name}» eliminado."
        }}
      }
      PlatformAction.FILE_RENAME -> {
        if(info.flags and Documents.Document.FLAG_SUPPORTS_RENAME==0) {report("El proveedor no permite renombrar este archivo.");return}
        confirm("Renombrar «${info.name}» como «${command.content}».") {work {
          val renamed=checkNotNull(Documents.renameDocument(contentResolver,uri,command.content!!))
          "Nombre asignado: ${metadata(renamed).name}."
        }}
      }
      PlatformAction.FILE_COPY -> {source=uri;destination.launch(null)}
      else -> report("No se admitió esa operación de archivo.")
    }
  }
}
