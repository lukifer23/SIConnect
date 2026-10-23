package dev.siconnect.app.bootstrap

import org.junit.Assert.*
import org.junit.Test

class BootstrapContractTest {
    @Test fun packageUidAndInstalledApkMustMatch() {
        val apk="/data/app/~~Ab-C=/dev.siconnect.app-abc=/base.apk"
        assertEquals(apk,BootstrapContract.apk("package:$apk\npackage:/data/app/x/split.apk",apk))
        assertEquals(10234,BootstrapContract.uid("package:dev.siconnect.app uid:10234\npackage:dev.siconnect.app.test uid:10235",10234))
        rejects {BootstrapContract.uid("package:another.app uid:10234",10234)}
        rejects {BootstrapContract.apk("package:/data/app/other/base.apk",apk)}
        rejects {BootstrapContract.start("/data/app/'\$(id)/base.apk",10234)}
        rejects {BootstrapContract.start(apk,2000)}
        assertEquals("CLASSPATH='$apk' /system/bin/app_process /system/bin --nice-name=siconnect_privileged dev.siconnect.app.privileged.DirectShellDaemon 10234 </dev/null >/data/local/tmp/siconnect-bootstrap.log 2>&1 &",BootstrapContract.start(apk,10234))
    }
    @Test fun distinctDynamicPortsAndSixDigitCodes() {
        assertEquals(37123,BootstrapContract.port("37123"))
        listOf("0","65536","5555","-1","123; id","9999999999","").forEach {value->rejects {BootstrapContract.port(value)}}
        assertArrayEquals("012345".toByteArray(),BootstrapContract.code("012345"))
        listOf("12345","1234567","１２３４５６","123;id","").forEach {value->rejects {BootstrapContract.code(value)}}
        assertTrue(BootstrapContract.shellUid("2000\n"))
        listOf("0","10234","uid=2000","2000\n0","").forEach {assertFalse(BootstrapContract.shellUid(it))}
    }
    private fun rejects(block:()->Unit) {try {block();fail("Expected rejection")} catch(_:IllegalArgumentException) {}}
}
