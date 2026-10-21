package dev.siconnect.app

import dev.siconnect.app.accessibility.SemanticUiRules
import org.junit.Assert.*
import org.junit.Test

class SemanticUiRulesTest {
    @Test fun securityAndPermissionSurfacesAreExcluded() {
        for (name in listOf("com.android.systemui", "com.android.settings", "com.google.android.permissioncontroller", "com.samsung.android.biometrics.app.setting", "com.android.packageinstaller")) assertTrue(SemanticUiRules.protectedPackage(name))
        assertFalse(SemanticUiRules.protectedPackage("com.example.disposable"))
    }
}
