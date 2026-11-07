package dev.siconnect.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable
data class StartupStage(val id:String,val title:String,val required:Boolean,val state:String="PENDING",val attempts:Int=0,
    val attemptedAtMs:Long?=null,val completedAtMs:Long?=null,val errorType:String?=null)

internal class StartupStep(val id:String,val title:String,val required:Boolean,val run:suspend ()->Unit)

/** Essential authority recovery runs once per process. Only named failed deferred tasks can retry. */
class RuntimeStartup internal constructor(private val steps:List<StartupStep>) {
    private val gate=Mutex()
    private var started=false
    private val mutable=MutableStateFlow(steps.map {StartupStage(it.id,it.title,it.required)})
    val stages=mutable.asStateFlow()
    init {require(steps.map {it.id}.distinct().size==steps.size)}
    private suspend fun perform(step:StartupStep) {
        mutable.update {rows->rows.map {if(it.id==step.id) it.copy(state="RUNNING",attempts=it.attempts+1,
            attemptedAtMs=System.currentTimeMillis(),completedAtMs=null,errorType=null) else it}}
        try {
            step.run()
            mutable.update {rows->rows.map {if(it.id==step.id) it.copy(state="READY",completedAtMs=System.currentTimeMillis()) else it}}
        } catch(e:CancellationException) {
            mutable.update {rows->rows.map {if(it.id==step.id) it.copy(state="INTERRUPTED",errorType=e.javaClass.simpleName) else it}}
            throw e
        } catch(e:Exception) {
            mutable.update {rows->rows.map {if(it.id==step.id) it.copy(state="FAILED",completedAtMs=System.currentTimeMillis(),errorType=e.javaClass.simpleName) else it}}
            if(step.required) throw e
        }
    }
    suspend fun start()=gate.withLock {
        check(!started) {"Startup already attempted; essential authority recovery cannot replay"}
        started=true
        for(step in steps) perform(step)
    }
    suspend fun retryDeferred():List<String> = gate.withLock {
        check(started && stages.value.filter {it.required}.all {it.state=="READY"}) {"Essential startup requires a new process; saved data is preserved"}
        val ids=stages.value.filter {!it.required && it.state in setOf("FAILED","INTERRUPTED")}.map {it.id}.toSet()
        steps.filter {it.id in ids}.forEach {perform(it)}
        ids.toList()
    }
}
