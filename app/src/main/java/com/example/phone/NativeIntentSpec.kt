package com.example.phone

import java.net.URLEncoder

/** Android's public handoff contract, independently testable without an Android runtime. */
data class NativeIntentSpec(
  val action: String,
  val dataUri: String? = null,
  val mimeType: String? = null,
  val stringExtras: Map<String, String> = emptyMap(),
  val longExtras: Map<String, Long> = emptyMap(),
  val preferredPackage: String? = null,
) {
  companion object {
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    fun forCommand(command: PhoneCommand): NativeIntentSpec? {
      val payload = when (command) {
        is PhoneCommand.Navigate -> command.destination
        is PhoneCommand.MapSearch -> command.query
        is PhoneCommand.YouTubeSearch -> command.query
        is PhoneCommand.SpotifySearch -> command.query
        is PhoneCommand.MusicSearch -> command.query
        is PhoneCommand.CalendarEvent -> command.title
        else -> null
      }
      if (payload != null && (payload.isBlank() || payload.length > 2000)) return null
      if (command is PhoneCommand.CalendarEvent && (command.beginMillis <= 0 || command.endMillis <= command.beginMillis)) return null
      val mode = if (command is PhoneCommand.Navigate) when (command.mode) {
        TravelMode.DRIVING -> "driving"
        TravelMode.WALKING -> "walking"
        TravelMode.BICYCLING -> "bicycling"
        TravelMode.TRANSIT -> "transit"
      } else null
      val spec = when (command) {
        is PhoneCommand.Navigate -> NativeIntentSpec("android.intent.action.VIEW",
          "https://www.google.com/maps/dir/?api=1&destination=${encode(command.destination)}&travelmode=${mode}&dir_action=navigate")
        is PhoneCommand.MapSearch -> NativeIntentSpec("android.intent.action.VIEW", "https://www.google.com/maps/search/?api=1&query=${encode(command.query)}")
        is PhoneCommand.YouTubeSearch -> NativeIntentSpec("android.intent.action.VIEW", "https://www.youtube.com/results?search_query=${encode(command.query)}", preferredPackage = MediaApp.YOUTUBE.packageName)
        is PhoneCommand.SpotifySearch -> NativeIntentSpec("android.intent.action.VIEW", "https://open.spotify.com/search/${encode(command.query).replace("+", "%20")}", preferredPackage = MediaApp.SPOTIFY.packageName)
        is PhoneCommand.MusicSearch -> NativeIntentSpec("android.media.action.MEDIA_PLAY_FROM_SEARCH",
          stringExtras = mapOf("query" to command.query, "android.intent.extra.focus" to "vnd.android.cursor.item/*"),
          preferredPackage = command.app?.packageName)
        is PhoneCommand.Camera -> NativeIntentSpec(if (command.video) "android.media.action.VIDEO_CAMERA" else "android.media.action.STILL_IMAGE_CAMERA")
        is PhoneCommand.Dial -> PhoneNumbers.canonical(command.target)?.let { NativeIntentSpec("android.intent.action.DIAL", "tel:$it") }
        is PhoneCommand.CalendarEvent -> NativeIntentSpec("android.intent.action.INSERT", "content://com.android.calendar/events",
          mimeType = "vnd.android.cursor.dir/event", stringExtras = mapOf("title" to command.title) + command.description?.let { mapOf("description" to it) }.orEmpty(), longExtras = mapOf("beginTime" to command.beginMillis, "endTime" to command.endMillis))
        else -> null
      }
      return spec?.takeIf { (it.dataUri?.length ?: 0) <= 2048 }
    }
  }
}

object PhoneNumbers {
  fun canonical(value: String): String? {
    if (!Regex("[+0-9\\s().-]+").matches(value)) return null
    val digits = value.replace(Regex("[\\s().-]"), "")
    return digits.takeIf { Regex("\\+?[0-9]{3,15}").matches(it) }
  }
}
