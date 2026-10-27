package dev.siconnect.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.siconnect.app.graph
import dev.siconnect.app.backend.DeviceLocation
import dev.siconnect.app.capabilities.CommuteRouter
import dev.siconnect.core.ExecutionContext
import dev.siconnect.app.data.CommuteProfile
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

@Composable internal fun MainActivity.CommuteSettings() {
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
    fun profile()=CommuteProfile(destination,zone,wake,arrival,preparation.takeIf(String::isNotBlank)?.toInt(),buffer.takeIf(String::isNotBlank)?.toInt(),destinationLabel).validated()
    Text("Commute",style=MaterialTheme.typography.titleMedium)
    Text("Routes use a real starting location and destination. Forecast coordinates below are independent of your commute origin.",style=MaterialTheme.typography.bodySmall)
    OutlinedTextField(destination,{destination=it},label={Text("Destination address")},modifier=Modifier.fillMaxWidth(),enabled=!busy)
    OutlinedTextField(destinationLabel,{destinationLabel=it},label={Text("Expected Maps destination name · optional")},modifier=Modifier.fillMaxWidth(),enabled=!busy)
    OutlinedTextField(zone,{zone=it},label={Text("Timezone · IANA")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy)
    OutlinedTextField(arrival,{arrival=it},label={Text("Arrive by · HH:mm · optional")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy)
    OutlinedTextField(wake,{wake=it},label={Text("Usual wake time · HH:mm · optional")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy)
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(preparation,{preparation=it},label={Text("Preparation · min")},singleLine=true,modifier=Modifier.weight(1f),enabled=!busy)
        OutlinedTextField(buffer,{buffer=it},label={Text("Arrival buffer · min")},singleLine=true,modifier=Modifier.weight(1f),enabled=!busy)
    }
    OutlinedButton(enabled=!busy,onClick={runAction {
        val value=profile()
        withContext(Dispatchers.IO) {g.settings.commute=value}
        route=null;status="Commute profile saved. Grant commute access separately for each agent."
    }}) {Text("Save commute")}
    if(!locationPermitted) OutlinedButton(enabled=!busy,onClick={requestNativeAccess(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))}) {Text("Allow current location")}
    Text(if(locationPermitted) "Android location access allowed; foreground access only." else "Allow location for the phone's current starting point. Agents can also supply explicit origin coordinates.",style=MaterialTheme.typography.bodySmall)
    Text("Commute calculation opens Maps and reads its visible route using SIConnect accessibility or the selected bundled/Shizuku service. No routing API key is needed. The phone must be unlocked.",style=MaterialTheme.typography.bodySmall)
    Button(enabled=!busy && destination.isNotBlank(),onClick={runAction {
        val value=profile()
        withContext(Dispatchers.IO) {g.settings.commute=value}
        route=null
        val id="owner-commute-"+java.util.UUID.randomUUID().toString()
        route=CommuteRouter(g).calculate(buildJsonObject {put("destination",value.destination)},ExecutionContext(id,id,"local-owner",g.settings.deviceId))
        status="Maps route read. No navigation, alarms or reminders started."
    }}) {Text(if(busy) "Reading Maps…" else "Calculate with Maps")}
    route?.let {r ->
        Text("${r["durationMinutes"]?.jsonPrimitive?.content} min · ${r["distanceMeters"]?.jsonPrimitive?.double?.div(1000.0)} km · Maps")
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
    Text("Weather forecast point",style=MaterialTheme.typography.titleMedium)
    OutlinedTextField(latitude,{latitude=it},label={Text("Latitude")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy)
    OutlinedTextField(longitude,{longitude=it},label={Text("Longitude")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy)
    OutlinedButton(enabled=!busy && locationPermitted,onClick={runAction {
        val fix=DeviceLocation(this@CommuteSettings).current()
        latitude=fix.getValue("latitude").jsonPrimitive.content;longitude=fix.getValue("longitude").jsonPrimitive.content
        status="Current location filled; accuracy ${fix["accuracyMeters"]?.jsonPrimitive?.content} m. Save to use it as your forecast point."
    }}) {Text("Use current location for forecast")}
    OutlinedButton(enabled=!busy && latitude.isNotBlank() && longitude.isNotBlank(),onClick={runAction {
        val lat=latitude.toDouble();val lon=longitude.toDouble()
        require(lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) {"Enter valid latitude and longitude"}
        withContext(Dispatchers.IO) {g.settings.weather=g.settings.weather.copy(latitude=lat,longitude=lon)}
        status="Forecast point saved"
    }}) {Text("Save forecast point")}
    Text("Forecasts: Open-Meteo, CC BY 4.0. Free endpoint for personal/non-commercial use.",style=MaterialTheme.typography.bodySmall)
}
