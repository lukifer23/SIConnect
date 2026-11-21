package dev.siconnect.app.capabilities

import dev.siconnect.app.RuntimeGraph
import dev.siconnect.app.clocks.ClockTimers
import dev.siconnect.app.notifications.NotificationListenerAccess
import dev.siconnect.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Typed managed timers only: no arbitrary shell, global timer dismissal, or other-agent adoption. */
fun registerClockCapabilities(g:RuntimeGraph) {
    val timers by lazy {ClockTimers(g)}
    fun background(args:JsonObject) = args["leaseId"]==null && (args["packageName"]==null || args["packageName"]?.jsonPrimitive?.content==g.context.packageName)
    g.registry.register(object:Capability {
        override val descriptor=CapabilityDescriptor("clock.timer_start",version=2,title="Start a timer on this phone",
            description="Start a 1–86400 second SIConnect countdown in the background. Omit packageName for an ordinary hands-free timer. To use a specific external Clock app, supply its packageName. That route requires connected notification access and a visible SIConnect activity, and waits for owner review when SIConnect is in the background. Use clock.timer_status and clock.timer_cancel with the returned timerId. Never automatically replay an uncertain external Clock dispatch.",
            inputSchema=objectSchema {
                integer("durationSeconds","Exact timer duration in seconds",true,min=1,max=86400)
                string("label","Optional single-line label, up to 80 characters. External Clock adds an ownership marker.",maxLength=80)
                string("packageName","Optional external Clock package. Omit for a SIConnect background countdown.",maxLength=200)
                string("leaseId","Optional owned shared-screen lease targeting that Clock package",maxLength=100)
            },backend=BackendKind.NATIVE,risk=RiskClass.MODERATE_IMPACT,interaction=InteractionMode.MAY_OPEN_SCREEN,hasBackgroundRoute=true,timeoutMs=15000,
            permissions=listOf("SIConnect countdown: exact alarms and timer notifications", "External Clock: SET_ALARM and connected notification access"),
            sideEffects=listOf("Schedules a SIConnect notification countdown or opens the selected Clock app to start its timer"))
        override fun availability()=if(timers.supportedUser()) g.reminders.countdownAvailability() else Availability.UNSUPPORTED
        override fun availability(arguments:JsonObject)=if(background(arguments)) availability() else timers.startAvailability(arguments)
        override fun availabilityDetail()="Background countdowns require precise alarms and timer notifications. External Clock apps require connected notification access."
        override fun assess(args:JsonObject)=RiskAssessment(RiskClass.MODERATE_IMPACT,descriptor.title,target=args["packageName"]?.jsonPrimitive?.content,
            sideEffects=descriptor.sideEffects,requiresForegroundReview=!background(args) && !g.ownerScreenVisible,nativeBackgroundOnly=background(args))
        override suspend fun execute(args:JsonObject,ctx:ExecutionContext):CapabilityResult {
            if(!background(args)) return timers.start(args,ctx,descriptor)
            val r=g.reminders.startCountdown(ctx,args.getValue("durationSeconds").jsonPrimitive.int,args["label"]?.jsonPrimitive?.content)
            return CapabilityResult(g.reminders.countdownMetadata(r),BackendKind.NATIVE,verification=VerificationStatus.VERIFIED,
                verificationDetail="SIConnect saved the countdown and scheduled its Android alarm")
        }
    })
    g.registry.register(object:Capability {
        override val descriptor=CapabilityDescriptor("clock.timer_status",title="Read your managed Clock timers",
            description="Read this agent's SIConnect countdowns and durable system-user external Clock timer identities and fresh exact-label Clock notification observations. Secondary/work profiles are unsupported. Optional timerId selects one owned timer; otherwise list bounded metadata pages. Never enumerates or adopts unrelated user timers. No notification does not prove stopped/expired. SIConnect reports its saved deadline and remaining seconds. External Clock seconds are available only when its notification publishes chronometer metadata; Samsung label evidence alone does not prove duration. Readback never starts, cancels, pauses or retries a timer.",
            inputSchema=objectSchema {
                string("timerId","Optional exact own timerId from start/status",maxLength=100)
                integer("limit","Maximum own timers; default20",min=1,max=50)
                string("beforeTimerId","Optional nextBeforeTimerId from the preceding own metadata page",maxLength=100)
            },backend=BackendKind.NATIVE,risk=RiskClass.READ_ONLY,interaction=InteractionMode.BACKGROUND)
        override fun availability()=if(timers.supportedUser()) Availability.IMPLEMENTED else Availability.UNSUPPORTED
        override suspend fun execute(args:JsonObject,ctx:ExecutionContext)=withContext(Dispatchers.IO) {
            CapabilityResult(timers.status(args,ctx),BackendKind.NATIVE,verification=VerificationStatus.VERIFIED,
                verificationDetail="Actual own timer journal plus available current-user notification observations, without timer mutation")
        }
    })
    g.registry.register(object:Capability {
        override val descriptor=CapabilityDescriptor("clock.timer_cancel",title="Cancel one owned Clock timer",
            description="Cancel an owned SIConnect countdown and read its saved state. For an external Clock timer dispatch exactly one freshly revalidated Clock-published cancellation action for this agent's uniquely labeled system-user timer. Secondary/work profiles are refused before timer dispatch. Requires current app UID/install identity, one exact owned non-group notification, no other Clock child notification, and a safe creator-bound non-authentication action with no text input or Activity launch. Supports published STOP semantics or verified Samsung Cancel/Stop labels. Uses the shared-screen lease and current policy. Refuses ambiguity, unknown ownership and an already dispatched cancellation; never uses a global dismiss-all Intent. Notification disappearance after dispatch is reported separately from independent timer-store verification. Pause/resume are not included without verified app support.",
            inputSchema=objectSchema {
                string("timerId","Exact owned managed timerId",true,maxLength=100)
                string("leaseId","Optional owned shared-screen lease targeting this Clock package",maxLength=100)
            },backend=BackendKind.NATIVE,risk=RiskClass.MODERATE_IMPACT,interaction=InteractionMode.MAY_OPEN_SCREEN,hasBackgroundRoute=true,timeoutMs=10000,
            sideEffects=listOf("Cancels the owned SIConnect countdown or invokes the selected Clock app’s observed cancellation action"))
        override fun availability()=if(timers.supportedUser()) Availability.IMPLEMENTED else Availability.UNSUPPORTED
        override fun assess(args:JsonObject)=RiskAssessment(descriptor.risk,descriptor.title,sideEffects=descriptor.sideEffects,
            nativeBackgroundOnly=args["timerId"]?.jsonPrimitive?.content?.startsWith("countdown:")==true)
        override fun availability(arguments:JsonObject)=if(arguments["timerId"]?.jsonPrimitive?.content?.startsWith("countdown:")==true) availability() else timers.controlAvailability()
        override fun availabilityDetail()=if(!timers.supportedUser()) "Managed Clock timers support Android's system user only; secondary/work profiles are unsupported." else NotificationListenerAccess.detail(g.context)
        override suspend fun execute(args:JsonObject,ctx:ExecutionContext):CapabilityResult {
            val id=args.getValue("timerId").jsonPrimitive.content
            if(!id.startsWith("countdown:")) return timers.cancel(args,ctx,descriptor)
            val owned=g.reminders.ownedCountdown(ctx.agentId,id)
            val result=g.reminders.cancel(owned.id)
            return CapabilityResult(g.reminders.countdownMetadata(result),BackendKind.NATIVE,verification=VerificationStatus.VERIFIED,
                verificationDetail="Read back SIConnect countdown state after cancellation")
        }
    })
}
