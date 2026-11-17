package com.example.phone

data class MediaSessionOption(val packageName: String, val operations: Set<MediaOp>, val playing: Boolean = false)
sealed interface MediaResolution {
 data object Missing:MediaResolution
 data object Choose:MediaResolution
 data object Ambiguous:MediaResolution
 data object Unsupported:MediaResolution
 data class Ready(val packageName: String, val fallbackOperation: MediaOp? = null) : MediaResolution
}
object MediaSessionPolicy {
 fun resolve(sessions:List<MediaSessionOption>,operation:MediaOp,packageName:String?):MediaResolution {
  val candidates = if (packageName == null) sessions else sessions.filter { it.packageName == packageName }
  if (candidates.isEmpty()) return MediaResolution.Missing
  val playing = if (packageName == null && operation == MediaOp.STOP) candidates.filter { it.playing }.singleOrNull() else null
  val choices = if (playing == null) candidates else candidates.filter { it.packageName == playing.packageName }
  if (choices.groupingBy { it.packageName }.eachCount().any { it.value > 1 }) return MediaResolution.Ambiguous
  val target = choices.singleOrNull() ?: return MediaResolution.Choose
  if (operation == MediaOp.STATUS || operation in target.operations) return MediaResolution.Ready(target.packageName)
  if (operation == MediaOp.STOP && MediaOp.PAUSE in target.operations) {
   return MediaResolution.Ready(target.packageName, fallbackOperation = MediaOp.PAUSE)
  }
  return MediaResolution.Unsupported
 }
}
