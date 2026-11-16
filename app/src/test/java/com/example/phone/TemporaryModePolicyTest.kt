package com.example.phone
import org.junit.Assert.*
import org.junit.Test
class TemporaryModePolicyTest {
 @Test fun manualChangesAndOwnershipLossPreventRestoration() {
  val lease = ModeLease("one", ModeKind.MEDIA_VOLUME, 9, 2, 2000, true)
  assertTrue(TemporaryModePolicy.canRestore(lease, current = 2))
  assertFalse(TemporaryModePolicy.canRestore(lease, current = 5))
  assertFalse(TemporaryModePolicy.canRestore(lease.copy(owned = false), current = 2))
 }
 @Test fun expiredAndOverlappingModesAreExplicit() {
  val lease = ModeLease("one", ModeKind.SILENT, 1, 0, 2000, true)
  assertTrue(TemporaryModePolicy.expired(lease, 2000))
  assertFalse(TemporaryModePolicy.expired(lease, 1999))
 }
}
