package com.example.phone

enum class StopwatchOp { START, PAUSE, RESUME, RESET, STATUS }
enum class TravelMode { DRIVING, WALKING, BICYCLING, TRANSIT }
enum class MediaOp { PLAY, PAUSE, STOP, NEXT, PREVIOUS, STATUS }
enum class MediaApp(val label: String, val packageName: String) {
  SPOTIFY("Spotify", "com.spotify.music"),
  YOUTUBE("YouTube", "com.google.android.youtube"),
}
enum class ModeKind { MEDIA_VOLUME, SILENT, DND }
enum class VolumeOp { UP, DOWN, SET }
enum class PlatformAction {
  CALL_DIRECT, MESSAGE_SEND,
  CAPTURE_PHOTO, CAPTURE_VIDEO, CAPTURE_AUDIO,
  FILE_OPEN, FILE_SEARCH, FILE_SHARE, FILE_RENAME, FILE_COPY, FILE_MOVE, FILE_DELETE,
  CALENDAR_QUERY, CALENDAR_UPDATE, CALENDAR_DELETE, ROUTINE_RUN, ROUTINE_LIST, ROUTINE_IMPORT,
  ALARM_DISMISS, ALARM_SNOOZE, TIMER_DISMISS, CALENDAR_AGENDA, DIALER_OPEN,
  CONTACT_SEARCH, CONTACT_PHONES, APP_SETTINGS, TEXT_SHARE,
  FILES_BROWSE, RECORDER_OPEN, BATTERY, BRIGHTNESS, ACCESSIBILITY_SETTINGS, DISPLAY_SETTINGS,
  SILENT_ON, SILENT_OFF, VIBRATE_ON, DND_ON, DND_OFF, NOTIFICATION_LIST, NOTIFICATION_OPEN, NOTIFICATION_DISMISS,
  /** Read-only SMN queries, answered asynchronously by the floating assistant; never by PhoneActionExecutor. */
  WEATHER_TEMPERATURE, WEATHER_RAIN
}
sealed interface PhoneCommand {
  data class Platform(val action: PlatformAction, val argument: String? = null, val percent: Int? = null, val content: String? = null) : PhoneCommand
  data class Media(val operation: MediaOp, val packageName: String? = null) : PhoneCommand
  data class TemporaryMode(val kind: ModeKind, val seconds: Int, val percent: Int = 0, val approximate: Boolean = false, val deadlineMillis: Long? = null) : PhoneCommand
  data object EndMode : PhoneCommand
  data class Navigate(val destination: String, val mode: TravelMode = TravelMode.DRIVING) : PhoneCommand
  data class MapSearch(val query: String) : PhoneCommand
  data class SavePlace(val alias: String, val address: String) : PhoneCommand
  data class Dial(val target: String) : PhoneCommand
  data class CalendarEvent(val title: String, val beginMillis: Long, val endMillis: Long, val description: String? = null) : PhoneCommand
  data class Camera(val video: Boolean = false) : PhoneCommand
  data class YouTubeSearch(val query: String) : PhoneCommand
  data class SpotifySearch(val query: String) : PhoneCommand
  data class MusicSearch(val query: String, val app: MediaApp? = null) : PhoneCommand
  data class Timer(val seconds: Int, val label: String = "") : PhoneCommand
  data class Alarm(val hour: Int, val minute: Int, val days: List<Int> = emptyList(), val label: String = "", val date: String? = null) : PhoneCommand
  data class ClockList(val alarms: Boolean) : PhoneCommand
  data class Stopwatch(val operation: StopwatchOp) : PhoneCommand
  data class OpenApp(val name: String, internal val packageName: String? = null) : PhoneCommand
  data class Volume(val operation: VolumeOp, val percent: Int = 0) : PhoneCommand
  data class Torch(val enabled: Boolean) : PhoneCommand
  data class Settings(val kind: String) : PhoneCommand
}
sealed interface PhoneRoute {
  data class Local(val command: PhoneCommand) : PhoneRoute
  data class Clarify(val prompt: String, val context: String) : PhoneRoute
  data class Unsupported(val message: String, val recoverable: Boolean = false) : PhoneRoute
  data object Instinct : PhoneRoute
  data object Cancel : PhoneRoute
}
