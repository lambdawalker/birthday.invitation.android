package com.lambdawalker.invitation

import java.util.Locale

object SignIn {
    fun username(value: String) = value.trim().lowercase(Locale.ROOT)
    fun validUsername(value: String) = Regex("^[a-z0-9][a-z0-9._-]{2,39}$").matches(username(value))
    fun validPIN(value: String) = Regex("^[0-9]{8}$").matches(value)
    fun userId(value: String) = tokenHash(username(value))

    // Legacy credentials keep their existing device identity during rollout.
    fun actorId(token: String): String {
        if (Regex("^user_[a-f0-9]{64}\\.[a-f0-9]{64}$").matches(token))
            return token.removePrefix("user_").substringBefore('.')
        return tokenHash(token)
    }
}
