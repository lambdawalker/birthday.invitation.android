package com.lambdawalker.invitation

import org.junit.Assert.*
import org.junit.Test

class SendPolicyTest {
    @Test
    fun uncertainNeverAutomaticallyResends() {
        assertFalse(SendPolicy.canSend("uncertain", "me", "me", false))
    }

    @Test
    fun downloadedDoesNotMeanAssigned() {
        assertFalse(SendPolicy.canSend("pending", "", "me", false))
    }

    @Test
    fun onlyOwnPendingWithNoOutboxCanSend() {
        assertTrue(SendPolicy.canSend("pending", "me", "me", false))
        assertFalse(SendPolicy.canSend("pending", "me", "me", true))
        assertFalse(SendPolicy.canSend("sent", "me", "me", false))
        assertFalse(SendPolicy.canSend("failed", "me", "me", false))
    }

    @Test
    fun restartConvertsDispatchToUncertain() {
        assertEquals("uncertain", SendPolicy.recover("dispatching"))
        assertEquals("sent", SendPolicy.recover("sent"))
    }

    @Test
    fun callbackCannotDowngradeDelivery() {
        assertEquals("delivered", SendPolicy.mergeResult("delivered", "sent"))
        assertEquals("sent", SendPolicy.mergeResult("sent", "failed"))
    }

    @Test
    fun manualConfirmationSupersedesLateDelivery() {
        assertTrue(SendPolicy.superseded("manual_sent", "delivered"))
        assertFalse(SendPolicy.superseded("sent", "delivered"))
    }

    @Test
    fun pausedNeverDispatchesAfterBegin() {
        assertFalse(SendPolicy.mayDispatch(false, true))
        assertFalse(SendPolicy.mayDispatch(true, false))
        assertTrue(SendPolicy.mayDispatch(true, true))
    }

    @Test
    fun reconcileOnlyStaleAttempts() {
        assertTrue(SendPolicy.canDiscard("old", "new"))
        assertFalse(SendPolicy.canDiscard("same", "same"))
    }
}
