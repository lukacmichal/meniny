package sk.lukac.meniny

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.time.LocalDate

/**
 * Koho si dat pripomenut: zoznam ludi, ich meniny a narodeniny.
 *
 * Zoznam je zamerne oddeleny od Nastaveni. V Nastaveniach je JEDNA vec — ci
 * a kedy pripomienka zvoni — a tu je to, co sa meni castejsie. Keby boli spolu,
 * clovek by pri kazdom pridani cloveka prerolovaval formular self-updatu.
 *
 * Po kazdej zmene sa prepocita budik a prekresli widget: tento zoznam je jediny
 * vstup, z ktoreho oboje zavisi.
 */
class OslavenciActivity : ZakladActivity() {

    private lateinit var data: MeninyData
    private lateinit var zoznam: RecyclerView
    private lateinit var prazdne: TextView
    private lateinit var adapter: Adapter
    private var ludia: List<Oslavenec> = emptyList()

    override fun onCreate(stav: Bundle?) {
        super.onCreate(stav)
        setContentView(R.layout.activity_oslavenci)
        data = MeninyRepo.data(this)

        zoznam = findViewById(R.id.zoznam)
        prazdne = findViewById(R.id.prazdne)
        adapter = Adapter()
        zoznam.layoutManager = LinearLayoutManager(this)
        zoznam.adapter = adapter

        findViewById<ImageButton>(R.id.pridaj).setOnClickListener { uprav(null) }
        // Spodny riadok hovori, ci a kedy pripomienka zvoni; tuknutim sa ide
        // presne tam, kde sa to da zmenit. Bez toho by clovek pridal ludi
        // a cakal na upozornenie, ktore ma vypnute.
        findViewById<TextView>(R.id.spodok).setOnClickListener {
            startActivity(Intent(this, NastaveniaActivity::class.java))
        }

        nacitaj()
    }

    override fun onResume() {
        super.onResume()
        nacitaj()
    }

    private fun nacitaj() {
        ludia = Oslavenci.zoradene(Prefs.oslavenci(this), LocalDate.now())
        adapter.notifyDataSetChanged()
        prazdne.visibility = if (ludia.isEmpty()) View.VISIBLE else View.GONE
        zoznam.visibility = if (ludia.isEmpty()) View.GONE else View.VISIBLE

        val spodok: TextView = findViewById(R.id.spodok)
        spodok.text = if (Prefs.pripomienkyZapnute(this)) {
            getString(
                R.string.osl_stav_zapnute,
                "%02d:%02d".format(
                    Prefs.pripomienkaHodina(this),
                    Prefs.pripomienkaMinuta(this),
                ),
            )
        } else {
            getString(R.string.osl_stav_vypnute)
        }
    }

    private fun uloz(novy: List<Oslavenec>) {
        Prefs.ulozOslavencov(this, novy)
        // Zoznam urcuje budik aj obsah widgetu — oboje sa musi zosuladit hned,
        // inak by widget svietil na oslavu, ktoru som prave zmazal.
        Pripomienky.naplanuj(this)
        MeninyWidgetProvider.prekresliVsetky(this)
        nacitaj()
    }

