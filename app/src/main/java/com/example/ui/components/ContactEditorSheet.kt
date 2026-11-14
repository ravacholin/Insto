package com.example.ui.components

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.PinnedContact
import com.example.whatsapp.PhoneNumberUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactEditorSheet(
  initialContact: PinnedContact?,
  onDismiss: () -> Unit,
  onSaveContact: (name: String, phone: String) -> Unit,
) {
  val context = LocalContext.current
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  var name by remember(initialContact) { mutableStateOf(initialContact?.displayName.orEmpty()) }
  var phone by remember(initialContact) { mutableStateOf(initialContact?.phoneNumber.orEmpty()) }
  var pickerNotice by remember { mutableStateOf<String?>(null) }

  val contactPickerLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
      if (result.resultCode == Activity.RESULT_OK) {
        val uri = result.data?.data
        if (uri != null) {
          val picked = readPickedPhoneContact(context, uri)
          if (picked != null) {
            name = picked.first
            phone = picked.second
            pickerNotice = null
          } else {
            pickerNotice = "Could not read phone number from selected contact."
          }
        }
      }
    }

  val isPhoneValid = PhoneNumberUtils.isValidPhoneNumber(phone)
  val jidPreview = PhoneNumberUtils.toWhatsAppJid(phone)

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface,
  ) {
    Column(
      modifier =
        Modifier.fillMaxWidth()
          .padding(horizontal = 24.dp)
          .padding(bottom = 24.dp)
          .navigationBarsPadding(),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(
        text = "Pinned WhatsApp Contact",
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )

      Text(
        text =
          "Choose the single contact PingAssist opens on tap and sends voice messages to on long press. Include country code (e.g. +1, +44, +39).",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      OutlinedButton(
        onClick = {
          val pickIntent =
            Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
          runCatching { contactPickerLauncher.launch(pickIntent) }
            .onFailure {
              pickerNotice = "No contacts app available. Enter the number manually below."
            }
        },
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().height(52.dp).testTag("pick_from_contacts_button"),
      ) {
        Icon(Icons.Filled.Contacts, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Text("Pick from Phone Contacts")
      }

      if (pickerNotice != null) {
        Text(
          text = pickerNotice.orEmpty(),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.error,
        )
      }

      OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("Contact Name") },
        placeholder = { Text("e.g. Mom, Alex") },
        leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().testTag("contact_name_input"),
      )

      OutlinedTextField(
        value = phone,
        onValueChange = { phone = it },
        label = { Text("WhatsApp Phone Number") },
        placeholder = { Text("+1 555 019 2834") },
        leadingIcon = { Icon(Icons.Filled.Phone, contentDescription = null) },
        singleLine = true,
        isError = phone.isNotBlank() && !isPhoneValid,
        supportingText = {
          if (phone.isNotBlank() && !isPhoneValid) {
            Text("Enter 6–15 digits including country code")
          } else if (jidPreview != null) {
            Text("Target JID: $jidPreview", style = MaterialTheme.typography.labelSmall)
          }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().testTag("contact_phone_input"),
      )

      if (jidPreview != null) {
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "Chat route: wa.me/${PhoneNumberUtils.normalizeDigits(phone)}",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
          }
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        OutlinedButton(
          onClick = onDismiss,
          shape = RoundedCornerShape(16.dp),
          modifier = Modifier.weight(1f).height(50.dp).testTag("cancel_contact_button"),
        ) {
          Text("Cancel")
        }

        Button(
          onClick = { onSaveContact(name.ifBlank { phone }, phone) },
          enabled = isPhoneValid,
          shape = RoundedCornerShape(16.dp),
          modifier = Modifier.weight(1f).height(50.dp).testTag("save_contact_button"),
        ) {
          Text("Save Contact")
        }
      }
    }
  }
}

private fun readPickedPhoneContact(context: Context, uri: Uri): Pair<String, String>? {
  val projection =
    arrayOf(
      ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
      ContactsContract.CommonDataKinds.Phone.NUMBER,
    )
  return runCatching {
      context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
          val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
          val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
          val displayName = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
          val number = if (numIdx >= 0) cursor.getString(numIdx).orEmpty() else ""
          if (number.isNotBlank()) displayName.ifBlank { number } to number else null
        } else {
          null
        }
      }
    }
    .getOrNull()
}
