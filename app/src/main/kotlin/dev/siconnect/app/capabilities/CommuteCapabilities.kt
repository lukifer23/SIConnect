package dev.siconnect.app.capabilities

import dev.siconnect.app.RuntimeGraph
import dev.siconnect.app.backend.DeviceLocation
import dev.siconnect.app.data.AppJson
import dev.siconnect.app.data.CommuteProfile
import dev.siconnect.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.time.*
import kotlin.math.ceil
import android.app.KeyguardManager
import android.content.Intent
import android.net.Uri
import android.util.Xml
import dev.siconnect.app.accessibility.SemanticAccessibilityService
import dev.siconnect.app.backend.ShellRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import org.xmlpull.v1.XmlPullParser

private const val MAPS_PACKAGE="com.google.android.apps.maps"

/** Timing is derived from provider duration, never straight-line distance or a guessed speed. */
object CommuteTiming {
    fun calculate(profile: CommuteProfile, args: JsonObject, durationSeconds: Double, now: Long): JsonObject {
        val zone=ZoneId.of(args["timezone"]?.jsonPrimitive?.content ?: profile.timezone)
        val arrival=args["arrivalAtMs"]?.jsonPrimitive?.longOrNull ?: profile.arrivalTime.takeIf(String::isNotBlank)?.let {time ->
            val date=args["targetDate"]?.jsonPrimitive?.content?.let(LocalDate::parse)
                ?: Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            date.atTime(LocalTime.parse(time)).atZone(zone).withEarlierOffsetAtOverlap().toInstant().toEpochMilli()
        }
        val buffer=args.long("bufferMinutes",profile.bufferMinutes?.toLong() ?: 0)
        val preparation=args["preparationMinutes"]?.jsonPrimitive?.longOrNull ?: profile.preparationMinutes?.toLong()
        require(buffer in 0..180 && (preparation==null || preparation in 0..360))
        require(durationSeconds.isFinite() && durationSeconds in 0.0..604800.0)
        val durationMs=ceil(durationSeconds*1000).toLong()
        return buildJsonObject {
            put("timezone",zone.id);put("bufferMinutes",buffer)
            put("bufferSource",if("bufferMinutes" in args) "EXPLICIT" else if(profile.bufferMinutes!=null) "SAVED_PROFILE" else "NONE_CONFIGURED")
            preparation?.let {put("preparationMinutes",it)}
            if(arrival!=null) {
                val leave=Math.subtractExact(Math.subtractExact(arrival,durationMs),buffer*60_000)
                val wake=preparation?.let {Math.subtractExact(leave,it*60_000)}
                put("arrivalAtMs",arrival);put("leaveByAtMs",leave)
                put("leaveByLocal",Instant.ofEpochMilli(leave).atZone(zone).toString())
                if(wake!=null) {put("wakeByAtMs",wake);put("wakeByLocal",Instant.ofEpochMilli(wake).atZone(zone).toString())}
                else put("status","PREPARATION_TIME_REQUIRED_FOR_WAKE_BY")
                put("departureAlreadyPassed",leave<now)
                profile.wakeTime.takeIf {it.isNotBlank() && wake!=null}?.let {
                    val date=Instant.ofEpochMilli(arrival).atZone(zone).toLocalDate()
                    val usual=date.atTime(LocalTime.parse(it)).atZone(zone).withEarlierOffsetAtOverlap().toInstant().toEpochMilli()
                    put("configuredWakeAtMs",usual);put("earlierWakeMinutes",ceil((usual-wake!!).coerceAtLeast(0)/60_000.0).toLong())
                }
            } else put("status","ARRIVAL_TIME_REQUIRED_FOR_LEAVE_BY")
            put("alarmChanged",false);put("reminderCreated",false)
        }
    }
}

class CommuteRouter(private val g:RuntimeGraph) {
    fun backend()=if(SemanticAccessibilityService.instance!=null) BackendKind.ACCESSIBILITY else g.privileged.kind
    fun availability()=if(SemanticAccessibilityService.instance!=null || g.privileged.available()) Availability.IMPLEMENTED else Availability.PRIVILEGE_REQUIRED
    private fun unlocked() {
        if(g.context.getSystemService(KeyguardManager::class.java).isDeviceLocked)
            throw CapabilityException("SCREEN_LOCKED","Unlock the phone to read Maps; no lock-screen controls were used",noSideEffects=true)
    }

