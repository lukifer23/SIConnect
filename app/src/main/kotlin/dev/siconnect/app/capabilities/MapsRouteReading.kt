package dev.siconnect.app.capabilities

import dev.siconnect.core.CapabilityException
import kotlinx.serialization.json.*

/** Bounded visible labels, never text from an API or a guessed driving speed. */
data class RouteLabel(val text:String,val description:String="",val top:Int=0,val bottom:Int=0)

object MapsRouteReading {
    fun destinationMatches(expected:String,observed:String):Boolean {
        fun normalized(s:String)=s.lowercase().filter(Char::isLetterOrDigit)
        return normalized(expected).isNotEmpty() && normalized(expected)==normalized(observed)
    }
    private val distance=Regex("\\(?((?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]+)?)\\s*(mi|km|m)\\)?",RegexOption.IGNORE_CASE)
    private val segment=Regex("([0-9]+)\\s*(hours?|hrs?|h|minutes?|mins?|m)(?=\\s|$)",RegexOption.IGNORE_CASE)

    private fun duration(value:String):Long? {
        val text=value.trim().lowercase().removePrefix("typically ").trim()
        val matches=segment.findAll(text).toList()
        if(matches.isEmpty() || segment.replace(text,"").isNotBlank()) return null
        return matches.sumOf {it.groupValues[1].toLong() * if(it.groupValues[2].startsWith("h")) 3600 else 60}
            .takeIf {it in 1..604800}
    }

    /** Returns an upper bound for ranges so leave-by guidance does not use the optimistic endpoint. */
    fun durationRange(value:String):Pair<Long,Long>? {
        val parts=value.trim().removePrefix("Typically ").split(Regex("\\s*(?:–|—|\\bto\\b)\\s*"))
        val parsed=parts.map {duration(it) ?: return null}
        return when(parsed.size) {1 -> parsed[0] to parsed[0];2 -> parsed[0].takeIf {it<=parsed[1]}?.let {it to parsed[1]};else -> null}
    }

    fun read(labels:List<RouteLabel>,now:Long):JsonObject {
        val pairs=labels.mapNotNull {d ->
            val match=distance.matchEntire(d.text.replace('\u00a0',' ').trim()) ?: return@mapNotNull null
            val times=labels.filter {kotlin.math.abs((it.top+it.bottom)/2-(d.top+d.bottom)/2)<=24}
                .mapNotNull {t->durationRange(t.text)?.let {Triple(t,it.first,it.second)}}
            if(times.size!=1) return@mapNotNull null
            val value=match.groupValues[1].replace(",","").toDouble()
            val meters=value*when(match.groupValues[2].lowercase()) {"mi"->1609.344;"km"->1000.0;else->1.0}
            if(meters !in 0.0..40_100_000.0) return@mapNotNull null
            Triple(times.single(),d,meters)
        }.distinctBy {listOf(it.first.first.text,it.second.text,it.first.first.top)}
        if(pairs.size!=1) throw CapabilityException("ROUTE_SCREEN_UNREADABLE","Expected one selected route with adjacent duration and distance. Inspect the actual Maps screen using UI tools or a screenshot; do not guess between alternative routes or travel modes.")
        val (time,distanceLabel,meters)=pairs.single()
        val origin=labels.firstOrNull {it.description.startsWith("Start location,")}
        val destination=labels.firstOrNull {it.description.startsWith("Destination,")}
        if(origin==null || destination==null || labels.none {it.text=="Drive" || it.description=="Drive"})
            throw CapabilityException("ROUTE_CONTEXT_UNVERIFIED","Driving route endpoints are not visible; inspect Maps and confirm the starting point, destination and travel mode")
        val evidence=labels.flatMap {listOf(it.text,it.description)}.filter {it.isNotBlank() &&
            (it.startsWith("Best route ") && it.contains("traffic",true) || it.startsWith("Traffic ") ||
                it.startsWith("Depart ") || it.startsWith("Arrive ") || it.startsWith("Leave "))}.distinct().take(12)
        val timeSelection=labels.map {it.text}.firstOrNull {it.startsWith("Leave ") || it.startsWith("Depart ")}
        return buildJsonObject {
            put("provider","GOOGLE_MAPS_SCREEN");put("travelMode","DRIVE")
            put("originLabel",origin.text.ifBlank {origin.description});put("destinationLabel",destination.text.ifBlank {destination.description})
            put("originSource",if(origin.text=="Your location") "MAPS_CURRENT_LOCATION" else "MAPS_VISIBLE_ORIGIN")
            put("originFixAgeKnown",false);put("originAccuracyKnown",false)
            put("durationLabel",time.first.text);put("distanceLabel",distanceLabel.text)
            put("minimumDurationSeconds",time.second);put("durationSeconds",time.third);put("durationMinutes",(time.third+59)/60)
            put("durationBasis",if(time.second==time.third) "VISIBLE_ESTIMATE" else "VISIBLE_RANGE_UPPER_BOUND")
            put("distanceMeters",meters);put("screenEvidence",JsonArray(evidence.map(::JsonPrimitive)))
            put("trafficStatus",if(timeSelection!=null && time.first.text.startsWith("Typically ")) "PREDICTED_TRAVEL_TIME_RANGE"
                else if(evidence.any {it.contains("traffic",true)}) "MAPS_TRAFFIC_TEXT_VISIBLE" else "TRAFFIC_NOT_CONFIRMED_ON_SCREEN")
            timeSelection?.let {put("timeSelectionLabel",it)}
            put("observedAtMs",now);put("expiresAtMs",now+120_000)
            put("forecastDepartureTimeVerified",false)
            put("guidance","Visible Maps route estimate only. Check screenEvidence and the selected departure/arrival date before applying it to tomorrow. Current location is Maps' label; fix age/accuracy are not exposed here. No traffic-delay baseline, alarm change, reminder creation or navigation start is inferred.")
        }
    }
}
