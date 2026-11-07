package dev.siconnect.app.ui

import android.Manifest
import android.app.TimePickerDialog
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import androidx.compose.ui.platform.LocalLocale
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.siconnect.app.graph
import dev.siconnect.app.backend.DeviceLocation
import dev.siconnect.core.*
import dev.siconnect.app.data.CommuteProfile
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

@Composable internal fun MainActivity.CommuteSettings() {
    val locale=LocalLocale.current.platformLocale
    val g=graph
    val scope=rememberCoroutineScope()
    val initial=remember {g.settings.commute}
    var destination by rememberSaveable {mutableStateOf(initial.destination)}
    var destinationLabel by rememberSaveable {mutableStateOf(initial.destinationLabel)}
    var zone by rememberSaveable {mutableStateOf(initial.timezone)}
    var wake by rememberSaveable {mutableStateOf(initial.wakeTime)}
    var arrival by rememberSaveable {mutableStateOf(initial.arrivalTime)}
    var preparation by rememberSaveable {mutableStateOf(initial.preparationMinutes?.toString() ?: "")}
    var buffer by rememberSaveable {mutableStateOf(initial.bufferMinutes?.toString() ?: "")}
    var latitude by rememberSaveable {mutableStateOf(g.settings.weather.latitude?.toString() ?: "")}
    var longitude by rememberSaveable {mutableStateOf(g.settings.weather.longitude?.toString() ?: "")}
    // Live route responses stay out of saved instance state.
    var busy by remember {mutableStateOf(false)}
    var status by remember {mutableStateOf("")}
    var route by remember {mutableStateOf<JsonObject?>(null)}
    val revision=permissionRevision
    val locationPermitted=remember(revision) {DeviceLocation(this).permitted()}
    fun runAction(action:suspend ()->Unit) {
        if(busy) return
        busy=true;status=""
        scope.launch {
            try {action()}
            catch(e:CancellationException) {throw e}
            catch(e:Exception) {status=e.message ?: "Action failed"}
            finally {busy=false}
        }
    }
    fun minutes(raw:String,label:String):Int? = raw.takeIf(String::isNotBlank)?.let {value->
        requireNotNull(value.toIntOrNull()) {"Enter $label as whole minutes."}
    }
    fun profile()=CommuteProfile(destination,zone,wake,arrival,minutes(preparation,"preparation"),minutes(buffer,"arrival buffer"),destinationLabel).validated()
    fun coordinate(raw:String)=raw.trim().replace(',','.').toDoubleOrNull()?.takeIf {it.isFinite()}
    val profileCheck=runCatching {profile()}
    val changed=profileCheck.getOrNull()?.let {it!=g.settings.commute} ?: true
    val forecastLat=coordinate(latitude)
    val forecastLon=coordinate(longitude)
    val validForecast=forecastLat!=null && forecastLat in -90.0..90.0 && forecastLon!=null && forecastLon in -180.0..180.0
    fun pickTime(value:String,update:(String)->Unit) {
        val current=runCatching {LocalTime.parse(value)}.getOrElse {LocalTime.now().withSecond(0).withNano(0)}
        TimePickerDialog(this@CommuteSettings,{_,hour,minute->update(LocalTime.of(hour,minute).format(DateTimeFormatter.ofPattern("HH:mm")))},
            current.hour,current.minute,android.text.format.DateFormat.is24HourFormat(this@CommuteSettings)).show()
    }
    @Composable fun timePreference(label:String,value:String,onChange:(String)->Unit) {
        Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(label,style=MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedButton(onClick={pickTime(value,onChange)},enabled=!busy,modifier=Modifier.weight(1f)) {
                    Text(runCatching {LocalTime.parse(value).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))}.getOrDefault("Choose a time"))
                }
                if(value.isNotBlank()) TextButton(onClick={onChange("")},enabled=!busy) {Text("Clear")}
            }
        }
    }
    SectionHeading("Your regular commute")
    Text("Routes use a real starting location and destination. Forecast coordinates below are independent of your commute origin.",style=MaterialTheme.typography.bodySmall)
    if(changed) StatusPill("Unsaved commute changes",StatusTone.ATTENTION)
    OutlinedTextField(destination,{destination=it.take(500)},label={Text("Destination address")},modifier=Modifier.fillMaxWidth(),enabled=!busy)
    OutlinedTextField(destinationLabel,{destinationLabel=it.take(200)},label={Text("Expected Maps destination name · optional")},modifier=Modifier.fillMaxWidth(),enabled=!busy)
    ExpandablePanel("Timing preferences","Arrival, wake time, preparation, and timezone") {
    OutlinedTextField(zone,{zone=it},label={Text("Timezone · IANA")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy)
    timePreference("Arrive by · optional",arrival) {arrival=it}
    timePreference("Usual wake time · optional",wake) {wake=it}
    OutlinedTextField(preparation,{preparation=it.filter(Char::isDigit).take(3)},label={Text("Preparation · minutes")},
        supportingText={Text("0–360 minutes before leaving")},isError=preparation.isNotBlank() && preparation.toIntOrNull()?.let {it in 0..360}!=true,
        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy)
    OutlinedTextField(buffer,{buffer=it.filter(Char::isDigit).take(3)},label={Text("Arrival buffer · minutes")},
        supportingText={Text("0–180 minutes before arrival")},isError=buffer.isNotBlank() && buffer.toIntOrNull()?.let {it in 0..180}!=true,
        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy)
    }
    if(profileCheck.isFailure && changed) Text(profileCheck.exceptionOrNull()?.message ?: "Review the timing preferences.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
    OutlinedButton(enabled=!busy && changed && profileCheck.isSuccess,onClick={runAction {
        val value=profile()
        withContext(Dispatchers.IO) {g.settings.commute=value}
        route=null;status="Commute profile saved. Grant commute access separately for each agent."
    }}) {Text("Save commute")}
    if(!locationPermitted) OutlinedButton(enabled=!busy,onClick={requestNativeAccess(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))}) {Text("Allow current location")}
    Text(if(locationPermitted) "Android location access allowed; foreground access only." else "Allow location for the phone's current starting point. Agents can also supply explicit origin coordinates.",style=MaterialTheme.typography.bodySmall)
    Text("Commute calculation opens Maps and reads its visible route using SIConnect accessibility or the selected bundled/Shizuku service. No routing API key is needed. The phone must be unlocked.",style=MaterialTheme.typography.bodySmall)
    Button(enabled=!busy && destination.isNotBlank() && profileCheck.isSuccess,onClick={
        // Freeze the form before authenticating. The engine owns dispatch and the durable
        // outcome even when Maps backgrounds this screen or the Activity is recreated.
        val value=runCatching {profile()}.getOrElse {status=it.message ?: "Check commute preferences";return@Button}
        ownerAuthentication.authenticate("Open and read Maps route",{runAction {
            route=null
            val result=withContext(Dispatchers.IO) {
                g.ready.await()
                g.settings.commute=value
                val request=g.engine.submit(InvocationRequest(agentId="local-owner",capabilityId="commute.route",
                    arguments=buildJsonObject {
                        put("destination",value.destination);put("timezone",value.timezone)
                        if(value.destinationLabel.isNotBlank()) put("expectedDestinationLabel",value.destinationLabel)
                    }),Origin.LOCAL_OWNER)
                if(request.state==RequestState.AWAITING_APPROVAL)
                    g.approveFromOwner(request.requestId,request.argsHash) else request
            }
            check(result.state==RequestState.SUCCEEDED) {
                "${result.errorMessage ?: result.state.name}. Request ${result.requestId} is retained in Activity; inspect it before retrying."
            }
            route=result.result?.output as? JsonObject
            status="Maps route read. Request ${result.requestId} is saved in Activity. No navigation, alarms or reminders started."
        }},{status=it})
    }) {Text(if(busy) "Reading Maps…" else "Calculate with Maps")}
    route?.let {r ->
        val duration=(r["durationMinutes"] as? JsonPrimitive)?.contentOrNull
        val distance=(r["distanceMeters"] as? JsonPrimitive)?.doubleOrNull?.div(1000.0)
        Text(listOfNotNull(duration?.let {"$it min"},distance?.let {String.format(locale,"%.1f km",it)},"Maps").joinToString(" · "),style=MaterialTheme.typography.titleMedium)
        Text(r["trafficStatus"]?.jsonPrimitive?.content.orEmpty(),style=MaterialTheme.typography.bodySmall)
        val timing=r["timing"]?.jsonObject
        if(r["destinationBinding"]?.jsonPrimitive?.content!="EXPECTED_LABEL_MATCHED") Text("Confirm the destination name in Maps and enter its expected name above. Timing advice is withheld when it does not match.",color=MaterialTheme.colorScheme.error)
        timing?.get("leaveByLocal")?.let {Text("Leave by ${it.jsonPrimitive.content}")}
        timing?.get("wakeByLocal")?.let {Text("Wake by ${it.jsonPrimitive.content}")}
        timing?.get("earlierWakeMinutes")?.let {Text("Earlier than saved wake time: ${it.jsonPrimitive.content} min")}
        if(timing?.get("departureAlreadyPassed")?.jsonPrimitive?.booleanOrNull==true) Text("This arrival target requires a departure time that has already passed.",color=MaterialTheme.colorScheme.error)
        r["timeSelectionLabel"]?.let {Text(it.jsonPrimitive.content)}
        Text("Recalculate near departure. For tomorrow, use Maps’ Set depart or arrive time and verify its date. Maps may retain a previous time selection.",style=MaterialTheme.typography.bodySmall)
    }
    TextButton(enabled=!busy && destination.isNotBlank(),onClick={
        val uri=Uri.parse("https://www.google.com/maps/dir/").buildUpon().appendQueryParameter("api","1")
            .appendQueryParameter("destination",destination.trim()).appendQueryParameter("travelmode","driving").build()
        try {startActivity(Intent(Intent.ACTION_VIEW,uri));status="Maps opened. Its displayed route and traffic have not been read back by SIConnect."}
        catch(e:android.content.ActivityNotFoundException) {status="No app available to open Maps directions"}
    }) {Text("Open route in Maps")}
    if(status.isNotBlank()) Text(status,style=MaterialTheme.typography.bodySmall)
    SectionHeading("Weather forecast point")
    OutlinedTextField(latitude,{latitude=it.take(32)},label={Text("Latitude")},supportingText={Text("−90 to 90")},isError=latitude.isNotBlank() && (forecastLat==null || forecastLat !in -90.0..90.0),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Text),singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy)
    OutlinedTextField(longitude,{longitude=it.take(32)},label={Text("Longitude")},supportingText={Text("−180 to 180; west is negative")},isError=longitude.isNotBlank() && (forecastLon==null || forecastLon !in -180.0..180.0),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Text),singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy)
    OutlinedButton(enabled=!busy && locationPermitted,onClick={runAction {
        val result=withContext(Dispatchers.IO) {
            g.ready.await()
            g.engine.submit(InvocationRequest(agentId="local-owner",capabilityId="location.current"),Origin.LOCAL_OWNER)
        }
        check(result.state==RequestState.SUCCEEDED) {result.errorMessage ?: "Current location could not be read"}
        val fix=requireNotNull(result.result?.output as? JsonObject) {"Location result is unavailable"}
        latitude=fix.getValue("latitude").jsonPrimitive.content;longitude=fix.getValue("longitude").jsonPrimitive.content
        status="Current location filled; accuracy ${fix["accuracyMeters"]?.jsonPrimitive?.content} m. Save to use it as your forecast point."
    }}) {Text("Use current location for forecast")}
    OutlinedButton(enabled=!busy && validForecast,onClick={runAction {
        val lat=requireNotNull(forecastLat);val lon=requireNotNull(forecastLon)
        require(lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) {"Enter valid latitude and longitude"}
        withContext(Dispatchers.IO) {g.settings.weather=g.settings.weather.copy(latitude=lat,longitude=lon)}
        status="Forecast point saved"
    }}) {Text("Save forecast point")}
    Text("Forecasts: Open-Meteo, CC BY 4.0. Free endpoint for personal/non-commercial use.",style=MaterialTheme.typography.bodySmall)
}
