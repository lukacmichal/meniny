package sk.lukac.meniny

/**
 * Nastavenia self-updatu z NAS-u cez SMB. Kazda appka ma na NAS-e vlastny
 * podpriecinok v share-i "Android" a v nom jeden subor s pevnym menom, ktory
 * sa pri vydani prepise.
 *
 * Predvolby vratane mena a hesla prichadzaju z `BuildConfig`, kam ich vlozil
 * build zo spolocneho suboru nas-credentials.local. Appka je teda po
 * instalacii rovno nastavena; polia v Nastaveniach sluzia uz len na prepis.
 */
data class UpdateConfig(
    val host: String = BuildConfig.NAS_HOST.ifBlank { "nas.local" },
    val shareName: String = BuildConfig.NAS_SHARE.ifBlank { "Android" },
    val remotePath: String = "Meniny",
    val apkFileName: String = "meniny.apk",
    val username: String = BuildConfig.NAS_USER,
    val password: String = BuildConfig.NAS_PASS,
    /** Vacsinou prazdna; Synology domain/workgroup ak ho mas nastaveny. */
    val domain: String = ""
) {
    val isConfigured: Boolean get() = host.isNotEmpty() && username.isNotEmpty()
}
