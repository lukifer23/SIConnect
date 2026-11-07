package dev.siconnect.app.capabilities.messages

import dev.siconnect.core.Canonical
import kotlinx.serialization.json.*

internal const val MESSAGES_PACKAGE="com.google.android.apps.messaging"
internal data class UiRect(val left:Int,val top:Int,val right:Int,val bottom:Int) {
    fun contains(other:UiRect)=left<=other.left && top<=other.top && right>=other.right && bottom>=other.bottom
    val area:Long get()=(right-left).coerceAtLeast(0).toLong()*(bottom-top).coerceAtLeast(0)
}
internal data class MessageNode(val ref:String,val resource:String,val text:String,val description:String,
    val bounds:UiRect,val enabled:Boolean,val actions:Set<Int>,val directions:Set<String>,val editable:Boolean) {
    val id get()=resource.substringAfterLast('/')
}
internal data class VisibleMessage(val text:String?,val labels:List<String>,val description:String?,val contentType:String,
    val textMayBeTruncated:Boolean) {
    // A visible signature is an overlap anchor, never an SMS/RCS database identity.
    val signature:String get()=Canonical.sha256Hex(toJson().toString())
    fun toJson()=buildJsonObject {
        put("text",text?.let(::JsonPrimitive) ?: JsonNull);put("visibleLabels",JsonArray(labels.map(::JsonPrimitive)))
        put("accessibilityDescription",description?.let(::JsonPrimitive) ?: JsonNull)
        put("contentType",contentType);put("textMayBeTruncated",textMayBeTruncated)
    }
}
internal data class MessageWindow(val snapshotId:String,val nodes:List<MessageNode>,val incomplete:Boolean) {
    fun nodes(id:String)=nodes.filter {it.id==id}
    fun single(id:String)=nodes(id).singleOrNull()
    fun titleMatches(expected:String):Boolean {
        val row=single("top_app_bar_title_row") ?: return false
        return nodes.count {it.text==expected && !it.editable && row.bounds.contains(it.bounds)}==1
    }
    fun hasThread()=single("message_list")!=null && single("top_app_bar_title_row")!=null
    fun conversationMatches(expected:String)=nodes("conversation_name").filter {it.text==expected && it.enabled}
    fun backNode():MessageNode? {
        val bar=single("top_app_bar") ?: return null
        return nodes.filter {it.description in setOf("Back","Navigate up") && it.enabled && bar.bounds.contains(it.bounds)}.singleOrNull()
    }
    fun messages():List<VisibleMessage> {
        val list=single("message_list") ?: return emptyList()
        val title=single("top_app_bar_title_row")
        val header=single("top_app_bar")
        return nodes("message_bubble_context").filter {it.bounds.area>0 && list.bounds.contains(it.bounds)}
            .sortedWith(compareBy<MessageNode> {it.bounds.top}.thenBy {it.bounds.left}).mapNotNull {bubble->
                val contents=nodes.filter {it!==bubble && bubble.bounds.contains(it.bounds) && !it.editable &&
                    !(header?.bounds?.contains(it.bounds) ?: false) && !(title?.bounds?.contains(it.bounds) ?: false)}
                val body=contents.filter {it.id=="message_text"}.sortedBy {it.bounds.top}
                // Multiple text nodes may be one bubble. Preserve their order rather than count
                // each span as a separate message; no contact/sender inference from alignment.
                val texts=body.map {it.text}.filter {it.isNotEmpty()}
                val labels=if(texts.isEmpty()) contents.sortedWith(compareBy<MessageNode> {it.bounds.top}.thenBy {it.bounds.left})
                    .flatMap {listOf(it.text,it.description)}.filter {it.isNotEmpty()}.distinct().take(12) else emptyList()
                VisibleMessage(texts.takeIf {it.isNotEmpty()}?.joinToString("\n"),labels,
                    body.map {it.description}.filter {it.isNotEmpty()}.distinct().takeIf {it.isNotEmpty()}?.joinToString("\n"),
                    if(texts.isEmpty() && labels.isEmpty()) "VISIBLE_UNREADABLE_BUBBLE" else if(texts.isEmpty()) "VISIBLE_NON_TEXT_BUBBLE" else "VISIBLE_TEXT_BUBBLE",
                    body.any {it.text.length>=1000 || it.description.length>=1000} || contents.any {it.text.length>=1000 || it.description.length>=1000})
            }
    }
    fun listSignature()=Canonical.sha256Hex(nodes("conversation_name").joinToString("\u0000") {it.text})
    companion object {
        fun parse(output:JsonObject):MessageWindow {
            require(output["packageName"]?.jsonPrimitive?.content==MESSAGES_PACKAGE &&
                output["targetWindowVerified"]?.jsonPrimitive?.booleanOrNull==true) {"Unverified Messages window"}
            val nodes=output.getValue("nodes").jsonArray.map {raw->
                val n=raw.jsonObject;val b=n.getValue("bounds").jsonObject
                fun str(key:String)=n[key]?.jsonPrimitive?.content.orEmpty()
                MessageNode(str("nodeRef"),str("resourceId"),str("text"),str("description"),
                    UiRect(b.getValue("left").jsonPrimitive.int,b.getValue("top").jsonPrimitive.int,b.getValue("right").jsonPrimitive.int,b.getValue("bottom").jsonPrimitive.int),
                    n["enabled"]?.jsonPrimitive?.booleanOrNull==true,
                    n["actions"]?.jsonArray?.map {it.jsonPrimitive.int}?.toSet().orEmpty(),
                    n["supportedScrollDirections"]?.jsonArray?.map {it.jsonPrimitive.content}?.toSet().orEmpty(),
                    n["editable"]?.jsonPrimitive?.booleanOrNull==true)
            }
            return MessageWindow(output.getValue("snapshotId").jsonPrimitive.content,nodes,output["scanIncomplete"]?.jsonPrimitive?.booleanOrNull!=false)
        }
    }
}
internal data class MessageMerge(val messages:List<VisibleMessage>,val overlap:Int,val ambiguity:String?=null)
internal fun mergeOlderMessages(older:List<VisibleMessage>,current:List<VisibleMessage>):MessageMerge {
    if(current.isEmpty()) return MessageMerge(older,0)
    if(older.isEmpty()) return MessageMerge(current,0,"NO_VISIBLE_BUBBLES")
    val previous=current.map {it.signature};val page=older.map {it.signature}
    if(previous==page) return MessageMerge(current,0,"UNCHANGED_VISIBLE_WINDOW")
    val matches=(1..minOf(page.size,previous.size)).filter {page.takeLast(it)==previous.take(it)}
    if(matches.isEmpty()) return MessageMerge(current,0,"NO_PROVABLE_OVERLAP")
    if(matches.size!=1) return MessageMerge(current,0,"AMBIGUOUS_REPEATED_BUBBLES")
    val overlap=matches.single()
    val anchor=page.takeLast(overlap)
    if(page.windowed(overlap).count {it==anchor}>1 || previous.windowed(overlap).count {it==anchor}>1)
        return MessageMerge(current,0,"AMBIGUOUS_REPEATED_BUBBLES")
    return MessageMerge(older.dropLast(overlap)+current,overlap)
}
