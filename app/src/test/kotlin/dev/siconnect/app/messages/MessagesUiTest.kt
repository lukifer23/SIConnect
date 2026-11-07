package dev.siconnect.app.messages

import dev.siconnect.app.capabilities.messages.*
import dev.siconnect.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Generated semantic JSON exercises the real parser; no app content or substituted executor. */
class MessagesUiTest {
    private fun node(id:String="",text:String="",description:String="",left:Int=0,top:Int=100,right:Int=500,bottom:Int=200,
                     editable:Boolean=false,actions:List<Int> = emptyList(),directions:List<String> = emptyList())=buildJsonObject {
        put("nodeRef","ref-$id-$top-$left");put("resourceId",id);put("text",text);put("description",description)
        put("enabled",true);put("editable",editable);put("actions",JsonArray(actions.map(::JsonPrimitive)))
        put("supportedScrollDirections",JsonArray(directions.map(::JsonPrimitive)))
        putJsonObject("bounds") {put("left",left);put("top",top);put("right",right);put("bottom",bottom)}
    }
    private fun snapshot(vararg nodes:JsonObject,verified:Boolean=true,pkg:String=MESSAGES_PACKAGE)=buildJsonObject {
        put("packageName",pkg);put("targetWindowVerified",verified);put("snapshotId","actual-snapshot-format")
        put("scanIncomplete",false);put("nodes",JsonArray(nodes.toList()))
    }
    private fun bubble(text:String,description:String=text)=VisibleMessage(text,emptyList(),description,"VISIBLE_TEXT_BUBBLE",false)

    @Test fun exactThreadHeaderCannotBeSatisfiedByComposerOrMessageContent() {
        val header=node("top_app_bar_title_row",left=50,top=0,right=300,bottom=60)
        val title=node(text="Team example",left=60,top=5,right=250,bottom=45)
        val list=node("message_list",top=70,bottom=1000)
        val composer=node("compose_message_text",text="Different thread",top=1050,bottom=1100,editable=true)
        val body=node("message_text",text="Different thread",top=200,bottom=250)
        val window=MessageWindow.parse(snapshot(header,title,list,composer,body))
        assertTrue(window.titleMatches("Team example"));assertFalse(window.titleMatches("team example"))
        assertFalse(window.titleMatches("Different thread"));assertFalse(window.titleMatches("Team"))
        val duplicate=MessageWindow.parse(snapshot(header,title,title,list))
        assertFalse(duplicate.titleMatches("Team example"))
    }
    @Test fun onlyVerifiedGoogleMessagesPackageCanBeParsed() {
        for(raw in listOf(snapshot(verified=false),snapshot(pkg="com.android.settings"))) {
            try {MessageWindow.parse(raw);fail("Wrong/protected window accepted")}
            catch(_:IllegalArgumentException) { /* Reject before extracting content. */ }
        }
    }
    @Test fun groupsVisibleBubbleSpansAndPreservesTwoIdenticalRealBubbles() {
        val list=node("message_list",top=70,bottom=1000)
        val one=node("message_bubble_context",top=100,bottom=250)
        val two=node("message_bubble_context",top=300,bottom=450)
        val page=MessageWindow.parse(snapshot(list,one,two,
            node("message_text",text="Repeated visible content",top=110,bottom=150),
            node("message_text",text="Second span",top=160,bottom=200),
            node("message_text",text="Repeated visible content",top=310,bottom=350),
            node("message_text",text="Second span",top=360,bottom=400),
            node("compose_message_text",text="Unsent draft must not become a message",top=1050,bottom=1100,editable=true)))
        assertEquals(2,page.messages().size)
        assertEquals("Repeated visible content\nSecond span",page.messages()[0].text)
        assertEquals(page.messages()[0].text,page.messages()[1].text)
    }
    @Test fun attachmentsUnreadableBubblesAndTextBoundsRemainExplicit() {
        val page=MessageWindow.parse(snapshot(node("message_list",top=70,bottom=1000),
            node("message_bubble_context",top=100,bottom=200),node(text="Photo attachment",top=110,bottom=150),
            node("message_bubble_context",top=300,bottom=400),
            node("message_bubble_context",top=500,bottom=900),node("message_text",text="x".repeat(1000),top=510,bottom=890)))
        val observed=page.messages()
        assertEquals(3,observed.size);assertEquals("VISIBLE_NON_TEXT_BUBBLE",observed[0].contentType)
        assertEquals(listOf("Photo attachment"),observed[0].labels)
        assertEquals("VISIBLE_UNREADABLE_BUBBLE",observed[1].contentType);assertNull(observed[1].text)
        assertTrue(observed[2].textMayBeTruncated)
    }
    @Test fun orderedOverlapPreservesRepeatedMessagesOutsideItsUniqueAnchor() {
        val older=listOf(bubble("A"),bubble("B"),bubble("C"))
        val current=listOf(bubble("C"),bubble("repeat"),bubble("repeat"),bubble("D"))
        val merge=mergeOlderMessages(older,current)
        assertNull(merge.ambiguity);assertEquals(1,merge.overlap)
        assertEquals(listOf("A","B","C","repeat","repeat","D"),merge.messages.map {it.text})
    }
    @Test fun repeatedAnchorsAndMissingOverlapCannotBeSilentlyCollapsed() {
        val repeated=mergeOlderMessages(listOf(bubble("same"),bubble("A"),bubble("same")),listOf(bubble("same"),bubble("B")))
        assertEquals("AMBIGUOUS_REPEATED_BUBBLES",repeated.ambiguity)
        assertEquals(listOf("same","B"),repeated.messages.map {it.text})
        val many=mergeOlderMessages(listOf(bubble("A"),bubble("same"),bubble("same")),listOf(bubble("same"),bubble("same"),bubble("B")))
        assertEquals("AMBIGUOUS_REPEATED_BUBBLES",many.ambiguity)
        assertEquals("NO_PROVABLE_OVERLAP",mergeOlderMessages(listOf(bubble("A")),listOf(bubble("B"))).ambiguity)
        assertEquals("UNCHANGED_VISIBLE_WINDOW",mergeOlderMessages(listOf(bubble("A")),listOf(bubble("A"))).ambiguity)
    }
    @Test fun parentMessagesGrantDoesNotAuthorizeAnyChildHeadlessOperation() {
        val policy=PolicyEngine();val now=1000L
        val parent=CapabilityDescriptor("messages.recent",title="Read visible messages",description="Real UI recipe",inputSchema=objectSchema(),
            backend=BackendKind.ADB_SERVICE,risk=RiskClass.HIGH_IMPACT,interaction=InteractionMode.ISOLATED_SCREEN)
        val grant=AgentGrant("caller","Caller",scopes=listOf("messages.*"),autoApproveUpTo=RiskClass.HIGH_IMPACT)
        fun decision(agent:AgentGrant,d:CapabilityDescriptor)=policy.evaluate(agent,d,buildJsonObject {},RiskAssessment(d.risk,d.title,target=MESSAGES_PACKAGE),
            Availability.IMPLEMENTED,DeviceControls(),Origin.REMOTE,now).outcome
        assertEquals(PolicyOutcome.ALLOW,decision(grant,parent))
        for(id in listOf("open","inspect","click","scroll","close")) {
            val child=parent.copy(id="headless.$id")
            assertEquals(PolicyOutcome.DENY,decision(grant,child))
            assertEquals(PolicyOutcome.ALLOW,decision(grant.copy(scopes=listOf("*"),fullControl=true),child))
            assertEquals(PolicyOutcome.DENY,decision(grant.copy(scopes=listOf("*"),fullControl=true,revoked=true),child))
        }
    }
}
