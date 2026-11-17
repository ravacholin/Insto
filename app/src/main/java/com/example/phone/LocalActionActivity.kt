package com.example.phone

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Native selection and permission UI for a command already recognized by Decisions. */
abstract class LocalActionActivity : ComponentActivity() {
  protected lateinit var command:PhoneCommand.Platform
  protected var interrupted=false
  private val access=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
    if(!interrupted) if(permissions.all {checkSelfPermission(it)==PackageManager.PERMISSION_GRANTED}) safeStart() else report("Sin esos permisos no se ejecutó el pedido.")
  }
  protected open val permissions:List<String> = emptyList()
  final override fun onCreate(savedInstanceState:Bundle?) {
    super.onCreate(savedInstanceState)
    try {
      command=PhoneCommandCodec.decode(JSONObject(intent.getStringExtra(EXTRA_COMMAND)!!)) as PhoneCommand.Platform
      if(savedInstanceState!=null) {interrupted=true;report("El pedido se interrumpió. Revisá la aplicación antes de volver a intentarlo; no se repetirá automáticamente.");return}
      val missing=permissions.filter {checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED}
      if(missing.isEmpty()) safeStart() else access.launch(missing.toTypedArray())
    } catch(_:Exception) {report("No se pudo preparar ese pedido local.")}
  }
  private fun safeStart() {try {begin()} catch(_:Exception) {report("Android no permitió completar ese pedido. No se ejecutó otra acción.")}}
  protected abstract fun begin()
  protected fun report(message:String) {
    if(isFinishing) return
    AlertDialog.Builder(this).setTitle("Insto").setMessage(message).setPositiveButton("Cerrar") {_,_->finish()}.setOnCancelListener {finish()}.show()
  }
  protected fun confirm(message:String,block:()->Unit) {
    AlertDialog.Builder(this).setTitle("Revisá el pedido").setMessage(message)
      .setNegativeButton("Cancelar") {_,_->finish()}.setPositiveButton("Confirmar") {_,_->
        try {block()} catch(_:Exception) {report("No se pudo completar el pedido. Revisá el resultado antes de reintentar.")}
      }.setOnCancelListener {finish()}.show()
  }
  protected fun work(block:suspend ()->String) {
    lifecycleScope.launch {
      val message=try {withContext(Dispatchers.IO) {block()}} catch(_:Exception) {"No se pudo completar el pedido. Revisá el resultado antes de reintentar; no se repetirá automáticamente."}
      report(message)
    }
  }
  protected fun handoff(intent:Intent) {
    if(intent.resolveActivity(packageManager)==null) report("No hay una aplicación compatible para completar el pedido.")
    else {startActivity(intent);finish()}
  }
  companion object {const val EXTRA_COMMAND="insto.local.command"}
}
