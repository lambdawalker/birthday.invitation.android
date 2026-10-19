package com.lambdawalker.invitation

data class PhoneContact(val id: String, val name: String, val phones: List<String>)
data class ContactDraft(val id: String, val name: String, val phone: String, val email: String = "")

object ContactNumbers {
    fun normalize(raw: String, countryCode: String = "+52"): String? {
        val compact = raw.trim().filterNot { it == ' ' || it in "-()." }
        if (!Regex("^(\\+|00)?[0-9]+$").matches(compact)) return null
        var phone = when {
            compact.startsWith("+") -> compact
            compact.startsWith("00") -> "+" + compact.drop(2)
            compact.length == 10 && Regex("^\\+[1-9][0-9]{0,2}$").matches(countryCode) -> countryCode + compact
            else -> return null
        }
        if (phone.startsWith("+521") && phone.length == 14) phone = "+52" + phone.drop(4)
        return phone.takeIf { Regex("^\\+[1-9][0-9]{7,14}$").matches(it) }
    }

    fun validName(name: String) = name.trim().isNotEmpty() && name.trim().toByteArray(Charsets.UTF_8).size <= 120
    fun duplicatePhones(drafts: List<ContactDraft>, countryCode: String): Set<String> =
        drafts.mapNotNull { normalize(it.phone, countryCode) }.groupingBy { it }.eachCount()
            .filterValues { it > 1 }.keys
}
