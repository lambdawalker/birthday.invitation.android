package com.lambdawalker.invitation

import java.security.MessageDigest
import org.json.JSONObject

data class Family(
    val id: String,
    val name: String,
    val phone: String,
    val status: String,
    val assignedTo: String,
    val assignedName: String,
    val attemptId: String,
    val channel: String,
    val rsvp: String,
) {
    companion object {
        fun from(j: JSONObject) =
            Family(
                j.getString("id"),
                j.getString("name"),
                j.getString("phone"),
                j.getString("status"),
                j.optString("assignedTo"),
                j.optString("assignedName"),
                j.optString("attemptId"),
                j.optString("channel"),
                j.getString("rsvp"),
            )
    }
}

fun tokenHash(value: String) =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
        "%02x".format(it)
    }

fun invitationMessage(f: Family, base: String) =
    "Hola ${f.name}, ven con tu familia a los 2 años de Abi el 24/oct a las 4 pm. Confirma: $base/?i=${f.id}"

fun Family.toJson() =
    JSONObject()
        .put("id", id)
        .put("name", name)
        .put("phone", phone)
        .put("status", status)
        .put("assignedTo", assignedTo)
        .put("assignedName", assignedName)
        .put("attemptId", attemptId)
        .put("channel", channel)
        .put("rsvp", rsvp)
