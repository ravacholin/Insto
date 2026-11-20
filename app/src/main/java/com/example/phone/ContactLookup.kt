package com.example.phone

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone

/** Android contacts whose name matches [name], at most 100 scanned. The flag says more remained. */
internal fun Context.findPhoneContacts(name: String): Pair<List<ContactOption>, Boolean> {
  val uri = Uri.withAppendedPath(Phone.CONTENT_FILTER_URI, Uri.encode(name))
  return contentResolver.query(uri, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER), null, null, Phone.DISPLAY_NAME + " ASC")?.use { cursor ->
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
