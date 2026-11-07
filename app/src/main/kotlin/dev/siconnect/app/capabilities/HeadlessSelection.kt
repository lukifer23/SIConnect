package dev.siconnect.app.capabilities

import dev.siconnect.core.Schema
import dev.siconnect.core.objectSchema
import kotlinx.serialization.json.*

/** Bounded AND selection over the same redacted fields returned by private inspection. */
internal class HeadlessSelection private constructor(private val selector:JsonObject) {
    fun matches(row:JsonObject):Boolean {
        val contains=selector["contains"]?.jsonPrimitive?.booleanOrNull==true
        return selector.all {(key,value)->when(key) {
            "contains"->true
            "text","description"->{
                val actual=row[key]?.jsonPrimitive?.content.orEmpty()
                val expected=value.jsonPrimitive.content
                if(contains) actual.contains(expected) else actual==expected
            }
            else->row[key]==value
        }}
    }

    companion object {
        val schema=objectSchema {
            string("text","Bounded visible text; exact unless contains=true",maxLength=500)
            string("description","Bounded content description; exact unless contains=true",maxLength=500)
            string("resourceId","Exact full resource ID",maxLength=300)
            string("className","Exact Android accessibility class",maxLength=200)
            boolean("contains","Substring matching for text and description only; default false")
            for(field in listOf("clickable","editable","scrollable","enabled","focused","selected")) boolean(field,"Require $field state")
        }
        fun parse(raw:JsonElement?):HeadlessSelection? {
            if(raw==null) return null
            require(raw is JsonObject && Schema.validate(schema,raw).isEmpty()) {"Invalid private inspection selector"}
            require(raw.keys.any {it!="contains"}) {"Supply at least one private inspection predicate"}
            return HeadlessSelection(raw)
        }
    }
}
