package dev.siconnect.app.privileged

import org.junit.Assert.*
import org.junit.Test

class PrivateScrollContractTest {
    @Test fun advertisedDirectionalActionsWorkWithoutScrollableFlag() {
        assertEquals(PrivateScrollContract.UP,PrivateScrollContract.semanticAction("BACKWARD",false,setOf(PrivateScrollContract.UP)))
        assertEquals(PrivateScrollContract.DOWN,PrivateScrollContract.semanticAction("FORWARD",false,setOf(PrivateScrollContract.DOWN)))
        assertEquals(listOf("BACKWARD"),PrivateScrollContract.directions(setOf(64,PrivateScrollContract.UP,PrivateScrollContract.BACKWARD)))
    }

    @Test fun advertisedOppositeDirectionDoesNotDispatchUnsupportedScroll() {
        val actions=setOf(64,PrivateScrollContract.UP,PrivateScrollContract.BACKWARD)
        assertNull(PrivateScrollContract.semanticAction("FORWARD",true,actions))
        assertEquals(PrivateScrollContract.BACKWARD,PrivateScrollContract.semanticAction("BACKWARD",true,actions))
    }

    @Test fun legacyScrollableNodeRetainsSemanticCompatibility() {
        assertEquals(PrivateScrollContract.FORWARD,PrivateScrollContract.semanticAction("FORWARD",true,setOf(64)))
        assertNull(PrivateScrollContract.semanticAction("FORWARD",false,setOf(64)))
    }

    @Test fun unreportedAndroidDeltaIsNotMistakenForBackwardMovement() {
        assertNull(PrivateScrollContract.observedDelta(-1))
        assertEquals(0,PrivateScrollContract.observedDelta(0))
        assertEquals(-120,PrivateScrollContract.observedDelta(-120))
        assertEquals(120,PrivateScrollContract.observedDelta(120))
    }

    @Test fun touchScrollStaysInsideClippedOwnedDisplayBoundsAndReversesDirection() {
        val forward=privateScrollPlan(-100,-50,1200,2200,1080,1920,"FORWARD")
        val backward=privateScrollPlan(-100,-50,1200,2200,1080,1920,"BACKWARD")
        assertEquals(540f,forward.x,0f)
        assertTrue(forward.fromY>forward.toY)
        assertTrue(forward.toY>0f && forward.fromY<1920f)
        assertEquals(forward.fromY,backward.toY,0f)
        assertEquals(forward.toY,backward.fromY,0f)
        val inset=privateScrollPlan(500,600,1000,1500,1080,1920,"FORWARD")
        assertEquals(750f,inset.x,0f)
        assertTrue(inset.toY>600f && inset.fromY<1500f)
    }

    @Test fun touchScrollRejectsOffscreenTinyAndInvalidTargetsBeforeInput() {
        for(bounds in listOf(listOf(1100,0,1400,500),listOf(0,2000,500,2200),listOf(10,10,20,200),listOf(10,10,200,50))) {
            try {privateScrollPlan(bounds[0],bounds[1],bounds[2],bounds[3],1080,1920,"FORWARD");fail("Unsafe target admitted")}
            catch(_:IllegalArgumentException) { }
        }
        try {privateScrollPlan(0,0,1080,1920,1080,1920,"SIDEWAYS");fail("Invalid direction admitted")}
        catch(_:IllegalArgumentException) { }
    }

    @Test fun captureScalingAlwaysConvergesWithoutZeroDimensions() {
        var size=1080 to 1920
        repeat(40) {
            val smaller=nextPrivateCaptureSize(size.first,size.second)
            assertTrue(smaller.first in 1..size.first && smaller.second in 1..size.second)
            if(size!=(1 to 1)) assertTrue(smaller!=size)
            size=smaller
        }
        assertEquals(1 to 1,size)
        assertEquals(1 to 1,nextPrivateCaptureSize(1,1))
        assertEquals(810 to 1440,nextPrivateCaptureSize(1080,1920))
    }
}
