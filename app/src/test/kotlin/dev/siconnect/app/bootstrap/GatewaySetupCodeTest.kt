package dev.siconnect.app.bootstrap

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class GatewaySetupCodeTest {
    @Test fun existingGatewayCodeAcceptsOnlySecureOriginsAndBoundedCredentials() {
        val code="""{"url":"https://gateway.example/","enroll":"disposable_fixture_token_only"}"""
        assertEquals("https://gateway.example",GatewaySetupCode.parse(code).endpoint)
        assertEquals(GatewaySetupCode.parse(code),GatewaySetupCode.parse(Base64.getUrlEncoder().withoutPadding().encodeToString(code.toByteArray())))
        for(url in listOf("http://gateway.example","https://gateway.example/path","https://user@gateway.example","https://gateway.example?token=x","https://gateway.example#fragment")) {
            try {GatewaySetupCode.parse(code.replace("https://gateway.example/",url));fail(url)} catch(_:IllegalArgumentException) {}
        }
        try {GatewaySetupCode.parse("x".repeat(4097));fail("Unbounded input accepted")} catch(_:IllegalArgumentException) {}
    }
}
