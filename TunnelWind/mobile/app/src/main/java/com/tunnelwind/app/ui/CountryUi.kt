package com.tunnelwind.app.ui

/** ISO country code -> readable name */
fun CountryName(code: String): String = when (code.uppercase()) {
    "AT" -> "Austria"; "AU" -> "Australia"; "BE" -> "Belgium"; "BR" -> "Brazil"
    "CA" -> "Canada"; "CH" -> "Switzerland"; "CZ" -> "Czechia"; "DE" -> "Germany"
    "DK" -> "Denmark"; "ES" -> "Spain"; "FI" -> "Finland"; "FR" -> "France"
    "GB" -> "United Kingdom"; "HR" -> "Croatia"; "HK" -> "Hong Kong"; "HU" -> "Hungary"
    "ID" -> "Indonesia"; "IE" -> "Ireland"; "IN" -> "India"; "IT" -> "Italy"
    "JP" -> "Japan"; "KR" -> "South Korea"; "LT" -> "Lithuania"; "MX" -> "Mexico"
    "MY" -> "Malaysia"; "NL" -> "Netherlands"; "NO" -> "Norway"; "NZ" -> "New Zealand"
    "PH" -> "Philippines"; "PL" -> "Poland"; "PT" -> "Portugal"; "RO" -> "Romania"
    "RS" -> "Serbia"; "SE" -> "Sweden"; "SG" -> "Singapore"; "TH" -> "Thailand"
    "TR" -> "Turkiye"; "UA" -> "Ukraine"; "US" -> "United States"; "VN" -> "Vietnam"
    "ZA" -> "South Africa"
    else -> code
}

/** flag emoji from regional indicator symbols */
fun flagEmoji(code: String): String {
    val c = code.uppercase()
    if (c.length != 2) return ""
    val cp1 = 0x1F1E6 + (c[0] - 'A')
    val cp2 = 0x1F1E6 + (c[1] - 'A')
    val sb = StringBuilder()
    sb.appendCodePoint(cp1)
    sb.appendCodePoint(cp2)
    return sb.toString()
}
