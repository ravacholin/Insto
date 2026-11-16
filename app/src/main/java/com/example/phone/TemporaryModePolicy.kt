package com.example.phone

data class ModeLease(val id: String, val kind: ModeKind, val original: Int, val applied: Int, val expiresAt: Long, val owned: Boolean, val exact: Boolean = false)
object TemporaryModePolicy {
 fun canRestore(lease: ModeLease, current: Int) = lease.owned && lease.applied == current
 fun expired(lease: ModeLease, now: Long) = now >= lease.expiresAt
}
