package dev.siconnect.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Material's system font and font scaling remain intact; the brand palette has light/dark contrast. */
@Composable fun SIConnectTheme(appearance:String,content:@Composable (Boolean)->Unit) {
    val dark=when(appearance) {"LIGHT"->false;"DARK"->true;else->isSystemInDarkTheme()}
    val colors=if(dark) darkColorScheme(primary=Color(0xFF8ADCC3),onPrimary=Color(0xFF062B23),
        background=Color(0xFF0D1218),surface=Color(0xFF151E27),surfaceVariant=Color(0xFF202D39))
    else lightColorScheme(primary=Color(0xFF006B55),onPrimary=Color.White,primaryContainer=Color(0xFFAAF2DA),
        onPrimaryContainer=Color(0xFF002117),background=Color(0xFFF6FAF8),surface=Color(0xFFF6FAF8),
        surfaceVariant=Color(0xFFDCE5E0),onSurface=Color(0xFF17211D),onSurfaceVariant=Color(0xFF404943),outline=Color(0xFF707972))
    MaterialTheme(colorScheme=colors,content={content(dark)})
}
