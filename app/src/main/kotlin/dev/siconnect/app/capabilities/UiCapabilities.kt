package dev.siconnect.app.capabilities

import android.os.Process
import dev.siconnect.app.coordination.UiLease
import dev.siconnect.app.coordination.UiLeaseCoordinator
import dev.siconnect.core.*
import kotlinx.serialization.json.*

/** Authenticated registry capabilities only; no independent screen-control transport. */
fun registerUiCapabilities(registry: CapabilityRegistry, coordinator: UiLeaseCoordinator) {
    val output = objectSchema {
        boolean("leased", "The shared screen currently has a cooperative lease", true)
        boolean("ownedByCaller", "This authenticated agent owns the lease", true)
        string("leaseId", "Reference required to renew/release or pass to apps.launch; only returned to owner")
        string("targetPackage", "Selected target package")
        integer("acquiredAtMs", "Lease acquisition epoch milliseconds")
        integer("expiresAtMs", "Lease expiration epoch milliseconds")
        string("activeRequestId", "Executing screen request, visible only to lease owner")
        boolean("actionInProgress", "An action must finish before another owner can acquire")
        boolean("expired", "TTL elapsed; an active action still owns the screen until it finishes")
        boolean("released", "Whether this request released its lease")
        string("isolation", "Cooperative structured-UI coordination limitation", true)
    }
    fun capability(id: String, title: String, input: JsonObject, risk: RiskClass,
                   body: (JsonObject, ExecutionContext) -> JsonObject) {
        registry.register(object : Capability {
            override val descriptor = CapabilityDescriptor(id, title = title,
                description = "$title. Coordinates structured screen operations without opening an Activity. Arbitrary owner-authorized privileged shell can bypass this cooperative lease. A lease does not authorize any other capability.",
                inputSchema = input, outputSchema = output, backend = BackendKind.NATIVE,
                risk = risk, interaction = InteractionMode.BACKGROUND,
                sideEffects = if (risk > RiskClass.READ_ONLY) listOf("Reserves or releases cooperative access to the shared screen") else emptyList())
            override fun availability() = Availability.IMPLEMENTED
            override suspend fun execute(args: JsonObject, ctx: ExecutionContext) = CapabilityResult(
                body(args, ctx), BackendKind.NATIVE, "uid=${Process.myUid()}", VerificationStatus.VERIFIED,
                "Ownership checked against authenticated agent and durable device-wide lease")
        })
    }
    capability("ui.lease_acquire", "Reserve shared screen for a target app", objectSchema {
        string("targetPackage", "Concrete target Android package", true, maxLength = 200)
        integer("ttlMs", "Lease duration (default 30000); renew before expiry", min = 5000, max = 300000)
    }, RiskClass.LOW_IMPACT) { a, ctx ->
        leaseJson(coordinator.acquire(ctx, a.str("targetPackage"), a.long("ttlMs", 30_000)), ctx)
    }
    capability("ui.lease_renew", "Renew your shared-screen reservation", objectSchema {
        string("leaseId", "Your exact lease reference", true, maxLength = 100)
        integer("ttlMs", "New duration from now (default 30000)", min = 5000, max = 300000)
    }, RiskClass.LOW_IMPACT) { a, ctx ->
        leaseJson(coordinator.renew(ctx, a.str("leaseId"), a.long("ttlMs", 30_000)), ctx)
    }
    capability("ui.lease_release", "Release your shared-screen reservation", objectSchema {
        string("leaseId", "Your exact lease reference", true, maxLength = 100)
    }, RiskClass.LOW_IMPACT) { a, ctx ->
        val released = coordinator.release(ctx, a.str("leaseId"))
        buildJsonObject { leaseJson(coordinator.status(), ctx).forEach { (key, value) -> put(key, value) }; put("released", released) }
    }
    capability("ui.lease_status", "Inspect shared-screen reservation availability", objectSchema(), RiskClass.READ_ONLY) { _, ctx ->
        leaseJson(coordinator.status(), ctx)
    }
}

private fun leaseJson(lease: UiLease?, ctx: ExecutionContext): JsonObject = buildJsonObject {
    put("leased", lease != null); put("ownedByCaller", lease?.agentId == ctx.agentId)
    put("isolation", "COOPERATIVE_ONLY; arbitrary privileged shell is outside this boundary")
    lease?.let {
        if (it.agentId == ctx.agentId) {
            put("leaseId", it.leaseId)
            it.activeRequestId?.let { id -> put("activeRequestId", id) }
        }
        put("targetPackage", it.targetPackage); put("acquiredAtMs", it.acquiredAtMs); put("expiresAtMs", it.expiresAtMs)
        put("actionInProgress", it.activeRequestId != null); put("expired", it.expiresAtMs <= System.currentTimeMillis())
    }
}
