package sk.lukac.meniny

import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Spolocny tvar obrazovky Nastavenia (docs/shared-standard.md 2.8).
 *
 * Tento subor je vo VSETKYCH appkach rovnaky (lisi sa len `package`) — je to
 * lacnejsie nez kniznicny modul pre osem samostatnych gradle projektov a
 * zaroven to drzi obrazovky naozaj zhodne. Ked sa tu nieco meni, meni sa to
 * vsade.
 *
 * Tri pravidla, ktore z toho robia standard:
 *
 * 1. **Poradie.** Najprv nastavenia SAMOTNEJ APPKY, az potom server (adresa,
 *    priecinok, meno, heslo). Clovek chodi do nastaveni kvoli appke; udaje
 *    k NAS-u sa zadavaju raz za zivot.
 * 2. **Kazde pole ma vysvetlivku.** Popis v poli hovori, ako sa to vola.
 *    Vysvetlivka hovori, na co to je a co sa stane, ked to necham prazdne.
 *    Bez nej su nastavenia hadanka aj pre toho, kto ich pisal.
 * 3. **Heslo sa da zobrazit.** Jedno zaskrtavatko odkryje vsetky hesla na
 *    obrazovke. Heslo, ktore sa nedá precitat, sa nedá ani overit — a clovek
 *    ho potom prepisuje naslepo.
 */
class NastaveniaForm(private val a: AppCompatActivity) {

    private val hustota = a.resources.displayMetrics.density
    private fun dp(v: Int) = (v * hustota).toInt()

