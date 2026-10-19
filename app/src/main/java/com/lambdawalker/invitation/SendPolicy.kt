package com.lambdawalker.invitation
object SendPolicy {
    fun canSend(status: String, owner: String, device: String, blocked: Boolean) =
        status == "pending" && owner == device && device.isNotBlank() && !blocked

    fun recover(status: String) =
        if (status == "dispatching" || status == "prepared") "uncertain" else status

    fun mergeResult(current: String, incoming: String): String =
        when {
            current == "delivered" -> current
            current in listOf("sent", "manual_sent") && incoming != "delivered" -> current
            else -> incoming
        }

    fun superseded(remote: String, incoming: String) =
        remote == "delivered" ||
            remote == "manual_sent" ||
            (remote == "sent" && incoming != "delivered")

    fun mayDispatch(running: Boolean, resumed: Boolean) = running && resumed

    fun canDiscard(localAttempt: String, remoteAttempt: String) = localAttempt != remoteAttempt
}