    /** Pridanie ([povodny] == null) alebo uprava existujuceho cloveka. */
    private fun uprav(povodny: Oslavenec?) {
        var meniny: String? = povodny?.meniny
        var narodeniny: String? = povodny?.narodeniny
        var rok: Int = povodny?.rok ?: 0

        val meno = EditText(this).apply {
            hint = getString(R.string.osl_meno)
            setText(povodny?.meno.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
        }
        val chMeniny = CheckBox(this)
        val btnMeniny = Button(this).apply {
            text = getString(R.string.osl_iny_den)
            setSingleLine()
        }
        val chNarodeniny = CheckBox(this)
        val btnNarodeniny = Button(this).apply {
            text = getString(R.string.osl_datum_narodenia)
            setSingleLine()
        }
        val chVek = CheckBox(this).apply { text = getString(R.string.osl_pocitat_vek) }

        fun prekresli() {
            chMeniny.isChecked = meniny != null
            chMeniny.text = meniny?.let { getString(R.string.osl_meniny_dna, denText(it)) }
                ?: getString(R.string.osl_sledovat_meniny)
            btnMeniny.visibility = if (meniny == null) View.GONE else View.VISIBLE

            chNarodeniny.isChecked = narodeniny != null
            chNarodeniny.text = narodeniny?.let {
                if (rok > 0) getString(R.string.osl_narodeniny_dna_rok, denText(it), rok)
                else getString(R.string.osl_narodeniny_dna, denText(it))
            } ?: getString(R.string.osl_sledovat_narodeniny)
            btnNarodeniny.visibility = if (narodeniny == null) View.GONE else View.VISIBLE
            chVek.visibility = if (narodeniny == null) View.GONE else View.VISIBLE
            chVek.isChecked = rok > 0
        }

        // Reaguje sa na KLIK, nie na zmenu stavu: setChecked v prekresli() by
        // listener spustil znova a zaskrtnutie by sa samo prepinalo.
        chMeniny.setOnClickListener {
            if (meniny != null) {
                meniny = null
                prekresli()
                return@setOnClickListener
            }
            // Ked je meno v kalendari, den sa doplni sam — to je bezny pripad
            // a vyberat ho rucne by bolo zbytocne klikanie.
            val zKalendara = data.klucMenin(meno.text.toString())
            if (zKalendara != null) {
                meniny = zKalendara
                prekresli()
            } else {
                vyberDen(meniny) { kluc -> meniny = kluc; prekresli() }
            }
        }
        btnMeniny.setOnClickListener {
            vyberDen(meniny) { kluc -> meniny = kluc; prekresli() }
        }

        chNarodeniny.setOnClickListener {
            if (narodeniny != null) {
                narodeniny = null
                prekresli()
            } else {
                vyberNarodeniny(narodeniny, rok) { kluc, r ->
                    narodeniny = kluc
                    rok = r
                    prekresli()
                }
            }
        }
        btnNarodeniny.setOnClickListener {
            vyberNarodeniny(narodeniny, rok) { kluc, r ->
                narodeniny = kluc
                rok = r
                prekresli()
            }
        }
        // Vek sa da vypnut bez toho, aby sa zahodil den narodenin — kto rok
        // nepozna, chce aj tak pripomienku.
        chVek.setOnClickListener {
            if (rok > 0) {
                rok = 0
                prekresli()
            } else {
                vyberNarodeniny(narodeniny, rok) { kluc, r ->
                    narodeniny = kluc
                    rok = r
                    prekresli()
                }
            }
        }

        prekresli()

        val okraj = (24 * resources.displayMetrics.density).toInt()
        val obsah = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(okraj, okraj / 2, okraj, 0)
            listOf(meno, chMeniny, btnMeniny, chNarodeniny, btnNarodeniny, chVek)
                .forEach { addView(it) }
        }

        AlertDialog.Builder(this)
            .setTitle(if (povodny == null) R.string.osl_novy else R.string.osl_uprava)
            .setView(ScrollView(this).apply { addView(obsah) })
            .setPositiveButton(R.string.osl_ulozit) { _, _ ->
                val vysledok = Oslavenec(
                    meno = meno.text.toString().trim(),
                    meniny = meniny,
                    narodeniny = narodeniny,
                    rok = rok,
                )
                if (!vysledok.platny) {
                    Toast.makeText(this, R.string.osl_neuplny, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val nove = ludia.toMutableList()
                // Identita, nie rovnost: dvaja rovnako pomenovani ludia
                // s rovnakym datumom su platny stav a upravit sa ma prave ten,
                // na ktory som klikol.
                val i = ludia.indexOfFirst { it === povodny }
                if (i >= 0) nove[i] = vysledok else nove += vysledok
                uloz(nove)
            }
            .setNegativeButton(R.string.osl_zrusit, null)
            .show()
    }

    /**
     * Vyber dna v roku. Rok sa zahadzuje — meniny sa viazu na den v kalendari,
     * nie na konkretny rok.
     */
    private fun vyberDen(teraz: String?, hotovo: (String) -> Unit) {
        val zaklad = datumZKluca(teraz) ?: LocalDate.now()
        DatePickerDialog(
            this,
            { _, _, mesiac, den -> hotovo("%02d-%02d".format(mesiac + 1, den)) },
            zaklad.year, zaklad.monthValue - 1, zaklad.dayOfMonth,
        ).show()
    }

    /** Vyber datumu narodenia aj s rokom (rok sluzi len na vek). */
    private fun vyberNarodeniny(teraz: String?, rok: Int, hotovo: (String, Int) -> Unit) {
        val zaklad = datumZKluca(teraz)?.let { if (rok > 0) it.withYear(rok) else it }
            ?: LocalDate.now().minusYears(30)
        DatePickerDialog(
            this,
            { _, r, mesiac, den -> hotovo("%02d-%02d".format(mesiac + 1, den), r) },
            zaklad.year, zaklad.monthValue - 1, zaklad.dayOfMonth,
        ).apply {
            // Datum narodenia v buducnosti je vzdy preklep a vysledkom by bol
            // zaporny vek.
            datePicker.maxDate = System.currentTimeMillis()
        }.show()
    }

    /**
     * "MM-DD" na datum v tomto roku. Pri 29. februari v nepriestupnom roku
     * vrati null — volajuci si s tym poradi sam.
     */
    private fun datumZKluca(kluc: String?): LocalDate? {
        if (kluc == null) return null
        return runCatching {
            LocalDate.of(
                LocalDate.now().year,
                kluc.substringBefore('-').toInt(),
                kluc.substringAfter('-').toInt(),
            )
        }.getOrNull()
    }

    /** "18. augusta" z kluca "08-18". */
    private fun denText(kluc: String): String {
        datumZKluca(kluc)?.let { return Datumy.kratky(this, it) }
        // 29. februar sa v nepriestupnom roku cez LocalDate nezostavi; ukaz ho
        // v priestupnom, nech v zozname nesviti holy kluc "02-29".
        return runCatching {
            Datumy.kratky(
                this,
                LocalDate.of(2024, kluc.substringBefore('-').toInt(), kluc.substringAfter('-').toInt()),
            )
        }.getOrDefault(kluc)
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Drzitel>() {

        override fun getItemCount() = ludia.size

        override fun onCreateViewHolder(rodic: ViewGroup, typ: Int) = Drzitel(
            LayoutInflater.from(rodic.context).inflate(R.layout.item_oslavenec, rodic, false)
        )

        override fun onBindViewHolder(d: Drzitel, i: Int) = d.napln(ludia[i])

        inner class Drzitel(v: View) : RecyclerView.ViewHolder(v) {
            private val riadok: View = v.findViewById(R.id.riadok)
            private val meno: TextView = v.findViewById(R.id.meno)
            private val detail: TextView = v.findViewById(R.id.detail)
            private val kedy: TextView = v.findViewById(R.id.kedy)
            private val zmaz: ImageButton = v.findViewById(R.id.zmaz)

            fun napln(o: Oslavenec) {
                meno.text = o.meno
                detail.text = listOfNotNull(
                    o.meniny?.let { getString(R.string.osl_r_meniny, denText(it)) },
                    o.narodeniny?.let {
                        if (o.rok > 0) getString(R.string.osl_r_narodeniny_rok, denText(it), o.rok)
                        else getString(R.string.osl_r_narodeniny, denText(it))
                    },
                ).joinToString(" · ")
                kedy.text = odpocet(o)
                riadok.setOnClickListener { uprav(o) }
                zmaz.setOnClickListener {
                    AlertDialog.Builder(this@OslavenciActivity)
                        .setMessage(getString(R.string.osl_zmazat_otazka, o.meno))
                        .setPositiveButton(R.string.osl_zmazat) { _, _ ->
                            // Porovnava sa identita, nie obsah: dvaja ludia
                            // s rovnakym menom aj datumom su platny stav
                            // a zmazat sa ma prave ten jeden riadok.
                            uloz(ludia.filter { it !== o })
                        }
                        .setNegativeButton(R.string.osl_zrusit, null)
                        .show()
                }
            }

            /** "dnes" / "zajtra" / "o 12 dni" — kvoli tomu je zoznam zoradeny. */
            private fun odpocet(o: Oslavenec): String {
                val dni = listOfNotNull(o.meniny, o.narodeniny)
                    .mapNotNull { Oslavenci.dniDo(it, LocalDate.now()) }
                    .minOrNull() ?: return ""
                return when (dni) {
                    0 -> getString(R.string.dnes)
                    1 -> getString(R.string.zajtra)
                    // Slovencina ma "o 2 dni" aj "o 5 dni" — plurals to rozlisi.
                    else -> resources.getQuantityString(R.plurals.osl_o_dni, dni, dni)
                }
            }
        }
    }
}