    private val obsah = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(32))
    }

    /** Vsetky heslove polia — prepinac ich odkryva naraz. */
    private val hesla = ArrayList<EditText>()
    private var odkryte = false

    // --- stavebne prvky ---

    /** Nadpis sekcie. */
    fun nadpis(text: String): TextView = TextView(a).apply {
        this.text = text
        textSize = 15f
        setTypeface(null, android.graphics.Typeface.BOLD)
        isAllCaps = true
        letterSpacing = 0.08f
        setTextColor(ContextCompat.getColor(a, R.color.text_vedlajsi))
        setPadding(0, dp(24), 0, dp(4))
        pridaj(this)
    }

    /** Vysvetlujuci odstavec — pod nadpisom alebo pod polom. */
    fun odstavec(text: String): TextView = TextView(a).apply {
        this.text = text
        textSize = 15f
        setLineSpacing(dp(3).toFloat(), 1f)
        setTextColor(ContextCompat.getColor(a, R.color.text_vedlajsi))
        setPadding(0, 0, 0, dp(8))
        pridaj(this)
    }

    /**
     * Vstupne pole s popisom a vysvetlivkou.
     *
     * Popis je `hint` AJ samostatny riadok nad polom: samotny `hint` zmizne,
     * len co sa pole vyplni, a clovek potom pozera na hodnotu bez toho, aby
     * vedel, comu patri.
     */
    fun pole(
        popis: String,
        hodnota: String,
        vysvetlenie: String? = null,
        heslo: Boolean = false,
        cislo: Boolean = false,
        viacRiadkov: Boolean = false,
    ): EditText {
        TextView(a).apply {
            text = popis
            textSize = 15f
            setTextColor(ContextCompat.getColor(a, R.color.text_vedlajsi))
            setPadding(0, dp(10), 0, 0)
            pridaj(this)
        }
        val pole = EditText(a).apply {
            hint = popis
            setText(hodnota)
            textSize = 18f
            inputType = when {
                heslo -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                cislo -> InputType.TYPE_CLASS_NUMBER
                viacRiadkov -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT
            }
        }
        if (heslo) hesla += pole
        pridaj(pole)
        vysvetlenie?.let { poznamka(it) }
        return pole
    }

    /** Drobny text pod polom. */
    fun poznamka(text: String): TextView = TextView(a).apply {
        this.text = text
        textSize = 14f
        setLineSpacing(dp(3).toFloat(), 1f)
        setTextColor(ContextCompat.getColor(a, R.color.text_vedlajsi))
        setPadding(0, dp(2), 0, dp(4))
        pridaj(this)
    }

    /**
     * Zaskrtavatko "Zobraziť heslá".
     *
     * Prepina vsetky heslove polia na obrazovke — aj tie, ktore pribudnu az
     * PO nom, lebo sa siahne na zoznam az pri kliknuti. Kurzor sa vrati na
     * koniec: zmena `inputType` ho inak posunie na zaciatok a dalsie pismeno
     * by pristalo pred heslom.
     */
    fun prepinacHesiel(): CheckBox = CheckBox(a).apply {
        setText(R.string.n_zobraz_hesla)
        textSize = 16f
        setPadding(0, dp(8), 0, 0)
        setOnCheckedChangeListener { _, zapnute ->
            odkryte = zapnute
            for (p in hesla) {
                p.inputType = InputType.TYPE_CLASS_TEXT or
                    if (zapnute) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    else InputType.TYPE_TEXT_VARIATION_PASSWORD
                p.setSelection(p.text.length)
            }
        }
        pridaj(this)
    }

    /**
     * Zaskrtavatko s vysvetlivkou pod nim.
     *
     * Vysvetlivka je povinna z rovnakeho dovodu ako pri poli: popis hovori, ako
     * sa to vola, vysvetlivka hovori, co sa stane, ked to zapnem.
     */
    fun zaskrtavatko(
        popis: String,
        zapnute: Boolean,
        vysvetlenie: String? = null,
    ): CheckBox = CheckBox(a).apply {
        text = popis
        isChecked = zapnute
        textSize = 16f
        setTextColor(ContextCompat.getColor(a, R.color.text_hlavny))
        setPadding(0, dp(8), 0, 0)
        pridaj(this)
        vysvetlenie?.let { poznamka(it) }
    }

    /**
     * Vyber jednej z niekolkych moznosti (prepinace pod sebou).
     *
     * Pod sebou, nie vedla seba: moznosti maju rozne dlhe popisy a v rade by sa
     * na uzkom telefone lamali. Vybranu polozku vrati [vybrane].
     */
    fun vyber(
        popis: String,
        moznosti: List<String>,
        vybraneIndex: Int,
        vysvetlenie: String? = null,
    ): RadioGroup {
        TextView(a).apply {
            text = popis
            textSize = 15f
            setTextColor(ContextCompat.getColor(a, R.color.text_vedlajsi))
            setPadding(0, dp(10), 0, 0)
            pridaj(this)
        }
        val skupina = RadioGroup(a).apply {
            orientation = LinearLayout.VERTICAL
            moznosti.forEachIndexed { i, text ->
                addView(RadioButton(a).apply {
                    this.text = text
                    id = View.generateViewId()
                    textSize = 16f
                    setTextColor(ContextCompat.getColor(a, R.color.text_hlavny))
                    isChecked = i == vybraneIndex
                })
            }
        }
        pridaj(skupina)
        vysvetlenie?.let { poznamka(it) }
        return skupina
    }

    /** Ktora moznost je vo [vyber] zaskrtnuta. -1 = ziadna. */
    fun vybrane(skupina: RadioGroup): Int =
        skupina.indexOfChild(skupina.findViewById(skupina.checkedRadioButtonId))

    /**
     * Cislo, ktore sa meni tlacidlami − a +.
     *
     * Pouziva sa tam, kde ma hodnota par krokov a kazdy sa da pomenovat slovom
     * ("zakladna", "vacsie o 2"). Textove pole by tam zavadzalo: ziadalo by
     * cislo, ktore clovek nema odkial poznat, a pripustilo by aj nezmysel.
     *
     * [nazov] prevadza hodnotu na to, co sa ukaze medzi tlacidlami.
     */
    fun krokovac(
        popis: String,
        hodnota: Int,
        najmenej: Int,
        najviac: Int,
        vysvetlenie: String? = null,
        nazov: (Int) -> String,
    ): Krokovac {
        TextView(a).apply {
            text = popis
            textSize = 15f
            setTextColor(ContextCompat.getColor(a, R.color.text_vedlajsi))
            setPadding(0, dp(10), 0, 0)
            pridaj(this)
        }
        val ukazovatel = TextView(a).apply {
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            setTextColor(ContextCompat.getColor(a, R.color.text_hlavny))
        }
        val stav = Krokovac(hodnota.coerceIn(najmenej, najviac))
        fun prekresli() { ukazovatel.text = nazov(stav.hodnota) }

        val rad = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            fun tlacitko(znak: String, zmena: Int) = Button(a).apply {
                text = znak
                textSize = 20f
                minimumWidth = dp(56)
                setOnClickListener {
                    stav.hodnota = (stav.hodnota + zmena).coerceIn(najmenej, najviac)
                    prekresli()
                }
            }
            addView(tlacitko("−", -1))
            addView(ukazovatel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(tlacitko("+", 1))
        }
        prekresli()
        pridaj(rad)
        vysvetlenie?.let { poznamka(it) }
        return stav
    }

    /** Drzi hodnotu krokovaca; obrazovka si ju precita az pri Ulozit. */
    class Krokovac(var hodnota: Int)

    /**
     * Prvok, ktory formular nepozna (zaskrtavatko pripomienok, tlacidlo casu).
     *
     * Kazda appka ma par nastaveni, ktore su len jej — nema zmysel ich vsetky
     * predvidat v spolocnom formulari. Odsadenie a pozadie tak ostanu jednotne
     * aj pre ne.
     */
    fun vlastne(v: View): View {
        pridaj(v)
        return v
    }

    fun tlacidlo(text: String, akcia: () -> Unit): Button = Button(a).apply {
        this.text = text
        setOnClickListener { akcia() }
        pridaj(this)
    }

    /** Dve tlacidla vedla seba (export/import). */
    fun dvojica(
        text1: String, akcia1: () -> Unit,
        text2: String, akcia2: () -> Unit,
    ): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        val vaha = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        addView(Button(a).apply { text = text1; setOnClickListener { akcia1() } }, vaha())
        addView(Button(a).apply { text = text2; setOnClickListener { akcia2() } }, vaha())
        pridaj(this)
    }

    /** Riadok na stavove hlasky (vysledok kontroly, chyby importu). */
    fun stav(text: String = ""): TextView = TextView(a).apply {
        this.text = text
        textSize = 15f
        setLineSpacing(dp(3).toFloat(), 1f)
        setTextColor(ContextCompat.getColor(a, R.color.text_vedlajsi))
        setPadding(0, dp(8), 0, 0)
        pridaj(this)
    }

    fun ciara(): View = View(a).apply {
        setBackgroundColor(ContextCompat.getColor(a, R.color.ciara))
        pridaj(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(16)
        })
    }

    /**
     * `?android:attr/selectableItemBackgroundBorderless` sa z XML vytiahnut da,
     * z kodu nie — atribut temy treba najprv rozlozit cez [android.content.res.TypedArray].
     */
    private fun kruhovyOdraz(): Int {
        val t = a.obtainStyledAttributes(
            intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
        )
        val id = t.getResourceId(0, 0)
        t.recycle()
        return id
    }

    private fun pridaj(v: View, lp: LinearLayout.LayoutParams? = null) {
        if (lp != null) obsah.addView(v, lp) else obsah.addView(v)
    }

    /**
     * Hlavicka so sipkou spat a nazvom obrazovky.
     *
     * Appky bezia na `NoActionBar` teme (docs/shared-standard.md 2.6), takze systemova
     * hlavicka neexistuje: bez tohto sa z nastaveni da vratit len systemovym
     * gestom a obrazovka nema ani nadpis.
     */
    private fun hlavicka(nazov: String): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(dp(6), dp(10), dp(16), dp(6))
        addView(android.widget.ImageButton(a).apply {
            setImageResource(R.drawable.ic_spat)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = a.getString(R.string.n_spat)
            setBackgroundResource(kruhovyOdraz())
            setOnClickListener { a.finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(TextView(a).apply {
            text = nazov
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(a, R.color.text_hlavny))
        })
    }

    /** Nasadi hotovy formular na obrazovku. */
    fun hotovo(nazov: String = a.getString(R.string.nastavenia)) {
        val koren = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(a, R.color.pozadie))
            addView(hlavicka(nazov))
            addView(ScrollView(a).apply { addView(obsah) })
        }
        a.setContentView(koren)
    }
}
