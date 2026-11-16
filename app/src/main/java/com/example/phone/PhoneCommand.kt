package com.example.phone

enum class StopwatchOp { START, PAUSE, RESUME, RESET, STATUS }
enum class TravelMode { DRIVING, WALKING, BICYCLING, TRANSIT }
enum class VolumeOp { UP, DOWN, SET }
sealed interface PhoneCommand {
  data class Navigate(val destination: String, val mode: TravelMode = TravelMode.DRIVING) : PhoneCommand
  data class MapSearch(val query: String) : PhoneCommand
  data class SavePlace(val alias: String, val address: String) : PhoneCommand
  data class Dial(val target: String) : PhoneCommand
  data class CalendarEvent(val title: String, val beginMillis: Long, val endMillis: Long) : PhoneCommand
  data class Camera(val video: Boolean = false) : PhoneCommand
  data class YouTubeSearch(val query: String) : PhoneCommand
  data class MusicSearch(val query: String) : PhoneCommand
  data class Timer(val seconds: Int, val label: String = "") : PhoneCommand
  data class Alarm(val hour: Int, val minute: Int, val days: List<Int> = emptyList(), val label: String = "") : PhoneCommand
  data class ClockList(val alarms: Boolean) : PhoneCommand
  data class Stopwatch(val operation: StopwatchOp) : PhoneCommand
  data class OpenApp(val name: String) : PhoneCommand
  data class Volume(val operation: VolumeOp, val percent: Int = 0) : PhoneCommand
  data class Torch(val enabled: Boolean) : PhoneCommand
  data class Settings(val kind: String) : PhoneCommand
}
sealed interface PhoneRoute {
  data class Local(val command: PhoneCommand) : PhoneRoute
  data class Clarify(val prompt: String, val context: String) : PhoneRoute
  data class Unsupported(val message: String) : PhoneRoute
  data object Instinct : PhoneRoute
  data object Cancel : PhoneRoute
}
