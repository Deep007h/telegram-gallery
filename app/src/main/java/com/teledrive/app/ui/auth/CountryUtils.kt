package com.teledrive.app.ui.auth

data class CountryInfo(
    val name: String,
    val callingCode: String,
    val flagEmoji: String,
    val isoCode: String
)

object CountryUtils {
    val commonCountries = listOf(
        CountryInfo("India", "+91", "\uD83C\uDDEE\uD83C\uDDF3", "IN"),
        CountryInfo("United States", "+1", "\uD83C\uDDFA\uD83C\uDDF8", "US"),
        CountryInfo("United Kingdom", "+44", "\uD83C\uDDEC\uD83C\uDDE7", "GB"),
        CountryInfo("Canada", "+1", "\uD83C\uDDE8\uD83C\uDDE6", "CA"),
        CountryInfo("Germany", "+49", "\uD83C\uDDE9\uD83C\uDDEA", "DE"),
        CountryInfo("France", "+33", "\uD83C\uDDE8\uD83C\uDDF7", "FR"),
        CountryInfo("Russia", "+7", "\uD83C\uDDF7\uD83C\uDDFA", "RU"),
        CountryInfo("Brazil", "+55", "\uD83C\uDDE7\uD83C\uDDF7", "BR"),
        CountryInfo("Indonesia", "+62", "\uD83C\uDDEE\uD83C\uDDE9", "ID"),
        CountryInfo("Pakistan", "+92", "\uD83C\uDDF5\uD83C\uDDF0", "PK"),
        CountryInfo("Bangladesh", "+880", "\uD83C\uDDE7\uD83C\uDDE9", "BD"),
        CountryInfo("Nigeria", "+234", "\uD83C\uDDF3\uD83C\uDDEC", "NG"),
        CountryInfo("Egypt", "+20", "\uD83C\uDDEA\uD83C\uDDEC", "EG"),
        CountryInfo("Turkey", "+90", "\uD83C\uDDF9\uD83C\uDDF7", "TR"),
        CountryInfo("Italy", "+39", "\uD83C\uDDEE\uD83C\uDDF9", "IT"),
        CountryInfo("Spain", "+34", "\uD83C\uDDEA\uD83C\uDDF8", "ES"),
        CountryInfo("Ukraine", "+380", "\uD83C\uDDFA\uD83C\uDDE6", "UA"),
        CountryInfo("Poland", "+48", "\uD83C\uDDF5\uD83C\uDDF1", "PL"),
        CountryInfo("Australia", "+61", "\uD83C\uDDE6\uD83C\uDDFA", "AU"),
        CountryInfo("United Arab Emirates", "+971", "\uD83C\uDDE6\uD83C\uDDEA", "AE"),
        CountryInfo("Saudi Arabia", "+966", "\uD83C\uDDF8\uD83C\uDDE6", "SA")
    )
}
