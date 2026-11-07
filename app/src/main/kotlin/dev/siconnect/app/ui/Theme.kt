package dev.siconnect.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Material's system font and font scaling remain intact; the brand palette has light/dark contrast. */
@Composable fun SIConnectTheme(appearance:String,content:@Composable (Boolean)->Unit) {
    val dark=when(appearance) {"LIGHT"->false;"DARK"->true;else->isSystemInDarkTheme()}
    val colors=if(dark) darkColorScheme(primary=Color(0xFF8ADCC3),onPrimary=Color(0xFF062B23),
        primaryContainer=Color(0xFF154A3D),onPrimaryContainer=Color(0xFFAAF2DA),
        secondary=Color(0xFFB4C7DC),onSecondary=Color(0xFF233241),secondaryContainer=Color(0xFF2C4253),onSecondaryContainer=Color(0xFFD3E7F9),
        tertiary=Color(0xFFFFC66D),onTertiary=Color(0xFF432D05),tertiaryContainer=Color(0xFF614110),onTertiaryContainer=Color(0xFFFFDEA5),
        background=Color(0xFF0D1218),onBackground=Color(0xFFDEE6ED),surface=Color(0xFF151E27),onSurface=Color(0xFFDEE6ED),
        surfaceVariant=Color(0xFF202D39),onSurfaceVariant=Color(0xFFBCCAD3),outline=Color(0xFF85949F),outlineVariant=Color(0xFF42515D),
        surfaceDim=Color(0xFF0D1218),surfaceBright=Color(0xFF303C47),surfaceContainerLowest=Color(0xFF091015),
        surfaceContainerLow=Color(0xFF151E27),surfaceContainer=Color(0xFF19252F),surfaceContainerHigh=Color(0xFF22313D),surfaceContainerHighest=Color(0xFF2B3A46))
    else lightColorScheme(primary=Color(0xFF006B55),onPrimary=Color.White,primaryContainer=Color(0xFFAAF2DA),
        onPrimaryContainer=Color(0xFF002117),secondary=Color(0xFF4D626B),onSecondary=Color.White,
        secondaryContainer=Color(0xFFDEEBEC),onSecondaryContainer=Color(0xFF18353D),tertiary=Color(0xFF795716),onTertiary=Color.White,
        tertiaryContainer=Color(0xFFFFDEA5),onTertiaryContainer=Color(0xFF291900),background=Color(0xFFF6FAF8),surface=Color(0xFFF6FAF8),
        surfaceVariant=Color(0xFFDCE5E0),onSurface=Color(0xFF17211D),onBackground=Color(0xFF17211D),onSurfaceVariant=Color(0xFF404943),outline=Color(0xFF707972),
        surfaceDim=Color(0xFFD7E1DC),surfaceBright=Color(0xFFF6FAF8),surfaceContainerLowest=Color.White,
        surfaceContainerLow=Color(0xFFF0F6F2),surfaceContainer=Color(0xFFEAF1ED),surfaceContainerHigh=Color(0xFFE3EBE6),surfaceContainerHighest=Color(0xFFDCE5E0))
    val typography=Typography(
        headlineLarge=TextStyle(fontFamily=FontFamily.SansSerif,fontWeight=FontWeight.SemiBold,fontSize=30.sp,lineHeight=36.sp,letterSpacing=(-.6).sp),
        headlineMedium=TextStyle(fontWeight=FontWeight.SemiBold,fontSize=26.sp,lineHeight=32.sp,letterSpacing=(-.4).sp),
        headlineSmall=TextStyle(fontWeight=FontWeight.SemiBold,fontSize=23.sp,lineHeight=30.sp),
        titleLarge=TextStyle(fontWeight=FontWeight.SemiBold,fontSize=20.sp,lineHeight=26.sp),
        titleMedium=TextStyle(fontWeight=FontWeight.SemiBold,fontSize=16.sp,lineHeight=23.sp),
        bodyLarge=TextStyle(fontSize=16.sp,lineHeight=25.sp),
        bodyMedium=TextStyle(fontSize=14.sp,lineHeight=22.sp),
        bodySmall=TextStyle(fontSize=12.sp,lineHeight=19.sp),
        labelLarge=TextStyle(fontWeight=FontWeight.SemiBold,fontSize=14.sp,lineHeight=20.sp),
        labelMedium=TextStyle(fontWeight=FontWeight.Medium,fontSize=12.sp,lineHeight=18.sp),
        labelSmall=TextStyle(fontWeight=FontWeight.SemiBold,fontSize=11.sp,lineHeight=16.sp,letterSpacing=.5.sp),
    )
    MaterialTheme(colorScheme=colors,typography=typography,shapes=Shapes(
        extraSmall=RoundedCornerShape(8.dp),small=RoundedCornerShape(12.dp),medium=RoundedCornerShape(16.dp),
        large=RoundedCornerShape(24.dp),extraLarge=RoundedCornerShape(28.dp)),content={content(dark)})
}