    private suspend fun capture(ctx:ExecutionContext,backend:BackendKind):Pair<List<RouteLabel>,String> {
        unlocked()
        if(backend==BackendKind.ACCESSIBILITY) {
            val service=SemanticAccessibilityService.instance ?: throw CapabilityException("ACCESSIBILITY_DISCONNECTED","Accessibility disconnected; restore it or make a new request using the selected privileged backend")
            val snapshot=service.inspect(ctx.agentId,MAPS_PACKAGE,buildJsonObject {put("maxScanNodes",1000)},200)
            return snapshot.getValue("nodes").jsonArray.map {it.jsonObject}.map {node ->
                val b=node.getValue("bounds").jsonObject
                RouteLabel(node["text"]?.jsonPrimitive?.content.orEmpty(),node["description"]?.jsonPrimitive?.content.orEmpty(),b.getValue("top").jsonPrimitive.int,b.getValue("bottom").jsonPrimitive.int)
            } to "uid=${android.os.Process.myUid()}"
        }
        if(!g.settings.controls.value.privilegedEnabled || g.privileged.kind!=backend)
            throw CapabilityException("PRIVILEGE_REQUIRED","The selected privileged backend changed or was disabled; make a fresh request",noSideEffects=true)
        // The owned shell process has no controlling terminal. Write to its actual stdout pipe.
        val result=g.privileged.execute(ShellRequest(ctx.requestId+"-maps-read",argv=listOf("uiautomator","dump","/proc/self/fd/1"),timeoutMs=8_000,maxOutputBytes=128*1024))
        if(result.exitCode!=0 || result.timedOut || result.cancelled || result.stdoutTruncated)
            throw CapabilityException("ROUTE_SCREEN_UNREADABLE","Maps hierarchy capture did not complete; inspect the screen or restore accessibility")
        val start=result.stdout.indexOf("<?xml")
        val end=result.stdout.lastIndexOf("</hierarchy>")
        if(start<0 || end<start) throw CapabilityException("ROUTE_SCREEN_UNREADABLE","Maps hierarchy was not available")
        val parser=Xml.newPullParser().apply {setInput(result.stdout.substring(start,end+12).reader())}
        val labels=mutableListOf<RouteLabel>()
        while(parser.eventType!=XmlPullParser.END_DOCUMENT && labels.size<1000) {
            if(parser.eventType==XmlPullParser.START_TAG && parser.name=="node" && parser.getAttributeValue(null,"package")==MAPS_PACKAGE && parser.getAttributeValue(null,"password")!="true") {
                val bounds=parser.getAttributeValue(null,"bounds").orEmpty()
                val match=Regex("\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]").matchEntire(bounds)
                if(match!=null) labels+=RouteLabel(parser.getAttributeValue(null,"text").orEmpty(),parser.getAttributeValue(null,"content-desc").orEmpty(),match.groupValues[2].toInt(),match.groupValues[4].toInt())
            }
            parser.next()
        }
        unlocked()
        return labels to result.identity
    }

