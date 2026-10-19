package com.lambdawalker.invitation

import android.content.Context
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** All changes use synchronous commit before an external send. No secrets in this file. */
class LocalState(context: Context) {
    private val prefs = context.getSharedPreferences("queue", Context.MODE_PRIVATE)

    companion object {
        private val lock = Any()
    }

    private fun records() = JSONObject(prefs.getString("attempts", "{}") ?: "{}")

    private fun outbox() = JSONArray(prefs.getString("outbox", "[]") ?: "[]")

    fun clear() {
        synchronized(lock) { check(prefs.edit().clear().commit()) }
    }

    fun cached(): String = prefs.getString("families", "[]") ?: "[]"

    fun cache(raw: String) {
        check(prefs.edit().putString("families", raw).commit())
    }

    fun state(id: String) =
        synchronized(lock) { records().optJSONObject(id)?.optString("status") ?: "" }

    fun attempt(id: String) =
        synchronized(lock) { records().optJSONObject(id)?.optString("attemptId") ?: "" }

    fun prepare(id: String, attempt: String, channel: String) {
        synchronized(lock) {
            val r = records()
            r.put(
                id,
                JSONObject()
                    .put("attemptId", attempt)
                    .put("status", "prepared")
                    .put("channel", channel),
            )
            check(prefs.edit().putString("attempts", r.toString()).commit())
        }
    }

    fun dispatching(id: String) {
        synchronized(lock) {
            val r = records()
            r.getJSONObject(id).put("status", "dispatching")
            check(prefs.edit().putString("attempts", r.toString()).commit())
        }
    }

    fun record(id: String, attempt: String, status: String, detail: String = "") {
        synchronized(lock) {
            val r = records()
            val old = r.optJSONObject(id) ?: return
            if (old.optString("attemptId") != attempt) return
            val next = SendPolicy.mergeResult(old.optString("status"), status)
            if (next == old.optString("status")) return
            old.put("status", next)
            val q = outbox()
            q.put(
                JSONObject()
                    .put("eventId", UUID.randomUUID().toString())
                    .put("id", id)
                    .put("attemptId", attempt)
                    .put("status", next)
                    .put("detail", detail)
            )
            check(
                prefs
                    .edit()
                    .putString("attempts", r.toString())
                    .putString("outbox", q.toString())
                    .commit()
            )
        }
    }

    fun recover() {
        synchronized(lock) {
            val r = records()
            for (id in r.keys()) {
                val v = r.getJSONObject(id)
                if (
                    v.optString("channel") == "sms" &&
                        v.optString("status") in listOf("prepared", "dispatching")
                )
                    record(
                        id,
                        v.getString("attemptId"),
                        "uncertain",
                        "La app se reinició; verifica antes de reenviar",
                    )
            }
        }
    }

    fun pending(): List<JSONObject> =
        synchronized(lock) {
            val q = outbox()
            (0 until q.length()).map { q.getJSONObject(it) }
        }

    fun acknowledge(eventId: String) {
        synchronized(lock) {
            val q = outbox()
            val next = JSONArray()
            for (i in 0 until q.length()) {
                val e = q.getJSONObject(i)
                if (e.getString("eventId") != eventId) next.put(e)
            }
            check(prefs.edit().putString("outbox", next.toString()).commit())
        }
    }

    fun blocked(id: String) =
        pending().any { it.getString("id") == id } ||
            state(id) in
                listOf(
                    "prepared",
                    "dispatching",
                    "uncertain",
                    "sent",
                    "delivered",
                    "manual_sent",
                    "whatsapp_opened",
                )

    fun allowReset(id: String) {
        synchronized(lock) {
            check(!pending().any { it.getString("id") == id })
            val r = records()
            r.remove(id)
            check(prefs.edit().putString("attempts", r.toString()).commit())
        }
    }

    fun handoff(id: String) {
        synchronized(lock) {
            val r = records()
            r.getJSONObject(id).put("status", "whatsapp_opened")
            check(prefs.edit().putString("attempts", r.toString()).commit())
        }
    }

    fun archiveStale(eventId: String) {
        synchronized(lock) {
            val event = pending().find { it.getString("eventId") == eventId } ?: return
            val audit = JSONArray(prefs.getString("audit", "[]"))
            audit.put(
                JSONObject(event.toString()).put("resolution", "Reviewed stale attempt; no resend")
            )
            check(prefs.edit().putString("audit", audit.toString()).commit())
            acknowledge(eventId)
        }
    }
}
