package dev.siconnect.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.siconnect.app.R
import dev.siconnect.core.RequestState

internal val LocalExpandedLayout=staticCompositionLocalOf {false}

/** Shared visual hierarchy; status always has a text label as well as a color. */
@Composable internal fun PageLayout(modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit) {
    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter) {
        Column(modifier.widthIn(max=1120.dp).fillMaxWidth().fillMaxHeight().padding(horizontal=24.dp,vertical=24.dp),
            verticalArrangement=Arrangement.spacedBy(20.dp),content=content)
    }
}

@Composable internal fun PageHeader(title:String,subtitle:String,eyebrow:String?=null,action:@Composable RowScope.()->Unit={}) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            eyebrow?.let {Text(it.uppercase(),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)}
            Text(title,style=MaterialTheme.typography.headlineLarge,modifier=Modifier.semantics {heading()})
            if(subtitle.isNotBlank()) Text(subtitle,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action()
    }
}

@Composable internal fun SectionHeading(title:String,action:String?=null,onAction:()->Unit={}) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Text(title,style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f).semantics {heading()})
        action?.let {TextButton(onClick=onAction) {Text(it)}}
    }
}

enum class StatusTone { GOOD, NEUTRAL, ATTENTION, ERROR }
@Composable internal fun StatusPill(label:String,tone:StatusTone=StatusTone.NEUTRAL) {
    val colors=MaterialTheme.colorScheme
    val color=when(tone) {StatusTone.GOOD->colors.primary;StatusTone.ATTENTION->colors.tertiary;StatusTone.ERROR->colors.error;else->colors.onSurfaceVariant}
    Surface(color=color.copy(alpha=.1f),shape=CircleShape) {
        Row(Modifier.padding(horizontal=10.dp,vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(color,CircleShape))
            Text(label,style=MaterialTheme.typography.labelMedium,color=color)
        }
    }
}

@Composable internal fun IconBadge(icon:Int,modifier:Modifier=Modifier) {
    Surface(modifier.size(44.dp),shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.primary.copy(alpha=.1f)) {
        Box(contentAlignment=Alignment.Center) {Icon(painterResource(icon),null,Modifier.size(22.dp),tint=MaterialTheme.colorScheme.primary)}
    }
}

@Composable internal fun Panel(modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit) {
    Card(modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
    }
}

@Composable internal fun NavigationCard(title:String,subtitle:String,onClick:()->Unit,modifier:Modifier=Modifier,icon:Int=R.drawable.ic_nav_settings,status:String?=null) {
    Card(onClick=onClick,modifier=modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.padding(18.dp).heightIn(min=56.dp),horizontalArrangement=Arrangement.spacedBy(14.dp),verticalAlignment=Alignment.CenterVertically) {
            IconBadge(icon)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(title,style=MaterialTheme.typography.titleMedium)
                Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                status?.let {Text(it,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)}
            }
            Text("›",style=MaterialTheme.typography.headlineSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable internal fun EmptyState(title:String,description:String,icon:Int=R.drawable.ic_nav_activity,action:String?=null,onAction:()->Unit={}) {
    Panel {
        IconBadge(icon)
        Text(title,style=MaterialTheme.typography.titleMedium)
        Text(description,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        action?.let {OutlinedButton(onClick=onAction) {Text(it)}}
    }
}

@Composable internal fun ExpandablePanel(title:String,summary:String,initiallyExpanded:Boolean=false,content:@Composable ColumnScope.()->Unit) {
    var expanded by rememberSaveable(title) {mutableStateOf(initiallyExpanded)}
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        NavigationCard(title,summary,{expanded=!expanded},status=if(expanded) "Hide details" else "Show details")
        if(expanded) Panel(content=content)
    }
}

@Composable internal fun Notice(text:String,error:Boolean=false) {
    Surface(color=if(error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,shape=MaterialTheme.shapes.medium) {
        Text(text,Modifier.fillMaxWidth().padding(16.dp),style=MaterialTheme.typography.bodyMedium,
            color=if(error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

internal fun requestTone(state:RequestState)=when(state) {
    RequestState.SUCCEEDED->StatusTone.GOOD
    RequestState.FAILED,RequestState.UNKNOWN_OUTCOME->StatusTone.ERROR
    RequestState.AWAITING_APPROVAL,RequestState.DENIED,RequestState.EXPIRED->StatusTone.ATTENTION
    else->StatusTone.NEUTRAL
}
