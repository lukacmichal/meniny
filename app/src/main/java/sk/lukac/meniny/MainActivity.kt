package sk.lukac.meniny

import android.app.AlertDialog
import android.content.Context
import android.net.ConnectivityManager
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.time.LocalDate

/**
 * Hlavna obrazovka: od dnesneho dna dopredu, s vyhladavanim.
 *
 * Vsetko je offline nad assetom — ziadne sietove volanie tu nie je okrem
 * kontroly novej verzie v paticke.
 */
class MainActivity : ZakladActivity() {

    /** Kolko dni dopredu sa ukaze v zakladnom zozname. */
    private val dniDopredu = 120

    private lateinit var data: MeninyData
    private lateinit var zoznam: RecyclerView
    private lateinit var prazdne: TextView
    private lateinit var adapter: Adapter
    private lateinit var potiahni: SwipeRefreshLayout

    /** Sledovani ludia; nacitavaju sa v onResume, lebo sa menia na inej obrazovke. */
    private var ludia: List<Oslavenec> = emptyList()


    /** Nastavenia sa mozu vratit s prosbou skontrolovat aktualizaciu hned. */
    private val nastavenia = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { vysledok ->
        if (vysledok.resultCode == NastaveniaActivity.VYSLEDOK_SKONTROLUJ) {
            skontrolujAktualizaciu(manualne = true)
        }
    }

    override fun onCreate(stav: Bundle?) {
        super.onCreate(stav)
        setContentView(R.layout.activity_main)

        data = MeninyRepo.data(this)
        zoznam = findViewById(R.id.zoznam)
        prazdne = findViewById(R.id.prazdne)
        adapter = Adapter()
        zoznam.layoutManager = LinearLayoutManager(this)
        zoznam.adapter = adapter

        val pole: EditText = findViewById(R.id.hladanie)
        val zrus: ImageButton = findViewById(R.id.zrus)
        pole.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val dopyt = s?.toString().orEmpty()
                zrus.visibility = if (dopyt.isBlank()) View.GONE else View.VISIBLE
                ukaz(dopyt)
            }

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
        zrus.setOnClickListener { pole.setText("") }

        // Potiahnutie dole = obnov. Meniny sa nesťahujú (sú v APK), takže sa
        // prekreslí dnešok a pri tej príležitosti sa pozrie na NAS, či nie je
        // nová verzia — gesto je vo všetkých appkách rovnaké (docs/shared-standard.md 2.6).
        potiahni = findViewById(R.id.potiahni)
        potiahni.setOnRefreshListener { obnov() }

        ukaz("")

        val paticka: TextView = findViewById(R.id.verzia)
        VersionUi.render(paticka, null)
        // Tuknutie na paticku = "skontroluj hned", aj na meranom pripojeni.
        paticka.setOnClickListener { skontrolujAktualizaciu(manualne = true) }

        // Nastavenia self-updatu. Vracaju sa s VYSLEDOK_SKONTROLUJ, ked si tam
        // clovek vypytal kontrolu — tá zije tu, nech nie je na dvoch miestach.
        // Ozubene koliecko vpravo hore, rovnako ako v ostatnych appkach; textovy
        // odkaz dole zanikol, lebo v kazdej appke sedel inde.
        findViewById<ImageButton>(R.id.nastavenia_ikona).setOnClickListener {
            nastavenia.launch(Intent(this, NastaveniaActivity::class.java))
        }
        findViewById<ImageButton>(R.id.obnov).setOnClickListener { obnov() }
        findViewById<Button>(R.id.oslavenci).setOnClickListener {
            startActivity(Intent(this, OslavenciActivity::class.java))
        }
        skontrolujAktualizaciu(manualne = false)

