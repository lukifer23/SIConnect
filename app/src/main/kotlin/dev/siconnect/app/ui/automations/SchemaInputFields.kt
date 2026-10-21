package dev.siconnect.app.ui.automations

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*

/** Typed controls follow the installed skill's schema; no second input contract. */
@Composable
internal fun SchemaInputFields(schema:JsonObject,value:JsonObject,onChange:(JsonObject)->Unit,
                               keys:Set<String>?=null,depth:Int=0) {
    val required=(schema["required"] as? JsonArray).orEmpty().map {it.jsonPrimitive.content}.toSet()
    val properties=schema["properties"] as? JsonObject ?: return
    properties.forEach {(name,element)->
        if(keys!=null && name !in keys) return@forEach
        val field=element.jsonObject
        val label=field["description"]?.jsonPrimitive?.content?.substringBefore(';') ?: name
        val current=value[name] ?: field["default"]
        fun update(next:JsonElement?) {onChange(JsonObject(value.toMutableMap().apply {if(next==null) remove(name) else put(name,next)}))}
        key(name) {
            when(field["type"]?.jsonPrimitive?.content) {
                "boolean" -> Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                    Switch(current?.jsonPrimitive?.booleanOrNull ?: false,{update(JsonPrimitive(it))})
                }
                "object" -> {
                    if(field["properties"] is JsonObject && depth<4) {
                        Text(label,style=MaterialTheme.typography.titleSmall)
                        Column(Modifier.padding(start=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            SchemaInputFields(field,current as? JsonObject ?: JsonObject(emptyMap()),{update(it)},depth=depth+1)
                        }
                    } else JsonValueField(label,current,name in required,::update)
                }
                "array" -> {
                    val items=field["items"] as? JsonObject
                    if(items?.get("type")?.jsonPrimitive?.content=="string") {
                        val lines=(current as? JsonArray)?.joinToString("\n") {it.jsonPrimitive.content} ?: ""
                        OutlinedTextField(lines,{raw->update(if(raw.isBlank() && name !in required) null else JsonArray(raw.take(16384).split('\n').map(::JsonPrimitive)))},
                            label={Text(label)},supportingText={Text("One item per line")},modifier=Modifier.fillMaxWidth(),maxLines=5)
                    } else JsonValueField(label,current,name in required,::update)
                }
                "null" -> Unit
                else -> {
                    val enums=field["enum"] as? JsonArray
                    if(enums!=null) {
                        ChoiceField(label,enums.map {it.jsonPrimitive.content},current?.jsonPrimitive?.content ?: "",{selected->
                            update(enums.first {it.jsonPrimitive.content==selected})
                        })
                    } else {
                        val kind=field["type"]?.jsonPrimitive?.content
                        val max=(field["maxLength"] as? JsonPrimitive)?.intOrNull?.coerceAtMost(16384) ?: 4000
                        OutlinedTextField(current?.jsonPrimitive?.content ?: "",{raw->
                            val bounded=raw.take(max)
                            val next=when {
                                bounded.isEmpty() && name !in required -> null
                                kind=="integer" -> bounded.toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(bounded)
                                kind=="number" -> bounded.toDoubleOrNull()?.takeIf {it.isFinite()}?.let(::JsonPrimitive) ?: JsonPrimitive(bounded)
                                else -> JsonPrimitive(bounded)
                            }
                            update(next)
                        },label={Text(label+if(name in required) " *" else "")},modifier=Modifier.fillMaxWidth(),
                            keyboardOptions=KeyboardOptions(keyboardType=if(kind=="integer" || kind=="number") KeyboardType.Decimal else KeyboardType.Text),
                            singleLine=name !in setOf("text","description"),maxLines=if(name in setOf("text","description")) 4 else 1)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ChoiceField(label:String,options:List<String>,selected:String,onSelect:(String)->Unit,labels:Map<String,String> = emptyMap()) {
    var expanded by remember {mutableStateOf(false)}
    Column {
        Text(label,style=MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth()) {Text(labels[selected] ?: selected.ifBlank {"Choose"})}
            DropdownMenu(expanded,{expanded=false}) {options.forEach {option->
                DropdownMenuItem(text={Text(labels[option] ?: option)},onClick={onSelect(option);expanded=false})
            }}
        }
    }
}

@Composable
private fun JsonValueField(label:String,current:JsonElement?,required:Boolean,update:(JsonElement?)->Unit) {
    var text by rememberSaveable(label) {mutableStateOf(current?.toString() ?: "")}
    var error by remember {mutableStateOf(false)}
    OutlinedTextField(text,{raw->
        text=raw.take(16384)
        val parsed=if(text.isBlank() && !required) Result.success(null) else runCatching {Json.parseToJsonElement(text)}
        error=parsed.isFailure
        // Invalid text remains a string so the authoritative schema prevents saving it.
        update(parsed.getOrElse {JsonPrimitive(text)})
    },label={Text(label)},supportingText={Text(if(error) "Enter valid JSON for this structured value" else "Structured value for this installed workflow")},isError=error,
        modifier=Modifier.fillMaxWidth(),minLines=2,maxLines=5)
}
