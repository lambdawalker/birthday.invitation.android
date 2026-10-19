package com.lambdawalker.invitation

import org.junit.Assert.*
import org.junit.Test

class SignInTest {
    @Test fun usernameNormalizationAndValidation() {
        assertEquals("david", SignIn.username(" David "))
        assertTrue(SignIn.validUsername("David.phone"))
        assertFalse(SignIn.validUsername("a"))
        assertFalse(SignIn.validUsername("name with spaces"))
    }

    @Test fun pinPreservesLeadingZeros() {
        assertTrue(SignIn.validPIN("00123456"))
        assertFalse(SignIn.validPIN("123456"))
        assertFalse(SignIn.validPIN("1234567a"))
    }

    @Test fun sessionRotationKeepsAssignments() {
        val id = SignIn.userId("David")
        assertEquals(id, SignIn.actorId("user_" + id + "." + "a".repeat(64)))
        assertEquals(id, SignIn.actorId("user_" + id + "." + "b".repeat(64)))
        assertEquals(SignIn.userId("david"), id)
    }

    @Test fun existingDeviceCredentialsKeepIdentity() {
        val token = "device_legacy-token"
        assertEquals(tokenHash(token), SignIn.actorId(token))
    }
}