        // Budiky nepreziju restart telefonu ani reinstalaciu appky. Receiver na
        // BOOT_COMPLETED to riesi, ale kym ho system prvy raz zavola, moze
        // ubehnut cas — a otvorenie appky je najlacnejsie miesto, kde sa da
        // retaz obnovit.
        Pripomienky.naplanuj(this)
    }

    /**
     * Kontrola aktualizacie. Cita LEN `output-metadata.json` (par stoviek
     * bajtov) — cele APK sa tiahne az po potvrdeni.
     *
     * Pri starte (manualne = false) sa na NAS siaha len na nemeranom pripojeni
     * a nedostupny NAS sa ticho ignoruje: appka funguje offline a hlaska
     * "NAS nedostupny" pri kazdom otvoreni by len prekazala. Po tuknuti na
     * paticku sa skusa vzdy a zlyhanie sa aj povie — clovek si o to prave
     * povedal (viz docs/shared-standard.md kap. 6).
     */

    /**
     * Kontrola narazila na chybajuce povolenie instalovat APK. Po navrate zo
     * systemovych nastaveni sa skusi znova — inak by clovek povolenie zapol
     * a aj tak sa nic nestalo, kym appku nezavrie a neotvori.
     */
    private var cakaNaPovolenie = false

    private fun skontrolujAktualizaciu(manualne: Boolean) {
        val cfg = Prefs.updateConfig(this)
        if (!cfg.isConfigured) return
        if (!manualne) {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm == null || cm.isActiveNetworkMetered) return
        }

        Thread {
            val vzdialena = runCatching {
                val j = org.json.JSONObject(SmbUpdater.readText(cfg, "output-metadata.json"))
                    .getJSONArray("elements").getJSONObject(0)
                j.getLong("versionCode") to j.optString("versionName").ifBlank { "?" }
            }.getOrNull()

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val paticka: TextView = findViewById(R.id.verzia)
                if (vzdialena == null) {
                    if (manualne) toast(getString(R.string.up_error, "NAS je nedostupný"))
                    return@runOnUiThread
                }
                val (kod, meno) = vzdialena
                val teraz = ApkInstaller.currentVersionCode(this)
                val novsia = kod > teraz
                VersionUi.render(paticka, "$meno ($kod)", novsia)

                if (!novsia) {
                    if (manualne) toast(getString(R.string.up_latest, teraz))
                    return@runOnUiThread
                }
                // Bez povolenia "Instalovat nezname aplikacie" sa APK odovzdat
                // nedá. Appka to POVIE — predtym len ticho otvorila systemove
                // nastavenia a kto sa z nich vratil, uz sa aktualizacie nedockal
                // (19. 8. 2026: "pise inu verziu, ale sama sa neaktualizuje").
                // Tiche kontroly pri starte obrazovku nehijackuju vobec:
                // pytat sa o povolenie ma zmysel len vtedy, ked o aktualizaciu
                // clovek prave poziadal (docs/shared-standard.md 2.4 — "nedostupny NAS = ticho").
                if (!ApkInstaller.canInstall(this)) {
                    cakaNaPovolenie = true
                    if (manualne) {
                        toast(getString(R.string.up_need_perm))
                        ApkInstaller.openInstallPermissionSettings(this)
                    }
                    return@runOnUiThread
                }
                AlertDialog.Builder(this)
                    // Nadpis aj obe verzie su z docs/shared-standard.md 2.6 §2: bez toho,
                    // co mas TERAZ, sa z ponuky neda poznat, ci je skok o jednu
                    // verziu alebo o pat.
                    .setTitle(R.string.up_prompt_title)
                    .setMessage(getString(R.string.up_new, "$meno ($kod)",
                                          VersionUi.installed(this)))
                    .setPositiveButton(R.string.up_install) { _, _ -> stiahniANainstaluj(cfg) }
                    .setNegativeButton(R.string.up_later, null)
                    .show()
            }
        }.start()
    }

    private fun stiahniANainstaluj(cfg: UpdateConfig) {
        toast(getString(R.string.up_downloading))
        Thread {
            val ciel = ApkInstaller.downloadTarget(this, cfg.apkFileName)
            val apk = runCatching { SmbUpdater.download(cfg, ciel) }.getOrNull()
            // Najvacsi jednorazovy prenos, aky appka spravi — bez toho by
            // pocitadlo mlcalo prave o tych megabajtoch, na ktorych zalezi.
            if (apk != null) Prenos.zapis(this, apk.length())
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (apk == null) {
                    toast(getString(R.string.up_error, "sťahovanie zlyhalo"))
                    return@runOnUiThread
                }
                // Verzia sa pred instalaciou cita este raz, uz z realneho APK:
                // metadata na NAS-e sa teoreticky mozu rozist s tym, co tam lezi.
                if (ApkInstaller.readVersionCode(this, apk) == null) {
                    toast(getString(R.string.up_bad_apk))
                    return@runOnUiThread
                }
                ApkInstaller.install(this, apk)
            }
        }.start()
    }

    /**
     * Prekresli zoznam k dnesnemu dnu a pozri sa na NAS po novej verzii.
     *
     * Kalendar je v APK, takze "obnovit" tu neznamena stiahnut data — znamena
     * to zosuladit obrazovku so skutocnym datumom (appka moze v pozadi prezit
     * polnoc) a skontrolovat aktualizaciu, o ktoru clovek prave gestom poziadal.
     */
    private fun obnov() {
        val pole: EditText = findViewById(R.id.hladanie)
        ukaz(pole.text?.toString().orEmpty())
        skontrolujAktualizaciu(manualne = true)
        if (::potiahni.isInitialized) potiahni.isRefreshing = false
    }

    /**
     * Po minimalizovani sa hladanie zahodi.
     *
     * Appka sa otvara kvoli otazke "kto ma dnes meniny" — a kto ju naposledy
     * zavrel s vyhladanym menom, tomu sa po tyzdni otvorila s tym istym
     * zoznamom jedneho mena namiesto dnesnych menin.
     */
    override fun onStop() {
        super.onStop()
        val pole: EditText = findViewById(R.id.hladanie)
        if (!pole.text.isNullOrBlank()) pole.setText("")
    }

    private fun toast(text: String) =
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()

    override fun onResume() {
        super.onResume()
        // Vratil sa z nastaveni a povolenie uz ma? Dokonci, o co ziadal.
        if (cakaNaPovolenie && ApkInstaller.canInstall(this)) {
            cakaNaPovolenie = false
            skontrolujAktualizaciu(manualne = true)
        }
        // Zoznam sledovanych ludi sa meni na inej obrazovke; po navrate sa
        // musia riadky prekreslit, inak by tam prave pridany clovek chybal.
        ludia = Prefs.oslavenci(this)
        ukaz(
            findViewById<EditText>(R.id.hladanie).text?.toString().orEmpty(),
            naVrch = false,
        )
    }

    /**
     * Prekresli zoznam. [naVrch] = posunut na zaciatok — pri zmene dopytu ano
     * (novy vysledok sa ma zacat citat zhora), po pridani oslavenca alebo po
     * navrate z inej obrazovky nie: clovek by prisiel o miesto, kde bol.
     */
    private fun ukaz(dopyt: String, naVrch: Boolean = true) {
        val polozky = if (dopyt.isBlank()) {
            data.odDatumu(LocalDate.now(), dniDopredu)
                .mapNotNull { (datum, den) -> den?.let { Polozka(datum, it, null) } }
        } else {
            data.hladaj(dopyt).map { (den, meno) -> Polozka(datumPre(den), den, meno) }
        }
        adapter.nastav(polozky)
        prazdne.visibility = if (polozky.isEmpty()) View.VISIBLE else View.GONE
        zoznam.visibility = if (polozky.isEmpty()) View.GONE else View.VISIBLE
        if (polozky.isNotEmpty() && naVrch) zoznam.scrollToPosition(0)
    }

    /**
     * Datum vysledku hladania v najblizsom vyskyte od dneska.
     *
     * Meniny su viazane na den v roku, nie na rok — kto hlada "Elena" v septembri,
     * chce vidiet buduci august, nie ten, ktory uz presiel.
     */
    private fun datumPre(den: Den): LocalDate {
        val dnes = LocalDate.now()
        val tento = LocalDate.of(dnes.year, den.mesiac, den.den)
        return if (tento.isBefore(dnes)) tento.plusYears(1) else tento
    }

    /**
     * Tuknutie na den = "chcem, aby mi appka toto meno pripomenula".
     *
     * Je to najkratsia cesta k pripomienke: clovek prave pozera na den, ktory
     * ho zaujima. Vyber mena je zaroven potvrdenie — jedno tuknutie do zoznamu
     * by inak ticho pridalo cloveka, ktoreho nikto nechcel.
     */
    private fun ponukniSledovanie(p: Polozka) {
        val mena = (p.zhoda?.let { listOf(it.meno) } ?: p.den.mena.map { it.meno })
            .filter { it.isNotBlank() }
        if (mena.isEmpty()) {
            toast(getString(R.string.osl_den_bez_mena))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.osl_pridat_koho)
            .setItems(mena.toTypedArray()) { _, i -> sleduj(mena[i], p.den.kluc) }
            .setNegativeButton(R.string.osl_zrusit, null)
            .show()
    }

    private fun sleduj(meno: String, kluc: String) {
        val uz = Prefs.oslavenci(this)
        if (uz.any { it.meno.equals(meno, ignoreCase = true) && it.meniny == kluc }) {
            toast(getString(R.string.osl_uz_je, meno))
            return
        }
        Prefs.ulozOslavencov(this, uz + Oslavenec(meno = meno, meniny = kluc))
        ludia = Prefs.oslavenci(this)
        // Zoznam meni budik aj widget — rovnako ako na obrazovke oslavencov.
        Pripomienky.naplanuj(this)
        MeninyWidgetProvider.prekresliVsetky(this)
        ukaz(
            findViewById<EditText>(R.id.hladanie).text?.toString().orEmpty(),
            naVrch = false,
        )
        toast(getString(R.string.osl_pridany, meno))
    }

    private data class Polozka(
        val datum: LocalDate,
        val den: Den,
        /** Pri hladani zvyraznene meno; v zakladnom zozname null. */
        val zhoda: Meno?,
    )

    private inner class Adapter : RecyclerView.Adapter<Adapter.Drzitel>() {

        private var polozky: List<Polozka> = emptyList()

        fun nastav(nove: List<Polozka>) {
            polozky = nove
            notifyDataSetChanged()
        }

        override fun getItemCount() = polozky.size

        override fun onCreateViewHolder(rodic: ViewGroup, typ: Int) = Drzitel(
            LayoutInflater.from(rodic.context).inflate(R.layout.item_den, rodic, false)
        )

        override fun onBindViewHolder(d: Drzitel, i: Int) = d.napln(polozky[i])

        inner class Drzitel(v: View) : RecyclerView.ViewHolder(v) {
            private val riadok: View = v.findViewById(R.id.riadok)
            private val datum: TextView = v.findViewById(R.id.datum)
            private val mena: TextView = v.findViewById(R.id.mena)
            private val popis: TextView = v.findViewById(R.id.popis)
            private val oslavenci: TextView = v.findViewById(R.id.oslavenci)
            private val sviatky: TextView = v.findViewById(R.id.sviatky)

            fun napln(p: Polozka) {
                datum.text = Datumy.popis(itemView.context, p.datum)
                oznacDnesok(p.datum == LocalDate.now())
                riadok.setOnClickListener { ponukniSledovanie(p) }

                // Pri hladani je zaujimave najdene meno, nie cely den.
                val hlavne = p.zhoda
                mena.text = when {
                    hlavne != null -> hlavne.meno
                    p.den.mena.isEmpty() -> itemView.context.getString(R.string.ziadne_meno)
                    else -> p.den.menaSpolu
                }

                val detail = when {
                    hlavne != null -> {
                        // Pri zhode ukaz aj ostatne mena toho dna, nech je vidiet kontext.
                        val ostatne = p.den.mena.filter { it.meno != hlavne.meno }
                        listOfNotNull(
                            vyznamText(hlavne),
                            if (ostatne.isEmpty()) null
                            else "aj " + ostatne.joinToString(", ") { it.meno },
                        ).joinToString(" · ")
                    }
                    else -> p.den.mena.mapNotNull { vyznamText(it) }.joinToString(" · ")
                }
                popis.text = detail
                popis.visibility = if (detail.isBlank()) View.GONE else View.VISIBLE

                // Kto zo sledovanych ludi v ten den slavi. Narodeniny v kalendari
                // nie su, takze bez tohto riadku by ich zoznam vobec neukazal.
                // Kazdy oslavenec na vlastnom riadku: v pravom stlpci sirokom
                // 150 dp by sa dvaja oddeleni bodkou aj tak zalomili, len na
                // nahodnom mieste.
                val oslavy = Oslavenci.preDatum(ludia, p.datum)
                    .joinToString("\n") { Pripomienky.kratky(itemView.context, it) }
                oslavenci.text = oslavy
                oslavenci.visibility = if (oslavy.isBlank()) View.GONE else View.VISIBLE

                val sv = p.den.sviatky.joinToString(" · ")
                sviatky.text = sv
                sviatky.visibility = if (sv.isBlank()) View.GONE else View.VISIBLE
            }

            /**
             * Dnesny riadok dostane pruh vlavo, jemne pozadie a farebny datum.
             *
             * Text "Dnes ·" v datume sam nestaci: v zozname 120 dni po sebe
             * vyzeraju vsetky riadky rovnako a oko sa nema coho chytit.
             * Padding sa nastavuje znova, lebo setBackgroundResource ho vie
             * prepisat podla pozadia.
             */
            private fun oznacDnesok(jeDnes: Boolean) {
                val ctx = itemView.context
                val vodorovne = riadok.paddingLeft
                val zvisle = riadok.paddingTop
                riadok.setBackgroundResource(if (jeDnes) R.drawable.dnes_pozadie else 0)
                riadok.setPadding(vodorovne, zvisle, vodorovne, zvisle)
                datum.setTextColor(
                    ContextCompat.getColor(
                        ctx,
                        if (jeDnes) R.color.zvyraznenie else R.color.text_vedlajsi,
                    ),
                )
                datum.setTypeface(
                    null,
                    if (jeDnes) android.graphics.Typeface.BOLD
                    else android.graphics.Typeface.NORMAL,
                )
            }

            /** "Elena — svetlo (grecky povod)". Prazdne pre mena bez popisu. */
            private fun vyznamText(m: Meno): String? {
                if (!m.maPopis) return null
                val casti = listOfNotNull(
                    m.vyznam.ifBlank { null },
                    m.povod.ifBlank { null }?.let { "($it)" },
                )
                return "${m.meno}: " + casti.joinToString(" ")
            }
        }
    }
}
