package dev.siconnect.app.backend

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import dev.siconnect.core.CapabilityException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.serialization.json.*

/** One bounded native fix. Never substitutes a configured forecast point for the phone. */
class DeviceLocation(private val context: Context) {
    fun permitted() = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED ||
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED

    suspend fun current(): JsonObject {
        if(!permitted()) throw CapabilityException("LOCATION_PERMISSION_REQUIRED","Allow location in SIConnect Settings, or supply explicit route origin coordinates")
        val manager=context.getSystemService(LocationManager::class.java)
        if(!manager.isLocationEnabled) throw CapabilityException("LOCATION_DISABLED","Enable Android location services")
        val fine=context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
        val providers=listOf(LocationManager.FUSED_PROVIDER,LocationManager.NETWORK_PROVIDER,LocationManager.GPS_PROVIDER)
            .filter { manager.allProviders.contains(it) && manager.isProviderEnabled(it) && (fine || it!=LocationManager.GPS_PROVIDER) }
        if(providers.isEmpty()) throw CapabilityException("LOCATION_UNAVAILABLE","No enabled location provider; enable location or supply origin coordinates")
        val fix=withTimeoutOrNull(20_000) {
            var found: Location?=null
            for(provider in providers) {
                val result=withTimeoutOrNull(6_000) {
                    suspendCancellableCoroutine<Location?> { continuation ->
                        val signal=CancellationSignal()
                        continuation.invokeOnCancellation {signal.cancel()}
                        try { manager.getCurrentLocation(provider,signal,context.mainExecutor) {location -> continuation.resume(location)} }
                        catch(e:SecurityException) {continuation.resumeWithException(CapabilityException("LOCATION_FOREGROUND_REQUIRED","Open SIConnect in the foreground to obtain location, or supply origin coordinates"))}
                    }
                }
                if(result!=null && !result.isMock && result.hasAccuracy() && result.accuracy.isFinite() && result.accuracy>=0 && ageMs(result) in 0..120_000) {found=result;break}
            }
            found
        } ?: throw CapabilityException("LOCATION_UNAVAILABLE","No fresh non-mock fix obtained. Open SIConnect in the foreground, check location access, or supply explicit origin coordinates")
        return buildJsonObject {
            put("latitude",fix.latitude);put("longitude",fix.longitude);put("accuracyMeters",fix.accuracy)
            put("capturedAtMs",fix.time);put("ageMs",ageMs(fix));put("observedAtMs",System.currentTimeMillis())
            put("source","ANDROID_CURRENT_LOCATION");put("precision",if(fine) "PRECISE_PERMISSION" else "APPROXIMATE_PERMISSION")
            put("mock",false)
        }
    }

    private fun ageMs(location: Location)=(SystemClock.elapsedRealtimeNanos()-location.elapsedRealtimeNanos)/1_000_000
}
