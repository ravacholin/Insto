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
}
