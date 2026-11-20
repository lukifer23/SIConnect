package dev.siconnect.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import dev.siconnect.app.data.GatewayConfig
import dev.siconnect.app.data.Settings
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class GatewaySettingsDeviceTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "test_enroll_${UUID.randomUUID()}_"
    private val context = object : ContextWrapper(target) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            target.getSharedPreferences(prefix + name, mode)
    }
    private val first = GatewayConfig("https://first.example", true)
    private val second = GatewayConfig("https://second.example", true)

    @After fun cleanup() {
        target.deleteSharedPreferences(prefix + "siconnect")
        target.deleteSharedPreferences(prefix + "siconnect_secrets")
    }

    @Test fun enrollmentSurvivesRecreationAndStaleCompletions() {
        Settings(context).beginEnrollment(first, "first-token")
        val restored = Settings(context)
        assertEquals(first, restored.gateway.value)
        assertEquals("first-token", restored.pendingEnrollment(first))
        restored.beginEnrollment(second, "next-token")
        restored.completeEnrollment(first, "first-token")
        restored.beginEnrollment(second, "replacement-token")
        restored.completeEnrollment(second, "next-token")
        assertNull(restored.pendingEnrollment(first))
        assertEquals("replacement-token", Settings(context).pendingEnrollment(second))
        restored.setGateway(second.copy(enabled=false))
        val paused = Settings(context)
        assertFalse(paused.gateway.value.enabled)
        paused.setGateway(second)
        paused.completeEnrollment(second, "replacement-token")
        assertNull(Settings(context).pendingEnrollment(second))
        assertFalse(context.getSharedPreferences("siconnect",0).contains("enroll_token"))
    }

    @Test fun legacyCiphertextMigratesAndStaysBoundToItsGateway() {
        Settings(context).beginEnrollment(first, "legacy-token")
        val prefs = context.getSharedPreferences("siconnect", 0)
        val secrets = context.getSharedPreferences("siconnect_secrets", 0)
        val ciphertext = requireNotNull(prefs.getString("enroll_token", null))
        assertNotEquals("legacy-token", ciphertext)
        assertTrue(secrets.edit().putString("enroll_token", ciphertext).commit())
        assertTrue(prefs.edit().remove("enroll_token").commit())
        val migrated = Settings(context)
        assertFalse(secrets.contains("enroll_token"))
        assertEquals("legacy-token", migrated.pendingEnrollment(first))
        migrated.setGateway(second)
        assertNull(Settings(context).pendingEnrollment(second))
    }
}
