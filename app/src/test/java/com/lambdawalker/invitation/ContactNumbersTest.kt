package com.lambdawalker.invitation

import org.junit.Assert.*
import org.junit.Test

class ContactNumbersTest {
    @Test fun mexicoLocalAndLegacyMobileNumbers() {
        assertEquals("+526561234567", ContactNumbers.normalize("(656) 123-4567"))
        assertEquals("+526561234567", ContactNumbers.normalize("+52 1 656 123 4567"))
        assertEquals("+526561234567", ContactNumbers.normalize("00526561234567"))
    }
    @Test fun countryOverrideDoesNotChangeInternationalNumber() {
        assertEquals("+19155551234", ContactNumbers.normalize("9155551234", "+1"))
        assertEquals("+19155551234", ContactNumbers.normalize("+19155551234", "+52"))
        assertNull(ContactNumbers.normalize("9155551234", "1"))
    }
    @Test fun rejectExtensionsAndAmbiguousNumbers() {
        for (raw in listOf("", "123", "6561234567 ext 2", "+52abc6561234567", "++526561234567", "+012345678"))
            assertNull(raw, ContactNumbers.normalize(raw))
    }
    @Test fun deduplicateAfterNormalization() {
        val contacts = listOf(
            ContactDraft("1","A","6561234567"),
            ContactDraft("2","B","+52 656 1234567"),
            ContactDraft("3","C","+19155551234"),
        )
        assertEquals(setOf("+526561234567"), ContactNumbers.duplicatePhones(contacts,"+52"))
    }
    @Test fun validateNameUsingBackendByteLimit() {
        assertFalse(ContactNumbers.validName(" "))
        assertTrue(ContactNumbers.validName("Familia García"))
        assertFalse(ContactNumbers.validName("ñ".repeat(61)))
    }
}
