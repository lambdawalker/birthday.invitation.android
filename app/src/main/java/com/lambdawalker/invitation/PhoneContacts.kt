package com.lambdawalker.invitation

import android.content.ContentResolver
import android.provider.ContactsContract.CommonDataKinds.Phone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PhoneContacts {
    // Only names and phone numbers are read, on a worker thread. Never persisted.
    suspend fun load(resolver: ContentResolver): List<PhoneContact> = withContext(Dispatchers.IO) {
        val rows = linkedMapOf<String, PhoneContact>()
        resolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER),
            null, null, null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(Phone.CONTACT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(Phone.DISPLAY_NAME_PRIMARY)
            val numberColumn = cursor.getColumnIndexOrThrow(Phone.NUMBER)
            while (cursor.moveToNext()) {
                val id = cursor.getString(idColumn)
                val number = cursor.getString(numberColumn)?.trim().orEmpty()
                if (number.isEmpty()) continue
                val name = cursor.getString(nameColumn)?.trim().orEmpty()
                val old = rows[id]
                rows[id] = PhoneContact(id, name, ((old?.phones ?: emptyList()) + number).distinct())
            }
        } ?: error("Unable to read contacts. Check Contacts permission and try again.")
        rows.values.sortedBy { it.name.lowercase() }
    }
}
