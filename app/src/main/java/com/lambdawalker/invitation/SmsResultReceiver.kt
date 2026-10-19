package com.lambdawalker.invitation

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("family") ?: return
        val attempt = intent.getStringExtra("attempt") ?: return
        val delivered = intent.action == "com.lambdawalker.invitation.DELIVERED"
        val status =
            if (resultCode == Activity.RESULT_OK) {
                if (delivered) "delivered" else "sent"
            } else {
                if (delivered) return else "failed"
            }
        LocalState(context)
            .record(
                id,
                attempt,
                status,
                if (status == "failed") "Android SMS error $resultCode" else "",
            )
    }
}
