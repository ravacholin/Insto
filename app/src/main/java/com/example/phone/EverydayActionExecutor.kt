package com.example.phone

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone

internal class EverydayActionExecutor(private val context: Context) {
  fun execute(command: PhoneCommand): PhoneActionResult {
    val places = SavedPlacesRepository(context)
    if (command is PhoneCommand.SavePlace) {
      places.save(command.alias, command.address)
      return PhoneActionResult("Guardé la dirección de ${command.alias} para tus próximos pedidos.")
    }
    val resolved = when (command) {
      is PhoneCommand.Navigate -> {
        val alias = PhoneCommandParser.normalize(command.destination).removePrefix("mi ").removePrefix("el ")
        if (alias in listOf("casa", "trabajo")) {
          val address = places.address(alias) ?: return PhoneActionResult(
            "Todavía no tengo la dirección de $alias. Decime: guardá mi $alias en, seguido de la dirección completa.", outcome = PhoneActionOutcome.REJECTED)
          command.copy(destination = address)
        } else command
      }
      is PhoneCommand.Dial -> {
        val number = PhoneNumbers.canonical(command.target)
        if (number != null) command.copy(target = number)
        else {
          if (command.target.firstOrNull()?.let { it.isDigit() || it in "+*#(" } == true) {
            return PhoneActionResult("Indicá un número completo sin extensiones ni códigos especiales.", outcome = PhoneActionOutcome.REJECTED)
          }
          if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return PhoneActionResult("Permití el acceso a tus contactos para buscar ese nombre, o indicá el número.",
              needsContacts = true, outcome = PhoneActionOutcome.NEEDS_INPUT)
          }
          val (candidates, truncated) = findContacts(command.target)
          when (val selection = ContactSelection.resolve(command.target, candidates, truncated)) {
            is ContactResolution.Number -> command.copy(target = selection.value)
            is ContactResolution.Choose -> return PhoneActionResult("Elegí el contacto y número que querés llamar.", contactOptions = selection.options, outcome = PhoneActionOutcome.NEEDS_INPUT)
            ContactResolution.Refine -> return PhoneActionResult("Hay muchas coincidencias. Indicá un nombre más completo o el número.", outcome = PhoneActionOutcome.REJECTED)
            ContactResolution.Missing -> return PhoneActionResult("No encontré un número para ${command.target}. Podés indicar el número directamente.", outcome = PhoneActionOutcome.REJECTED)
          }
        }
      }
      else -> command
    }
    val spec = NativeIntentSpec.forCommand(resolved) ?: return PhoneActionResult("No pude preparar ese pedido del teléfono.", outcome = PhoneActionOutcome.REJECTED)
    val intent = Intent(spec.action).apply {
      setDataAndType(spec.dataUri?.let(Uri::parse), spec.mimeType)
      spec.stringExtras.forEach { (key, value) -> putExtra(key, value) }
      spec.longExtras.forEach { (key, value) -> putExtra(key, value) }
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (intent.resolveActivity(context.packageManager) == null) {
      return PhoneActionResult("No hay una aplicación compatible para completar este pedido.", outcome = PhoneActionOutcome.REJECTED)
    }
    context.startActivity(intent)
    val message = when (resolved) {
      is PhoneCommand.Dial -> "Número preparado en Teléfono. Tocá Llamar para iniciar la llamada."
      is PhoneCommand.CalendarEvent -> "Evento preparado. Revisá la fecha, duración y guardalo en Calendario."
      is PhoneCommand.Camera -> if (resolved.video) "Cámara abierta en modo video." else "Cámara abierta para sacar una foto."
      is PhoneCommand.YouTubeSearch -> "Búsqueda abierta en YouTube."
      is PhoneCommand.MusicSearch -> "Pedido de música enviado a la aplicación compatible."
      is PhoneCommand.Navigate -> "Ruta solicitada en Maps."
      else -> "Búsqueda abierta en Maps."
    }
    return PhoneActionResult(message, outcome = PhoneActionOutcome.HANDOFF)
  }

  private fun findContacts(name: String): Pair<List<ContactOption>, Boolean> {
    val uri = Uri.withAppendedPath(Phone.CONTENT_FILTER_URI, Uri.encode(name))
    return context.contentResolver.query(uri, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER), null, null, Phone.DISPLAY_NAME + " ASC")?.use { cursor ->
      val values = buildList {
        var scanned = 0
        while (scanned < 100 && cursor.moveToNext()) {
          scanned++
          val label = cursor.getString(0)
          val number = cursor.getString(1)
          if (!label.isNullOrBlank() && !number.isNullOrBlank()) add(ContactOption(label, number))
        }
      }
      values to cursor.moveToNext()
    } ?: (emptyList<ContactOption>() to false)
  }
}
