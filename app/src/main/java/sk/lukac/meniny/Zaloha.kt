package sk.lukac.meniny

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Export a import nastaveni do jedneho JSON suboru.
 *
 * Na co to je: zoznam oslavencov je jedina vec v tejto appke, ktoru clovek
 * naozaj vytvoril rukou — dna, mena, roky narodenia. Kalendar menin je v APK
 * a po preinstalovani sa vrati sam, oslavenci nie. A `allowBackup="false"`
 * v manifeste znamena, ze to za nas neurobi ani Google (a to je zamer, viz
 * docs/shared-standard.md 2.5: inak by sifrovane heslo odislo do cudzieho cloudu).
 *
 * **Heslá sa neexportuju.** Nie z opatrnosti "pre istotu", ale preto, ze by
 * v subore boli navyse: appka sa buduje s udajmi z `nas-credentials.local`,
 * takze po importe na cistej instalacii sa k NAS-u prihlasi aj tak.
 *
 * Format je zamerne obycajny JSON s [KLUC_VERZIA] navrchu — da sa otvorit
 * v poznamkovom bloku a v pripade potreby aj rucne opravit.
 */
object Zaloha {

    const val KLUC_VERZIA = "verzia"
    const val VERZIA = 1
    const val MIME = "application/json"

    private const val K_APPKA = "appka"
    private const val K_VYTVORENE = "vytvorene"
    private const val K_OSLAVENCI = "oslavenci"
    private const val K_PRIPOMIENKY = "pripomienky"
    private const val K_NAS = "nas"

    fun menoSuboru(dnes: String): String = "meniny-nastavenia-$dnes.json"

    // --- export ---

    fun json(ctx: Context, cas: Long = System.currentTimeMillis()): String {
        val cfg = Prefs.updateConfig(ctx)
        val nas = JSONObject()
            .put("host", cfg.host)
            .put("share", cfg.shareName)
            .put("cesta", cfg.remotePath)
            .put("subor", cfg.apkFileName)
            .put("meno", cfg.username)

        val prip = JSONObject()
            .put("zapnute", Prefs.pripomienkyZapnute(ctx))
            .put("hodina", Prefs.pripomienkaHodina(ctx))
            .put("minuta", Prefs.pripomienkaMinuta(ctx))

        return JSONObject()
            .put(KLUC_VERZIA, VERZIA)
            .put(K_APPKA, "meniny")
            .put(K_VYTVORENE, cas)
            // Zoznam ide cez `Oslavenci.doJson`, teda presne v tom tvare, v akom
            // ho appka uklada. Druhy format by znamenal druhe miesto, kde sa da
            // pri zmene modelu zabudnut.
            .put(K_OSLAVENCI, JSONArray(Oslavenci.doJson(Prefs.oslavenci(ctx))))
            .put(K_PRIPOMIENKY, prip)
            .put(K_NAS, nas)
            .toString(2)
    }

    // --- import ---

    data class Obsah(
        val oslavenci: List<Oslavenec>,
        val pripomienkyZapnute: Boolean?,
        val hodina: Int?,
        val minuta: Int?,
        val host: String,
        val share: String,
        val cesta: String,
        val subor: String,
        val meno: String,
    )

    /**
     * @throws IllegalArgumentException ked to nie je zaloha tejto appky alebo
     *   je z novsej verzie. Ticho ignorovat cudzi subor by znamenalo, ze
     *   clovek vidi "Naimportovane" a nic sa nezmenilo.
     */
    fun precitaj(text: String): Obsah {
        val j = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("súbor nie je JSON")
        }
        val v = j.optInt(KLUC_VERZIA, 0)
        if (v == 0) throw IllegalArgumentException("súbor nie je záloha tejto appky")
        if (v > VERZIA) {
            throw IllegalArgumentException(
                "záloha je z novšej verzie appky ($v) — najprv aktualizuj appku"
            )
        }

        val ludia = j.optJSONArray(K_OSLAVENCI)?.let { Oslavenci.zJson(it.toString()) }
            ?: emptyList()

        val prip = j.optJSONObject(K_PRIPOMIENKY)
        val nas = j.optJSONObject(K_NAS) ?: JSONObject()
        return Obsah(
            oslavenci = ludia,
            pripomienkyZapnute = prip?.optBoolean("zapnute"),
            // Neplatny cas zo zle upraveneho suboru by ticho posunul budik;
            // radsej sa ignoruje a ostane, co v appke uz je.
            hodina = prip?.optInt("hodina", -1)?.takeIf { it in 0..23 },
            minuta = prip?.optInt("minuta", -1)?.takeIf { it in 0..59 },
            host = nas.optString("host"),
            share = nas.optString("share"),
            cesta = nas.optString("cesta"),
            subor = nas.optString("subor"),
            meno = nas.optString("meno"),
        )
    }

    /**
     * Zapise obsah zalohy do nastaveni.
     *
     * Oslavenci sa **nahradzuju**, nie zlucuju: import je "vrat mi to, ako to
     * bolo", a zlucovanie by po dvoch importoch nechalo zoznam, aky nikdy
     * neexistoval. Prazdny zoznam v zalohe sa ignoruje — inak by chybny subor
     * vymazal vsetkych ludi.
     */
    fun pouzi(ctx: Context, o: Obsah) {
        if (o.oslavenci.isNotEmpty()) Prefs.ulozOslavencov(ctx, o.oslavenci)
        if (o.pripomienkyZapnute != null) {
            Prefs.nastavPripomienky(
                ctx,
                o.pripomienkyZapnute,
                o.hodina ?: Prefs.pripomienkaHodina(ctx),
                o.minuta ?: Prefs.pripomienkaMinuta(ctx),
            )
        }
        val teraz = Prefs.updateConfig(ctx)
        Prefs.saveUpdateConfig(
            ctx,
            host = o.host.ifBlank { teraz.host },
            share = o.share.ifBlank { teraz.shareName },
            path = o.cesta.ifBlank { teraz.remotePath },
            file = o.subor.ifBlank { teraz.apkFileName },
            user = o.meno.ifBlank { teraz.username },
            pass = teraz.password,
        )
    }
}
