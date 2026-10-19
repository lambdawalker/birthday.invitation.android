package com.lambdawalker.invitation

import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class Api(private val base: String, private val token: String) {
    init {
        val uri = URI(base)
        require(
            uri.scheme == "https" &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.query == null &&
                uri.fragment == null &&
                (uri.path.isNullOrEmpty() || uri.path == "/")
        ) {
            "Usa la URL HTTPS del servidor, sin ruta ni parámetros"
        }
    }

    suspend fun request(path: String, body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val c =
                URI(base.trimEnd('/') + "/api" + path).toURL().openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 15000
                c.readTimeout = 20000
                c.requestMethod = if (body == null) "GET" else "POST"
                c.setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(body.toString().toByteArray()) }
                }
                val code = c.responseCode
                val text =
                    (if (code in 200..299) c.inputStream else c.errorStream)
                        ?.bufferedReader()
                        ?.use { it.readText() } ?: "{}"
                val result =
                    try {
                        JSONObject(text)
                    } catch (_: Exception) {
                        JSONObject()
                    }
                if (code !in 200..299)
                    throw IllegalStateException(result.optString("error", "Error HTTP $code"))
                result
            } finally {
                c.disconnect()
            }
        }

    suspend fun families(): List<Family> {
        val a = request("/families").getJSONArray("families")
        return (0 until a.length()).map { Family.from(a.getJSONObject(it)) }
    }

    suspend fun action(
        id: String,
        action: String,
        channel: String = "",
        attemptId: String = "",
        status: String = "",
        detail: String = "",
    ): Family =
        Family.from(
            request(
                "/families/$id/actions",
                JSONObject()
                    .put("action", action)
                    .put("channel", channel)
                    .put("attemptId", attemptId)
                    .put("status", status)
                    .put("detail", detail),
            )
        )
}