    suspend fun calculate(args:JsonObject,ctx:ExecutionContext,open:Boolean=true):JsonObject {
        unlocked()
        val selected=backend()
        if(selected!=BackendKind.ACCESSIBILITY && !g.settings.controls.value.privilegedEnabled)
            throw CapabilityException("PRIVILEGE_REQUIRED","Privileged execution is disabled; enable accessibility or the selected service",noSideEffects=true)
        val profile=g.settings.commute
        // Validate timing before taking the shared screen.
        CommuteTiming.calculate(profile,args,0.0,System.currentTimeMillis())
        val destination=args["destination"]?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotBlank) ?: profile.destination
        val expectedDestination=args["expectedDestinationLabel"]?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotBlank)
            ?: profile.destinationLabel.takeIf {it.isNotBlank() && (!open || destination.equals(profile.destination,true))}
            ?: (if(open) destination else profile.destination).substringBefore(',').trim().takeIf(String::isNotBlank)
        if(open && destination.isBlank()) throw CapabilityException("DESTINATION_REQUIRED","Supply the destination address or save it in Commute settings",noSideEffects=true)
        return g.ui.withScreen(ctx,MAPS_PACKAGE,args["leaseId"]?.jsonPrimitive?.content) {
            if(open) {
                val uri=Uri.parse("https://www.google.com/maps/dir/").buildUpon().appendQueryParameter("api","1")
                    .appendQueryParameter("destination",destination).appendQueryParameter("travelmode","driving")
                    .apply {args["origin"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)?.let {appendQueryParameter("origin",it)}}.build()
                if(selected==BackendKind.ACCESSIBILITY) withContext(Dispatchers.Main) {
                    g.context.startActivity(Intent(Intent.ACTION_VIEW,uri).setPackage(MAPS_PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    val launch=g.privileged.execute(ShellRequest(ctx.requestId+"-maps-open",argv=listOf("am","start","-W","-a",Intent.ACTION_VIEW,"-d",uri.toString(),"-p",MAPS_PACKAGE),timeoutMs=8_000,maxOutputBytes=8192))
                    if(launch.exitCode!=0 || launch.timedOut || launch.stdout.contains("Error:")) throw CapabilityException("MAPS_LAUNCH_FAILED","Maps could not open the requested driving route")
                }
            }
            val deadline=android.os.SystemClock.elapsedRealtime()+15_000
            var last:CapabilityException?=null
            do {
                try {
                    val (labels,identity)=capture(ctx,selected)
                    val reading=MapsRouteReading.read(labels,System.currentTimeMillis())
                    return@withScreen buildJsonObject {
                        reading.forEach { (k,v)->put(k,v) }
                        put("screenBackend",selected.name);put("screenExecutionIdentity",identity)
                        args["expectedDepartureAtMs"]?.jsonPrimitive?.longOrNull?.let {expected ->
                            val zone=ZoneId.of(args["timezone"]?.jsonPrimitive?.content ?: profile.timezone)
                            val expectedTime=Instant.ofEpochMilli(expected).atZone(zone)
                            fun normalized(s:String)=s.replace(Regex("[\\s\\u00a0\\u202f]+"),"").lowercase()
                            val formatted=expectedTime.format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d, h:mma",java.util.Locale.US))
                            val label=reading["timeSelectionLabel"]?.jsonPrimitive?.content.orEmpty()
                            if(kotlin.math.abs(expected-System.currentTimeMillis())>366L*86_400_000 || normalized(label) !in listOf(normalized("Leave $formatted"),normalized("Depart $formatted")))
                                throw CapabilityException("ROUTE_TIME_MISMATCH","Maps' visible departure label does not match the requested departure. Use its actual time controls, inspect the selected date/time, then read again.")
                            put("forecastDepartureTimeVerified",true);put("evaluatedDepartureAtMs",expected)
                        }
                        if(open) {put("requestedDestination",destination);put("routeOpened",true);put("destinationAddressFullyVerified",false)}
                        val destinationMatches=expectedDestination?.let {MapsRouteReading.destinationMatches(it,reading.getValue("destinationLabel").jsonPrimitive.content)}
                        put("destinationBinding",when(destinationMatches) {true->"EXPECTED_LABEL_MATCHED";false->"DESTINATION_MISMATCH";null->"DESTINATION_CONFIRMATION_REQUIRED"})
                        put("destinationAddressFullyVerified",false)
                        put("timing",if(destinationMatches==true) CommuteTiming.calculate(profile,args,reading.getValue("durationSeconds").jsonPrimitive.double,System.currentTimeMillis())
                            else buildJsonObject {put("status",if(destinationMatches==false) "DESTINATION_MISMATCH" else "DESTINATION_CONFIRMATION_REQUIRED");put("guidance","Confirm the intended destination on screen and supply expectedDestinationLabel. Never substitute Maps' saved Work shortcut for the owner's explicit destination. Timing advice withheld.")})
                    }
                } catch(e:CancellationException) {throw e}
                catch(e:CapabilityException) {
                    if(e.code !in setOf("ROUTE_SCREEN_UNREADABLE","ROUTE_CONTEXT_UNVERIFIED","TARGET_NOT_FOREGROUND","NO_ACTIVE_WINDOW")) throw e
                    last=e
                }
                if(!open) break
                delay(400)
            } while(android.os.SystemClock.elapsedRealtime()<deadline)
            throw last ?: CapabilityException("ROUTE_SCREEN_UNREADABLE","Read the actual Maps screen before calculating timing")
        }
    }
}

