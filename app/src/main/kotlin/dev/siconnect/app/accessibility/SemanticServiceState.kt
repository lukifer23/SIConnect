package dev.siconnect.app.accessibility

/** Android's private accessibility crash list is unavailable to an ordinary APK; never infer it. */
internal object SemanticServiceState {
    fun classify(enabled:Boolean,connected:Boolean,locked:Boolean,windowAvailable:Boolean):String = when {
        !connected && !enabled->"DISABLED_BY_OWNER"
        !connected->"ENABLED_BUT_DISCONNECTED"
        locked->"CONNECTED_BUT_SCREEN_LOCKED"
        !windowAvailable->"CONNECTED_WITH_NO_FOREGROUND_WINDOW"
        else->"CONNECTED_READY"
    }
}
