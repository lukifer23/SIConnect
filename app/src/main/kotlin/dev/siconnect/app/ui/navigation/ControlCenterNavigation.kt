package dev.siconnect.app.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import dev.siconnect.app.R
import androidx.compose.ui.unit.dp

enum class Destination(val label:String,val icon:Int) {
    HOME("Home",R.drawable.ic_nav_home),AGENTS("Agents",R.drawable.ic_nav_agents),AUTOMATIONS("Automations",R.drawable.ic_nav_automations),ACTIVITY("Activity",R.drawable.ic_nav_activity),SETTINGS("Settings",R.drawable.ic_nav_settings)
}

/** Material's window/posture-aware bar/rail; a saved destination suffices for five local sections. */
@Composable fun ControlCenterNavigation(selected:Destination,onSelect:(Destination)->Unit,content:@Composable (Boolean)->Unit) {
    val saved=rememberSaveableStateHolder()
    BackHandler(selected!=Destination.HOME) {onSelect(Destination.HOME)}
    NavigationSuiteScaffold(navigationSuiteItems={Destination.entries.forEach {d ->
        item(selected=selected==d,onClick={onSelect(d)},icon={Icon(painterResource(d.icon),contentDescription=null)},label={Text(d.label)})
    }}) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide=maxWidth>=720.dp
            saved.SaveableStateProvider(selected.name) {content(wide)}
        }
    }
}
