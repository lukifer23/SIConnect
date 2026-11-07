package dev.siconnect.app.clocks

import java.util.Locale

/** Exact managed ownership, never a best-effort match against an unrelated user timer. */
internal object ClockContract {
    const val SAMSUNG_CLOCK="com.sec.android.app.clockpackage"
    const val STOP_SEMANTIC=15
    fun publishedLabel(label:String,timerId:String):String {
        require(label.length<=80 && label.none {it=='\u0000' || it=='\n' || it=='\r'}) {"Use a single timer label up to80 characters"}
        require(timerId.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))) {"Invalid managed timer identity"}
        return label.trim().takeIf {it.isNotEmpty()}?.let {"$it · SIConnect:$timerId"} ?: "SIConnect:$timerId"
    }
    fun matchesLabel(packageName:String,publishedLabel:String,fields:List<String>,samsungSecondary:String?):Boolean=
        if(packageName==SAMSUNG_CLOCK) samsungSecondary?.startsWith("$publishedLabel / ")==true && samsungSecondary.length>publishedLabel.length+3
        else fields.any {it==publishedLabel}
    fun cancelMeaning(packageName:String,semantic:Int,label:String):Boolean = semantic==STOP_SEMANTIC ||
        (packageName==SAMSUNG_CLOCK && label.trim().lowercase(Locale.ROOT) in setOf("cancel","stop"))
    fun timerIntentArgv(packageName:String,durationSeconds:Int,label:String):List<String> {
        require(packageName.length<=200 && packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) {"Use an exact Clock package"}
        require(durationSeconds in 1..86400) {"Timer duration must be1..86400 seconds"}
        require(label.length<=160 && '\u0000' !in label) {"Timer label exceeds its bound"}
        return listOf("/system/bin/am","start","--user","0","-a","android.intent.action.SET_TIMER","-p",packageName,
            "--ei","android.intent.extra.alarm.LENGTH",durationSeconds.toString(),"--es","android.intent.extra.alarm.MESSAGE",label,
            "--ez","android.intent.extra.alarm.SKIP_UI","true")
    }
}
