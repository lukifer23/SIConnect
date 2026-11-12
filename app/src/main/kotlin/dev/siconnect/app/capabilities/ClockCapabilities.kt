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
    g.registry.register(object:Capability {
        private val memo=backendDescriptor({g.privileged.kind}) {kind->CapabilityDescriptor("clock.timer_start",title="Start an owned Clock timer",
            description="Start a1..86400-second timer in an installed SET_TIMER handler through the selected authorized backend. Currently supports Android's system user only; secondary/work profiles are refused before timer dispatch. Appends an exact unique SIConnect ownership marker to the label, records identity before dispatch, and requests skip UI. Requires connected notification access and no preexisting current-user Clock notifications, preserving other timers/alarms/alerts. Reserves the shared screen because Clock may open UI. Dispatch acceptance is distinct from exact-label notification or native countdown observation. Never replay an uncertain start; discover the original timer with clock.timer_status. Supports the observed Samsung Clock published-label format and exact standard labels from other Clock handlers; unsupported readback is reported honestly.",
            inputSchema=objectSchema {
                integer("durationSeconds","Exact timer duration in seconds",true,min=1,max=86400)
                string("label","Optional single-line label up to80 characters; unique ownership marker is appended",maxLength=80)
                string("packageName","Optional exact installed SET_TIMER handler; otherwise Android's concrete default",maxLength=200)
                string("leaseId","Optional owned shared-screen lease targeting that Clock package",maxLength=100)
            },backend=kind,risk=RiskClass.MODERATE_IMPACT,interaction=InteractionMode.MAY_OPEN_SCREEN,timeoutMs=15000,
            permissions=listOf("Owner-selected authorized shell backend","Owner-granted connected notification listener"),
            sideEffects=listOf("Starts an audible Clock timer; the app may open its screen"))}
        override val descriptor get()=memo()
        override fun availability()=timers.startAvailability()
        override fun availability(arguments:JsonObject)=timers.startAvailability(arguments)
        override fun availabilityDetail()="System-user-only managed timers: secondary/work profiles are unsupported. Requires selected authorized backend, connected notification access, an enabled Clock SET_TIMER handler and no existing Clock work. No fallback to global dismissal or arbitrary shell."
        override fun assess(args:JsonObject)=RiskAssessment(RiskClass.MODERATE_IMPACT,descriptor.title,target=args["packageName"]?.jsonPrimitive?.content,
            sideEffects=descriptor.sideEffects)
        override suspend fun execute(args:JsonObject,ctx:ExecutionContext)=timers.start(args,ctx,descriptor)
    })
    g.registry.register(object:Capability {
        override val descriptor=CapabilityDescriptor("clock.timer_status",title="Read your managed Clock timers",
            description="Read this agent's durable system-user timer identities and fresh exact-label Clock notification observations. Secondary/work profiles are unsupported. Optional timerId selects one owned timer; otherwise list bounded metadata pages. Never enumerates or adopts unrelated user timers. No notification does not prove stopped/expired. Native countdown seconds are reported only when Clock publishes chronometer metadata; Samsung label evidence alone does not prove duration. Readback never starts, cancels, pauses or retries a timer.",
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
            description="Dispatch exactly one freshly revalidated Clock-published cancellation action for this agent's uniquely labeled system-user timer. Secondary/work profiles are refused before timer dispatch. Requires current app UID/install identity, one exact owned non-group notification, no other Clock child notification, and a safe creator-bound non-authentication action with no text input or Activity launch. Supports published STOP semantics or verified Samsung Cancel/Stop labels. Uses the shared-screen lease and current policy. Refuses ambiguity, unknown ownership and an already dispatched cancellation; never uses a global dismiss-all Intent. Notification disappearance after dispatch is reported separately from independent timer-store verification. Pause/resume are not included without verified app support.",
            inputSchema=objectSchema {
                string("timerId","Exact owned managed timerId",true,maxLength=100)
                string("leaseId","Optional owned shared-screen lease targeting this Clock package",maxLength=100)
            },backend=BackendKind.NATIVE,risk=RiskClass.MODERATE_IMPACT,interaction=InteractionMode.MAY_OPEN_SCREEN,timeoutMs=10000,
            sideEffects=listOf("Invokes the Clock app's published action for one exactly matched managed timer"))
        override fun availability()=timers.controlAvailability()
        override fun availabilityDetail()=if(!timers.supportedUser()) "Managed Clock timers support Android's system user only; secondary/work profiles are unsupported." else NotificationListenerAccess.detail(g.context)
        override suspend fun execute(args:JsonObject,ctx:ExecutionContext)=timers.cancel(args,ctx,descriptor)
    })
}
