package com.lambdawalker.invitation

fun contactImportError(status: Int): String = when (status) {
    404, 405 -> "Contact import is unavailable on this server (HTTP $status). Ask the administrator to deploy the latest birthday.invitation backend, then try again."
    403 -> "Contact import was refused (HTTP 403). Check your app access with the administrator."
    400, 413 -> "The server rejected this contact batch (HTTP $status). Check the names, phone numbers and emails, or select fewer contacts."
    429 -> "Too many requests (HTTP 429). Wait a moment, then retry; existing numbers will be skipped."
    else -> "Contact import failed (HTTP $status). Some contacts may already be added. Retry safely; existing numbers will be skipped. If this continues, share this HTTP code with the administrator."
}
