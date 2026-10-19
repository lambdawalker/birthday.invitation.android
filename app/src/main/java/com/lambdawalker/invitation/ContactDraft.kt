package com.lambdawalker.invitation

data class PhoneContact(val id: String, val name: String, val phones: List<String>)
data class ContactDraft(val id: String, val name: String, val phone: String, val email: String = "", val countryCode: String = "")

object ContactNumbers {
    fun normalize(raw: String, countryCode: String = ""): String? {
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

    fun existingCountryCode(raw: String): String {
        val phone = normalize(raw) ?: return ""
        return when {
            phone.startsWith("+52") -> "+52"
            phone.startsWith("+1") -> "+1"
            else -> ""
        }
    }

    fun hasInternationalPrefix(raw: String): Boolean =
        raw.trim().startsWith("+") || raw.trim().startsWith("00")

    fun validName(name: String) = name.trim().isNotEmpty() && name.trim().toByteArray(Charsets.UTF_8).size <= 120
    fun duplicatePhones(drafts: List<ContactDraft>): Set<String> =
        drafts.mapNotNull { normalize(it.phone, it.countryCode) }.groupingBy { it }.eachCount()
            .filterValues { it > 1 }.keys
}
