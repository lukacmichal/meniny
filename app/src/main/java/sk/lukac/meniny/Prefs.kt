package sk.lukac.meniny

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Ulozenie nastaveni appky: sledovani oslavenci a pristup k NAS-u.
 *
 * Meniny nemaju vlastny server ani ucet, takze okrem pripadneho prepisu
 * pristupu k NAS-u pre self-update tu ziadne uctove udaje nie su. Predvolby
 * self-updatu prichadzaju z BuildConfig (vlozil ich build z
 * nas-credentials.local), takze po instalacii je appka rovno nastavena
 * a tieto polia ostavaju prazdne.
 *
 * Zoznam oslavencov je obycajny JSON v NEsifrovanych prefs. Su to mena z okolia,
 * nie tajomstva — a sifrovanie by znamenalo, ze na telefone s rozbitym keystore
 * prestanu chodit pripomienky.
 *
 * Heslo lezi v SIFROVANYCH prefs, kluc drzi Android Keystore. Ak by sa keystore
 * na telefone rozbil, padame spat na obycajne prefs, aby appka fungovala aj
 * vtedy — rovnaky pristup ako wake-nb, stocks a homesecure.
 */
object Prefs {
    private const val TAG = "Prefs"
    private const val FILE = "meniny_prefs"
    private const val FILE_SECRET = "meniny_secret"

    // self-update (SMB na NAS); prazdne = pouzi predvolbu z BuildConfig
    private const val K_UP_HOST = "up_host"
    private const val K_UP_SHARE = "up_share"
    private const val K_UP_PATH = "up_path"
    private const val K_UP_FILE = "up_file"
    private const val K_UP_USER = "up_user"
    private const val K_UP_PASS = "up_pass"

    // Pripomienky oslavencov (nesuvisi so self-updatom, ale ide o to iste
    // ulozisko — heslo si zije zvlast v sifrovanych prefs).
    private const val K_PRIP_ZAP = "prip_zap"
    private const val K_PRIP_HODINA = "prip_hodina"
    private const val K_PRIP_MINUTA = "prip_minuta"
    private const val K_OSLAVENCI = "oslavenci"

    /** Predvoleny cas pripomienky: rano, kym sa da este zavolat alebo napisat. */
    const val PRIP_HODINA_PREDVOLENA = 8
    const val PRIP_MINUTA_PREDVOLENA = 0

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Volatile
    private var secretCache: SharedPreferences? = null

    /** Sifrovane prefs; pri zlyhani keystore padni na obycajne, nech appka zije. */
    private fun secret(ctx: Context): SharedPreferences {
        secretCache?.let { return it }
        val app = ctx.applicationContext
        val prefs = try {
            val key = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                app, FILE_SECRET, key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            Log.w(TAG, "sifrovane prefs nedostupne, pouzivam obycajne", e)
            app.getSharedPreferences(FILE_SECRET, Context.MODE_PRIVATE)
        }
        secretCache = prefs
        migrateSecrets(app, prefs)
        return prefs
    }

    /**
     * Presun hesla zo starych necifrovanych prefs.
     *
     * Bez tohto by po aktualizacii appky zmizlo ulozene heslo a pouzivatel by
     * ho musel zadat znova. Z povodneho suboru sa hned maze — inak by tam
     * ostalo v citatelnej podobe a cely presun by nemal zmysel.
     */
    private fun migrateSecrets(ctx: Context, target: SharedPreferences) {
        val plain = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val pass = plain.getString(K_UP_PASS, null) ?: return
        target.edit().putString(K_UP_PASS, pass).apply()
        plain.edit().remove(K_UP_PASS).apply()
        Log.i(TAG, "heslo presunute do sifrovanych prefs")
    }

    /**
     * Nastavenia self-updatu: prazdne pole = predvolba z buildu.
     *
     * Heslo zhodne s BuildConfig sa neuklada — inak by po zmene hesla
     * v nas-credentials.local a rebuilde appka stale pouzivala stary ulozeny
     * prepis a self-update by prestal fungovat bez zjavnej priciny.
     */
    fun updateConfig(ctx: Context): UpdateConfig {
        val p = sp(ctx)
        val d = UpdateConfig()
        return UpdateConfig(
            host = p.getString(K_UP_HOST, "").orEmpty().ifBlank { d.host },
            shareName = p.getString(K_UP_SHARE, "").orEmpty().ifBlank { d.shareName },
            remotePath = p.getString(K_UP_PATH, "").orEmpty().ifBlank { d.remotePath },
            apkFileName = p.getString(K_UP_FILE, "").orEmpty().ifBlank { d.apkFileName },
            username = p.getString(K_UP_USER, "").orEmpty().ifBlank { d.username },
            password = secret(ctx).getString(K_UP_PASS, "").orEmpty().ifBlank { d.password },
        )
    }

    fun saveUpdateConfig(
        ctx: Context,
        host: String,
        share: String,
        path: String,
        file: String,
        user: String,
        pass: String,
    ) {
        sp(ctx).edit()
            .putString(K_UP_HOST, host.trim())
            .putString(K_UP_SHARE, share.trim())
            .putString(K_UP_PATH, path.trim())
            .putString(K_UP_FILE, file.trim())
            .putString(K_UP_USER, user.trim())
            .apply()
        // Zhodne s buildom = neukladat (viz komentar pri updateConfig).
        val cistene = pass.trim()
        secret(ctx).edit().apply {
            if (cistene.isEmpty() || cistene == BuildConfig.NAS_PASS) remove(K_UP_PASS)
            else putString(K_UP_PASS, cistene)
        }.apply()
    }

    // ---- pripomienky oslavencov ------------------------------------------

    fun pripomienkyZapnute(ctx: Context): Boolean = sp(ctx).getBoolean(K_PRIP_ZAP, false)

    fun pripomienkaHodina(ctx: Context): Int =
        sp(ctx).getInt(K_PRIP_HODINA, PRIP_HODINA_PREDVOLENA)

    fun pripomienkaMinuta(ctx: Context): Int =
        sp(ctx).getInt(K_PRIP_MINUTA, PRIP_MINUTA_PREDVOLENA)

    fun nastavPripomienky(ctx: Context, zapnute: Boolean, hodina: Int, minuta: Int) {
        sp(ctx).edit()
            .putBoolean(K_PRIP_ZAP, zapnute)
            .putInt(K_PRIP_HODINA, hodina)
            .putInt(K_PRIP_MINUTA, minuta)
            .apply()
    }

    fun oslavenci(ctx: Context): List<Oslavenec> =
        Oslavenci.zJson(sp(ctx).getString(K_OSLAVENCI, "[]").orEmpty())

    fun ulozOslavencov(ctx: Context, zoznam: List<Oslavenec>) {
        sp(ctx).edit().putString(K_OSLAVENCI, Oslavenci.doJson(zoznam)).apply()
    }
}
