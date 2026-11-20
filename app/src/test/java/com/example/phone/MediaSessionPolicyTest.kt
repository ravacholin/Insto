package com.example.phone
import org.junit.Assert.*
import org.junit.Test
class MediaSessionPolicyTest {
 @Test fun multipleSessionsRequireSelectionAndUnsupportedActionsDoNotDispatch() {
  val candidates=listOf(MediaSessionOption("a",setOf(MediaOp.PLAY)),MediaSessionOption("b",setOf(MediaOp.PAUSE)))
  assertEquals(MediaResolution.Choose,MediaSessionPolicy.resolve(candidates,MediaOp.PLAY,null))
  assertEquals(MediaResolution.Unsupported,MediaSessionPolicy.resolve(candidates,MediaOp.PAUSE,"a"))
  assertEquals(MediaResolution.Ready("b"),MediaSessionPolicy.resolve(candidates,MediaOp.PAUSE,"b"))
 }
 @Test fun duplicateSessionsFromSameAppCannotBeSelectedArbitrarily() {
  val candidates=List(2){MediaSessionOption("a",setOf(MediaOp.PLAY))}
  assertEquals(MediaResolution.Ambiguous,MediaSessionPolicy.resolve(candidates,MediaOp.PLAY,"a"))
  assertEquals(MediaResolution.Missing,MediaSessionPolicy.resolve(emptyList(),MediaOp.PLAY,null))
 }
 @Test fun aNamedAppIsNotBlockedByDuplicateSessionsInAnotherApp() {
  val spotify = MediaSessionOption("com.spotify.music", setOf(MediaOp.PAUSE))
  val youtube = MediaSessionOption("com.google.android.youtube", setOf(MediaOp.PAUSE))
  assertEquals(MediaResolution.Ready("com.spotify.music"),
   MediaSessionPolicy.resolve(listOf(youtube, spotify, youtube), MediaOp.PAUSE, "com.spotify.music"))
  assertEquals(MediaResolution.Missing,
   MediaSessionPolicy.resolve(listOf(youtube), MediaOp.PAUSE, "com.spotify.music"))
 }
 @Test fun stopPrefersThePlayingSessionAndUsesPauseOnlyIfNeeded() {
  val spotify = MediaSessionOption("com.spotify.music", setOf(MediaOp.PAUSE), playing = true)
  val youtube = MediaSessionOption("com.google.android.youtube", setOf(MediaOp.STOP))
  val sessions = listOf(youtube, spotify)
  assertEquals(MediaResolution.Ready("com.spotify.music", fallbackOperation = MediaOp.PAUSE),
   MediaSessionPolicy.resolve(sessions, MediaOp.STOP, null))
  assertEquals(MediaResolution.Ready("com.google.android.youtube"),
   MediaSessionPolicy.resolve(sessions, MediaOp.STOP, "com.google.android.youtube"))
  assertEquals(MediaResolution.Unsupported,
   MediaSessionPolicy.resolve(sessions, MediaOp.PAUSE, "com.google.android.youtube"))
 }
 @Test fun theContextAppOnlyReplacesTheChoiceAmongSeveralSessions() {
  val spotify = MediaSessionOption("com.spotify.music", setOf(MediaOp.PAUSE, MediaOp.NEXT))
  val youtube = MediaSessionOption("com.google.android.youtube", setOf(MediaOp.PAUSE))
  val sessions = listOf(youtube, spotify)
  assertEquals(MediaResolution.Ready("com.spotify.music"),
   MediaSessionPolicy.resolve(sessions, MediaOp.NEXT, null, preferred = "com.spotify.music"))
  // An explicit app prevails over the context.
  assertEquals(MediaResolution.Ready("com.google.android.youtube"),
   MediaSessionPolicy.resolve(sessions, MediaOp.PAUSE, "com.google.android.youtube", preferred = "com.spotify.music"))
  // A context app without a session does not invent one: the user still chooses.
  assertEquals(MediaResolution.Choose,
   MediaSessionPolicy.resolve(sessions, MediaOp.PAUSE, null, preferred = "com.example.radio"))
  // A single session is used as before, whatever the context says.
  assertEquals(MediaResolution.Ready("com.google.android.youtube"),
   MediaSessionPolicy.resolve(listOf(youtube), MediaOp.PAUSE, null, preferred = "com.spotify.music"))
  assertEquals(MediaResolution.Unsupported,
   MediaSessionPolicy.resolve(sessions, MediaOp.NEXT, null, preferred = "com.google.android.youtube"))
 }
}
