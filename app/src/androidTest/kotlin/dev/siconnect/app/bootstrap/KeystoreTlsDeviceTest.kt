package dev.siconnect.app.bootstrap

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

/** A real TLS 1.3 socket handshake with disposable Keystore keys. Not Android adbd pairing. */
class KeystoreTlsDeviceTest {
    @Test fun nonexportableHostIdentitySurvivesReloadAndSignsTls13() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val alias="siconnect-tls-fixture-${UUID.randomUUID()}"
        val identity=AdbHostIdentity(context,alias)
        val executor=Executors.newSingleThreadExecutor()
        try {
            val material=identity.loadOrCreate()
            assertNull(material.key.encoded)
            assertEquals(material.fingerprint,AdbHostIdentity(context,alias).loadOrCreate().fingerprint)
            val manager=object:X509ExtendedKeyManager() {
                override fun getClientAliases(type:String?,issuers:Array<out Principal>?)=arrayOf("fixture")
                override fun chooseClientAlias(types:Array<out String>?,issuers:Array<out Principal>?,socket:Socket?)="fixture"
                override fun getServerAliases(type:String?,issuers:Array<out Principal>?)=arrayOf("fixture")
                override fun chooseServerAlias(type:String?,issuers:Array<out Principal>?,socket:Socket?)=if(type=="RSA") "fixture" else null
                override fun getCertificateChain(alias:String?)=arrayOf(material.certificate)
                override fun getPrivateKey(alias:String?):PrivateKey=material.key
            }
            val trust=object:X509TrustManager {
                override fun getAcceptedIssuers()=emptyArray<X509Certificate>()
                override fun checkClientTrusted(chain:Array<out X509Certificate>?,type:String?) {assertEquals(material.certificate,chain?.first())}
                override fun checkServerTrusted(chain:Array<out X509Certificate>?,type:String?) {assertEquals(material.certificate,chain?.first())}
            }
            val tls=SSLContext.getInstance("TLSv1.3").apply {init(arrayOf(manager),arrayOf(trust),null)}
            (tls.serverSocketFactory.createServerSocket(0,1,InetAddress.getLoopbackAddress()) as SSLServerSocket).use {server ->
                server.enabledProtocols=arrayOf("TLSv1.3");server.needClientAuth=true;server.soTimeout=10000
                val peer=executor.submit<ByteArray> {
                    (server.accept() as SSLSocket).use {socket ->
                        socket.soTimeout=10000;socket.startHandshake()
                        android.net.ssl.SSLSockets.exportKeyingMaterial(socket,"adb-label\u0000",null,64)
                    }
                }
                (tls.socketFactory.createSocket(InetAddress.getLoopbackAddress(),server.localPort) as SSLSocket).use {socket ->
                    socket.enabledProtocols=arrayOf("TLSv1.3");socket.soTimeout=10000
                    try {socket.startHandshake()} catch(e:Exception) {throw AssertionError("TLS peer failure: "+runCatching {peer.get(2,TimeUnit.SECONDS)}.exceptionOrNull(),e)}
                    assertEquals("TLSv1.3",socket.session.protocol)
                    assertArrayEquals(peer.get(10,TimeUnit.SECONDS),android.net.ssl.SSLSockets.exportKeyingMaterial(socket,"adb-label\u0000",null,64))
                }
            }
        } finally {executor.shutdownNow();identity.reset();context.deleteSharedPreferences(alias)}
    }
}
