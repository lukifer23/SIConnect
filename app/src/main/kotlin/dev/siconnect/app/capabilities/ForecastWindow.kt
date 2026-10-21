package dev.siconnect.app.capabilities

import dev.siconnect.core.CapabilityException
import kotlinx.serialization.json.*
import java.time.*

/** Open-Meteo accumulation/probability timestamps describe the preceding hour. */
object ForecastWindow {
    fun bounds(date:String,zone:String,startHour:Int,endHour:Int):Pair<Instant,Instant> {
        require(startHour in 0..23 && endHour in 1..24 && startHour<endHour)
        val day=LocalDate.parse(date);val tz=ZoneId.of(zone)
        val start=day.atTime(startHour,0).atZone(tz).toInstant()
        val end=(if(endHour==24) day.plusDays(1).atStartOfDay(tz) else day.atTime(endHour,0).atZone(tz)).toInstant()
        require(end>start) {"Window is empty after timezone/DST resolution"}
        return start to end
    }
    fun summarize(body:JsonObject,start:Instant,end:Instant):JsonObject {
        val hourly=body["hourly"] as? JsonObject ?: throw CapabilityException("WEATHER_DATA","Provider omitted hourly data")
        val times=(hourly["time"] as? JsonArray)?.map {it.jsonPrimitive.longOrNull ?: throw CapabilityException("WEATHER_DATA","Unix timestamps required")}
            ?: throw CapabilityException("WEATHER_DATA","Provider omitted timestamps")
        require(times.size<=400 && times.zipWithNext().all {(a,b)->b>a}) {"Invalid forecast time axis"}
        val instantRows=times.indices.filter {times[it]>=start.epochSecond && times[it]<end.epochSecond}
        val accumulationRows=times.indices.filter {times[it]>start.epochSecond && times[it]<=end.epochSecond}
        if(instantRows.isEmpty() || accumulationRows.isEmpty()) throw CapabilityException("FORECAST_WINDOW_UNAVAILABLE","Provider does not cover the requested window")
        fun values(key:String,rows:List<Int>)=rows.mapNotNull {index -> (hourly[key] as? JsonArray)?.getOrNull(index)?.jsonPrimitive?.doubleOrNull?.takeIf(Double::isFinite)}
        val rain=values("precipitation_probability",accumulationRows)
        val snow=values("snowfall",accumulationRows)
        val precipitation=values("precipitation",accumulationRows)
        val temperature=values("temperature_2m",instantRows)
        val codes=values("weather_code",instantRows)
        val expected=Duration.between(start,end).toHours().toInt()
        val complete=instantRows.size==expected && accumulationRows.size==expected &&
            listOf(rain,snow,precipitation).all {it.size==expected} && temperature.size==expected && codes.size==expected
        return buildJsonObject {
            put("windowStartMs",start.toEpochMilli());put("windowEndMs",end.toEpochMilli())
            put("expectedHours",expected);put("instantSamples",instantRows.size);put("accumulationSamples",accumulationRows.size)
            put("completeness",if(complete) "COMPLETE" else "PARTIAL")
            put("maxRainProbability",rain.maxOrNull()?.let(::JsonPrimitive) ?: JsonNull)
            // Partial sums are explicitly incomplete, never presented as a known zero.
            put("expectedSnowCm",snow.takeIf {it.size==expected}?.sum()?.let(::JsonPrimitive) ?: JsonNull)
            put("precipitationMm",precipitation.takeIf {it.size==expected}?.sum()?.let(::JsonPrimitive) ?: JsonNull)
            put("minimumTemperatureC",temperature.minOrNull()?.let(::JsonPrimitive) ?: JsonNull)
            put("weatherCodes",JsonArray(codes.distinct().map {JsonPrimitive(it.toInt())}))
            put("accumulationSemantics","preceding-hour endpoints within (start,end]; temperatures/codes within [start,end)")
            put("units",buildJsonObject {put("temperature","C");put("snowfall","cm");put("precipitation","mm");put("probability","percent")})
        }
    }
}
