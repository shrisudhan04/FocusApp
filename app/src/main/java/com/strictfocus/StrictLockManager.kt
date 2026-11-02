package com.strictfocus

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.lockStore by preferencesDataStore(name = "strict_lock")

/** One block: a single app is blocked from startMillis until endMillis. */
data class BlockRule(val packageName: String, val startMillis: Long, val endMillis: Long) {
    fun isActiveAt(now: Long = System.currentTimeMillis()) = now >= startMillis && now < endMillis
    fun isExpiredAt(now: Long = System.currentTimeMillis()) = now >= endMillis
}

/**
 * Compatibility view used by StrictBlockActivity, BootReceiver and FocusDeviceAdminReceiver.
 * packages = apps blocked right now, expiresAtMillis = latest end among them.
 */
data class LockSession(val packages: Set<String>, val expiresAtMillis: Long) {
    val isActive: Boolean
        get() = packages.isNotEmpty() && System.currentTimeMillis() < expiresAtMillis

    fun remainingMillis(now: Long = System.currentTimeMillis()): Long =
        (expiresAtMillis - now).coerceAtLeast(0L)
}

object StrictLockManager {
    private val rulesKey = stringSetPreferencesKey("block_rules")
    private val emergencyUnlockUntilKey = androidx.datastore.preferences.core.longPreferencesKey("emergency_unlock_until")
    private val emergencyUnlockLastUsedKey = androidx.datastore.preferences.core.longPreferencesKey("emergency_unlock_last_used")

    /** Emergency unlock temporarily suspends blocking globally for a short, user-confirmed window. */
    suspend fun activateEmergencyUnlock(context: Context, durationMillis: Long = 5 * 60_000L) {
        require(durationMillis in 60_000L..10 * 60_000L) { "Emergency unlock must be between 1 and 10 minutes." }
        context.lockStore.edit { prefs ->
            val now = System.currentTimeMillis()
            val lastUsed = prefs[emergencyUnlockLastUsedKey] ?: 0L
            require(now - lastUsed >= 30 * 60_000L) { "Emergency unlock is available once every 30 minutes." }
            prefs[emergencyUnlockLastUsedKey] = now
            prefs[emergencyUnlockUntilKey] = now + durationMillis
        }
    }

    suspend fun emergencyUnlockRemainingMillis(context: Context): Long =
        ((context.lockStore.data.first()[emergencyUnlockUntilKey] ?: 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    private suspend fun isEmergencyUnlockActive(context: Context): Boolean {
        val until = context.lockStore.data.first()[emergencyUnlockUntilKey] ?: 0L
        return System.currentTimeMillis() < until
    }

    private fun encode(rule: BlockRule) = "${rule.packageName}|${rule.startMillis}|${rule.endMillis}"

    private fun decode(value: String): BlockRule? {
        val parts = value.split("|")
        if (parts.size != 3) return null
        val start = parts[1].toLongOrNull() ?: return null
        val end = parts[2].toLongOrNull() ?: return null
        return BlockRule(parts[0], start, end)
    }

    /** All rules that have not ended yet, including scheduled ones that have not started. */
    fun rulesFlow(context: Context): Flow<List<BlockRule>> =
        context.lockStore.data.map { prefs ->
            val now = System.currentTimeMillis()
            (prefs[rulesKey] ?: emptySet())
                .mapNotNull { decode(it) }
                .filter { !it.isExpiredAt(now) }
                .sortedBy { it.startMillis }
        }

    fun sessionFlow(context: Context): Flow<LockSession> =
        rulesFlow(context).map { rules ->
            val now = System.currentTimeMillis()
            val active = rules.filter { it.isActiveAt(now) }
            LockSession(
                packages = active.map { it.packageName }.toSet(),
                expiresAtMillis = active.maxOfOrNull { it.endMillis } ?: 0L
            )
        }

    /**
     * Adds a block for one app. Existing blocks are never removed or shortened,
     * so the lock can only get stricter.
     */
    suspend fun addRule(context: Context, packageName: String, startMillis: Long, endMillis: Long) {
        require(endMillis - startMillis >= 60_000L) { "A block must be at least 1 minute." }
        require(endMillis - startMillis <= 86_400_000L) { "A block can be at most 24 hours." }
        require(endMillis > System.currentTimeMillis()) { "The end time has already passed." }

        context.lockStore.edit { prefs ->
            val now = System.currentTimeMillis()
            val existing = (prefs[rulesKey] ?: emptySet())
                .mapNotNull { decode(it) }
                .filter { !it.isExpiredAt(now) }
            prefs[rulesKey] = (existing + BlockRule(packageName, startMillis, endMillis))
                .map { encode(it) }
                .toSet()
        }
    }

    /** Removes only rules that have already ended. Active and scheduled rules are kept. */
    suspend fun clear(context: Context) {
        context.lockStore.edit { prefs ->
            val now = System.currentTimeMillis()
            prefs[rulesKey] = (prefs[rulesKey] ?: emptySet())
                .filter { decode(it)?.isExpiredAt(now) == false }
                .toSet()
        }
    }

    suspend fun isLocked(context: Context, packageName: String): Boolean {
        if (isEmergencyUnlockActive(context)) return false
        val now = System.currentTimeMillis()
        return rulesFlow(context).first().any { it.packageName == packageName && it.isActiveAt(now) }
    }

    suspend fun remainingMillis(context: Context): Long =
        sessionFlow(context).first().remainingMillis()

    suspend fun getSession(context: Context): LockSession =
        sessionFlow(context).first()
}