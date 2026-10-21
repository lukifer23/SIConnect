package dev.siconnect.app.coordination

import dev.siconnect.core.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.util.UUID

/** Cooperative ownership of Android's single shared screen, not a sandbox for arbitrary shell. */
@Serializable
data class UiLease(
    val leaseId: String,
    val agentId: String,
    val requestId: String,
    val traceId: String,
    val targetPackage: String,
    val acquiredAtMs: Long,
    val expiresAtMs: Long,
    val processGeneration: String,
    val activeRequestId: String? = null,
    val activeTraceId: String? = null,
)

interface UiLeaseStore {
    fun <T> atomic(block: () -> T): T
    fun get(): UiLease?
    fun put(lease: UiLease)
    fun clear()
}

/**
 * One instance in the one app process. Every ownership decision also commits to SQLite so a
 * restarted process can identify abandoned ownership. Never steals an in-flight action at TTL:
 * expiry prevents renewal/use, but another action must wait for the current callback to finish.
 */
class UiLeaseCoordinator(
    private val store: UiLeaseStore,
    private val events: EventSink,
    private val deviceId: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val generation: String = UUID.randomUUID().toString(),
) {
    private val lock = Any()

    /** Call once during runtime startup before accepting requests. No screen action is replayed. */
    fun recover() = synchronized(lock) { store.atomic {
        store.get()?.takeIf { it.processGeneration != generation }?.let {
            store.clear()
            event("ui.lease.abandoned", it, error = if (it.activeRequestId != null) "UNKNOWN_OUTCOME" else null)
        }
    } }

    fun acquire(ctx: ExecutionContext, targetPackage: String, ttlMs: Long = 30_000): UiLease = synchronized(lock) { store.atomic {
        validateTarget(targetPackage); validateTtl(ttlMs)
        existing()?.let { busy() }
        val now = clock()
        UiLease(UUID.randomUUID().toString(), ctx.agentId, ctx.requestId, ctx.traceId,
            targetPackage, now, Math.addExact(now, ttlMs), generation).also {
            store.put(it); event("ui.lease.acquired", it)
        }
    } }

    fun renew(ctx: ExecutionContext, leaseId: String, ttlMs: Long = 30_000): UiLease = synchronized(lock) { store.atomic {
        validateTtl(ttlMs)
        val lease = owned(ctx, leaseId)
        if (lease.expiresAtMs <= clock()) throw CapabilityException("LEASE_EXPIRED", "Acquire a new screen lease after the current action finishes", noSideEffects = true)
        lease.copy(expiresAtMs = Math.addExact(clock(), ttlMs)).also {
            store.put(it); event("ui.lease.renewed", it, ctx)
        }
    } }

    fun release(ctx: ExecutionContext, leaseId: String): Boolean = synchronized(lock) { store.atomic {
        val lease = store.get() ?: return@atomic false
        requireOwner(lease, ctx, leaseId)
        if (lease.activeRequestId != null) busy()
        store.clear(); event("ui.lease.released", lease, ctx); true
    } }

    /** Owner fields and lease ID are redacted by the capability for other agents. */
    fun status(): UiLease? = synchronized(lock) { store.atomic { existing() } }

    /**
     * Structured SCREEN capabilities wrap their real implementation here. An explicit lease spans
     * multiple calls; without one, acquire/release ownership just for this invocation. Unrelated
     * native/background capabilities never enter this coordinator.
     */
    suspend fun <T> withScreen(ctx: ExecutionContext, targetPackage: String, leaseId: String? = null,
                               block: suspend () -> T): T {
        val implicit = leaseId == null
        val lease = synchronized(lock) { store.atomic {
            validateTarget(targetPackage)
            val selected = if (implicit) acquire(ctx, targetPackage) else owned(ctx, leaseId)
            if (selected.targetPackage != targetPackage) throw CapabilityException("LEASE_TARGET_MISMATCH", "Screen lease targets a different package", noSideEffects = true)
            if (selected.expiresAtMs <= clock()) throw CapabilityException("LEASE_EXPIRED", "Screen lease has expired; acquire another before acting", noSideEffects = true)
            if (selected.activeRequestId != null) busy()
            selected.copy(activeRequestId = ctx.requestId, activeTraceId = ctx.traceId).also {
                store.put(it); event("ui.action.started", it, ctx)
            }
        } }
        var complete = false
        try { return block().also { complete = true } }
        finally { withContext(NonCancellable) {
            synchronized(lock) { store.atomic {
                val current = store.get()
                if (current?.leaseId == lease.leaseId && current.activeRequestId == ctx.requestId) {
                    if (implicit || current.expiresAtMs <= clock()) store.clear()
                    else store.put(current.copy(activeRequestId = null, activeTraceId = null))
                    event(if (complete) "ui.action.finished" else "ui.action.interrupted", current, ctx,
                        error = if (complete) null else "UNKNOWN_OUTCOME")
                }
            } }
        } }
    }

    private fun existing(): UiLease? {
        val lease = store.get() ?: return null
        if (lease.expiresAtMs <= clock() && lease.activeRequestId == null) {
            store.clear(); event("ui.lease.expired", lease); return null
        }
        return lease
    }

    private fun owned(ctx: ExecutionContext, id: String): UiLease {
        val lease = store.get() ?: throw CapabilityException("LEASE_NOT_FOUND", "Screen lease no longer exists; acquire a new one", noSideEffects = true)
        requireOwner(lease, ctx, id)
        if (lease.processGeneration != generation) throw CapabilityException("LEASE_ABANDONED", "Runtime restarted; acquire a new screen lease", noSideEffects = true)
        return lease
    }

    private fun requireOwner(lease: UiLease, ctx: ExecutionContext, id: String) {
        if (lease.agentId != ctx.agentId || lease.leaseId != id)
            throw CapabilityException("LEASE_NOT_OWNED", "Screen lease does not belong to this authenticated agent", noSideEffects = true)
    }

    private fun busy(): Nothing {
        throw CapabilityException("RESOURCE_BUSY", "Shared screen is reserved; inspect ui.lease_status and retry after release or expiration. No UI action was performed", retryable = true, noSideEffects = true)
    }

    private fun event(type: String, lease: UiLease, ctx: ExecutionContext? = null, error: String? = null) {
        events.emit(Event(clock(), eventType = type, requestId = ctx?.requestId ?: lease.activeRequestId ?: lease.requestId,
            traceId = ctx?.traceId ?: lease.activeTraceId ?: lease.traceId, agentId = ctx?.agentId ?: lease.agentId,
            deviceId = deviceId, backend = "NATIVE", errorCode = error,
            detail = "Cooperative screen ownership; arbitrary broad shell is outside this boundary"))
    }

    private fun validateTtl(ttlMs: Long) {
        if (ttlMs !in 5_000..300_000) throw CapabilityException("INVALID_ARGUMENT", "Lease duration must be 5000–300000 milliseconds", noSideEffects = true)
    }
    private fun validateTarget(target: String) {
        if (!target.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*")) || target.length > 200)
            throw CapabilityException("INVALID_PACKAGE", "A concrete Android target package is required", noSideEffects = true)
    }
}