fun registerCommuteCapabilities(g:RuntimeGraph) {
    val location=DeviceLocation(g.context)
    val router=CommuteRouter(g)
    g.registry.register(object:Capability {
        override val descriptor=CapabilityDescriptor("location.current",title="Current phone location",description="Fresh native Android fix with age, accuracy and precision. Requires Android location consent and may require SIConnect foreground; no saved weather point is substituted.",inputSchema=objectSchema(),backend=BackendKind.NATIVE,risk=RiskClass.READ_ONLY,interaction=InteractionMode.BACKGROUND)
        override fun availability()=if(location.permitted()) Availability.IMPLEMENTED else Availability.PERMISSION_REQUIRED
        override fun availabilityDetail()="Allow location in SIConnect Settings; Android can require the app in foreground"
        override suspend fun execute(args:JsonObject,ctx:ExecutionContext)=CapabilityResult(location.current(),BackendKind.NATIVE,"uid=${android.os.Process.myUid()}")
    })
    g.registry.register(object:Capability {
        override val descriptor=CapabilityDescriptor("commute.profile",title="Saved commute preferences",description="Read owner-saved destination, timezone, optional arrival/wake times and preparation/buffer minutes. No current-location inference.",inputSchema=objectSchema(),backend=BackendKind.NATIVE,risk=RiskClass.READ_ONLY,interaction=InteractionMode.BACKGROUND)
        override fun availability()=Availability.IMPLEMENTED
        override suspend fun execute(args:JsonObject,ctx:ExecutionContext)=CapabilityResult(AppJson.encodeToJsonElement(CommuteProfile.serializer(),g.settings.commute),BackendKind.NATIVE,"uid=${android.os.Process.myUid()}")
    })
    for(open in listOf(true,false)) g.registry.register(object:Capability {
        override val descriptor get()=CapabilityDescriptor(if(open) "commute.route" else "commute.read_route",title=if(open) "Open and read Maps commute" else "Read selected Maps route",
            description=if(open) "Opens Google Maps driving directions from Maps' current location (or explicit origin address/coordinates) to the destination and reads the visible selected route. No API key. Shared screen must be unlocked. Uses accessibility when connected, otherwise the owner's selected privileged service. Does not start navigation. For tomorrow, use real UI controls to Set depart or arrive time and then call commute.read_route; a current route is not tomorrow's prediction."
            else "Read duration, distance, endpoint labels and traffic wording from the actual foreground Maps driving-route screen. Use UI tools to select a future departure/arrival time first. Range estimates use their upper bound for leave-by timing. Confirm date, endpoints and selected mode on screen; no API request, guessed travel speed or alarm changes.",
            inputSchema=objectSchema {
                if(open) {string("destination","Address; otherwise owner-saved commute destination",maxLength=500);string("origin","Starting address or latitude,longitude; omit for Maps current location",maxLength=500)}
                else integer("expectedDepartureAtMs","Require visible Maps departure label to match this epoch time; mismatch refuses timing advice",min=1)
                string("expectedDestinationLabel","Confirmed destination name to match on screen before timing advice; otherwise saved name or destination prefix. Never replace the owner's address with Maps saved Work.",maxLength=200)
                string("leaseId","Optional existing owned Maps screen lease",maxLength=100)
                integer("arrivalAtMs","Desired arrival epoch milliseconds for timing arithmetic",min=1)
                string("targetDate","Local arrival date YYYY-MM-DD for saved arrival time; otherwise today",maxLength=10)
                string("timezone","IANA timezone; otherwise profile timezone",maxLength=100)
                integer("bufferMinutes","Extra arrival buffer",min=0,max=180)
                integer("preparationMinutes","Waking to departure preparation",min=0,max=360)
            },backend=router.backend(),risk=if(open) RiskClass.MODERATE_IMPACT else RiskClass.READ_ONLY,
            interaction=InteractionMode.SCREEN,timeoutMs=40_000,sideEffects=if(open) listOf("Opens Google Maps on the shared phone screen; sends route endpoints to Maps") else emptyList())
        override fun availability()=router.availability()
        override fun availabilityDetail()="Unlock the phone and connect SIConnect accessibility or its selected bundled/Shizuku service. Scope and shared-screen lease are enforced."
        override suspend fun execute(args:JsonObject,ctx:ExecutionContext):CapabilityResult {
            val selected=router.backend()
            val result=router.calculate(args,ctx,open)
            return CapabilityResult(result,selected,result.getValue("screenExecutionIdentity").jsonPrimitive.content,VerificationStatus.VERIFIED,"Duration and distance read from the actual Maps route screen; complete destination address requires owner confirmation. See forecastDepartureTimeVerified for time-label verification.")
        }
    })
}

