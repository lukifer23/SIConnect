# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class dev.siconnect.** { *** Companion; }
-keepclasseswithmembers class dev.siconnect.** { kotlinx.serialization.KSerializer serializer(...); }
# Shizuku UserService is instantiated reflectively in the privileged process.
-keep class dev.siconnect.app.privileged.PrivilegedShellService { <init>(...); }
-keep class dev.siconnect.app.privileged.IPrivilegedShell* { *; }

-keep class dev.siconnect.app.privileged.DirectShellDaemon { *; }
