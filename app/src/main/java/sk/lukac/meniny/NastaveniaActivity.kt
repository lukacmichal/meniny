package sk.lukac.meniny

import android.Manifest
import android.app.Activity
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Nastavenia: kedy zvoni pripomienka oslavencov a odkial sa appka aktualizuje.
 *
 * Poradie ani tvar si tu nevymyslam — drzi sa spolocneho standardu, ktory
 * stavia [NastaveniaForm] (docs/shared-standard.md 2.8): najprv appka, potom server,
 * kazde pole s vysvetlivkou, heslo sa da zobrazit.
 *
 * Predvolby self-updatu prichadzaju z buildu (`nas-credentials.local`), takze
 * appka je po instalacii nastavena a tie polia sluzia len na prepis. Prazdne
 * pole = "pouzi to, s cim si bola zbuildovana".
 */
class NastaveniaActivity : ZakladActivity() {

    private var hodina = Prefs.PRIP_HODINA_PREDVOLENA
    private var minuta = Prefs.PRIP_MINUTA_PREDVOLENA

    private lateinit var zapnute: CheckBox
    private lateinit var cas: Button

    private lateinit var host: EditText
    private lateinit var share: EditText
    private lateinit var cesta: EditText
    private lateinit var subor: EditText
    private lateinit var user: EditText
    private lateinit var heslo: EditText
    private lateinit var stavZalohy: TextView

    /**
     * Povolenie upozornovat (Android 13+).
     *
     * Pripomienky sa zapnu aj ked ho clovek odmietne — nastavenie je jeho volba
     * a appka mu ju neprepisuje. Povie sa mu vsak, ze upozornenia takto
     * neprijdu; ticho zapnuta pripomienka, ktora nikdy nezvoni, je horsia nez
     * ziadna.
     */
    private val povolenie = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { povolene ->
        if (!povolene) toast(getString(R.string.prip_bez_povolenia))
    }

    /**
     * Ulozenie zalohy cez systemovy vyber suboru (SAF).
     *
     * Zamerne nie zapis do vlastneho priecinka: subor ma skoncit tam, kam si
     * ho clovek da (Disk Google, Stiahnute, USB) a appka na to nepotrebuje
     * ziadne povolenie na ulozisko.
     */
    private val ulozZalohu = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { v -> if (v.resultCode == Activity.RESULT_OK) v.data?.data?.let { zapis(it) } }

    private val nacitajZalohu = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { v -> if (v.resultCode == Activity.RESULT_OK) v.data?.data?.let { citaj(it) } }

    private lateinit var f: NastaveniaForm
    private lateinit var rezim: android.widget.RadioGroup
    private lateinit var pismo: NastaveniaForm.Krokovac
    private lateinit var pismoWidgetu: NastaveniaForm.Krokovac

    override fun onCreate(stav: Bundle?) {
        super.onCreate(stav)
        title = getString(R.string.nastavenia)
        val cfg = Prefs.updateConfig(this)
        hodina = Prefs.pripomienkaHodina(this)
        minuta = Prefs.pripomienkaMinuta(this)

        f = NastaveniaForm(this)

        // --- 1. nastavenia appky ---------------------------------------------
        // Nadpis je MENO APPKY, nie vseobecne "Appka": obrazovka nastaveni
        // vyzera vo vsetkych appkach rovnako, takze bez mena sa neda povedat,
        // v ktorej clovek prave je.
        f.nadpis(getString(R.string.app_name))
        f.odstavec(getString(R.string.prip_popis))

        zapnute = CheckBox(this).apply {
            text = getString(R.string.prip_zapnut)
            isChecked = Prefs.pripomienkyZapnute(this@NastaveniaActivity)
            setOnClickListener {
                if (isChecked) pytajPovolenie()
                ulozPripomienky()
            }
        }
        f.vlastne(zapnute)

        cas = Button(this).apply {
            setOnClickListener {
                TimePickerDialog(
                    this@NastaveniaActivity,
                    { _, h, m ->
                        hodina = h
                        minuta = m
                        ulozPripomienky()
                        obnovCas()
                    },
                    hodina, minuta, true,
                ).show()
            }
        }
        f.vlastne(cas)
        obnovCas()

        f.tlacidlo(getString(R.string.prip_koho)) {
            startActivity(Intent(this, OslavenciActivity::class.java))
        }

        // Pripomienka chodi raz za den a cakat na nu do rana je zla spatna
        // vazba. Toto ukaze presne to, co by prislo dnes.
        f.tlacidlo(getString(R.string.prip_skusit)) {
            if (!Pripomienky.mozeUpozornovat(this)) {
                pytajPovolenie()
                toast(getString(R.string.prip_bez_povolenia))
            } else if (!Pripomienky.skusobne(this)) {
                toast(getString(R.string.prip_dnes_nikto))
            }
        }

        // --- 1b. vzhlad ------------------------------------------------------
        // Vzhlad je nastavenie APPKY, takze patri este pred server (docs/shared-standard.md
        // 2.8). Sekcia vyzera rovnako vo vsetkych appkach.
        f.ciara()
        f.nadpis(getString(R.string.n_sekcia_vzhlad))
        f.odstavec(getString(R.string.n_vzhlad_popis))
        rezim = f.vyber(
            getString(R.string.n_rezim),
            listOf(
                getString(R.string.n_rezim_system),
                getString(R.string.n_rezim_svetly),
                getString(R.string.n_rezim_cierny),
            ),
            Vzhlad.rezim(this),
        )
        pismo = f.krokovac(
            getString(R.string.n_pismo),
            Vzhlad.pismo(this),
            Vzhlad.PISMO_MIN,
            Vzhlad.PISMO_MAX,
            getString(R.string.n_pismo_popis),
        ) { popisPisma(it) }
        // Widget ma VLASTNU velkost pisma (29. 8. 2026). Jedno cislo pre appku
        // aj plochu znamenalo, ze zvacsene pismo v appke ubralo widgetu polovicu
        // riadkov — a widget sa pritom cita z inej vzdialenosti nez appka.
        pismoWidgetu = f.krokovac(
            getString(R.string.n_pismo_widget),
            Vzhlad.pismoWidgetu(this),
            Vzhlad.PISMO_MIN,
            Vzhlad.PISMO_MAX,
            getString(R.string.n_pismo_widget_popis),
        ) { popisPisma(it) }

        // --- 2. zaloha -------------------------------------------------------
        f.nadpis(getString(R.string.n_sekcia_zaloha))
        f.odstavec(getString(R.string.n_zaloha_popis))
        f.dvojica(
            getString(R.string.n_export), { spustiExport() },
            getString(R.string.n_import), { spustiImport() },
        )
        stavZalohy = f.stav()

        // --- 3. server -------------------------------------------------------
        // --- prenesene data ---------------------------------------------------
        //
        // Nadpis, text aj tlacidlo pyta [Prenos] — sekcia je vo vsetkych
        // appkach rovnaka a devat kopii tych istych retazcov v deviatich
        // strings.xml by sa rozislo uz pri prvej oprave preklepu.
        f.ciara()
        f.nadpis(Prenos.NADPIS)
        val stavPrenosu = f.stav(Prenos.popis(this))
        f.poznamka(Prenos.POZNAMKA)
        f.tlacidlo(Prenos.VYNULOVAT) {
            Prenos.vynuluj(this)
            stavPrenosu.text = Prenos.popis(this)
        }

        f.ciara()
        f.nadpis(getString(R.string.n_sekcia_nas))
        f.odstavec(getString(R.string.n_nas_popis))

        host = f.pole(getString(R.string.up_host), cfg.host, getString(R.string.n_host_popis))
        share = f.pole(getString(R.string.up_share), cfg.shareName, getString(R.string.n_share_popis))
        cesta = f.pole(getString(R.string.up_path), cfg.remotePath, getString(R.string.n_path_popis))
        subor = f.pole(getString(R.string.up_file), cfg.apkFileName, getString(R.string.n_file_popis))
        user = f.pole(getString(R.string.up_user), cfg.username, getString(R.string.n_user_popis))
        heslo = f.pole(getString(R.string.up_pass), cfg.password, getString(R.string.n_pass_popis),
            heslo = true)
        f.prepinacHesiel()

        f.tlacidlo(getString(R.string.up_ulozit)) { uloz() }

        f.ciara()
        f.nadpis(getString(R.string.n_sekcia_verzia))
        f.stav(
            getString(
                R.string.up_stav,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
                cestaNaNas(cfg),
            )
        )
        // Kontrola bezi v MainActivity — tam je aj dialog a instalacia. Tu len
        // povieme, ze sa ma spustit po navrate, nech nie je ta logika na dvoch
        // miestach.
        f.tlacidlo(getString(R.string.up_skontrolovat)) {
            uloz(ticho = true)
            setResult(VYSLEDOK_SKONTROLUJ)
            finish()
        }

        f.hotovo()
    }

    /** "základná" / "menšie o 1" / "väčšie o 2" — krok sa musi dat precitat slovom. */
    private fun popisPisma(krok: Int): String = when {
        krok == 0 -> getString(R.string.n_pismo_zaklad)
        krok < 0 -> getString(R.string.n_pismo_menej)
        else -> getString(R.string.n_pismo_viac, krok)
    }

    private fun uloz(ticho: Boolean = false) {
        Prefs.saveUpdateConfig(
            this,
            host.text.toString(), share.text.toString(),
            cesta.text.toString(), subor.text.toString(),
            user.text.toString(), heslo.text.toString(),
        )
        val podpisPred = Vzhlad.podpis(this)
        Vzhlad.uloz(this, f.vybrane(rezim).coerceAtLeast(0), pismo.hodnota,
                pismoWidgetu.hodnota)
        // Widget farby ani velkost pisma neberie z temy — musi sa prekreslit,
        // inak ostane na plose v starom vzhlade.
        MeninyWidgetProvider.prekresliVsetky(this)
        if (!ticho) Toast.makeText(this, R.string.up_ulozene, Toast.LENGTH_SHORT).show()
        // Nocny rezim si prekreslenie zariadi sam (AppCompatDelegate), velkost
        // pisma nie — layout uz je nafukany starym kontextom.
        if (Vzhlad.podpis(this) != podpisPred) recreate()
    }

    /** Cas sa ukazuje na tlacitku, nech je vidiet bez otvarania dialogu. */
    private fun obnovCas() {
        cas.text = getString(R.string.prip_cas, "%02d:%02d".format(hodina, minuta))
    }

    private fun ulozPripomienky() {
        Prefs.nastavPripomienky(this, zapnute.isChecked, hodina, minuta)
        // Budik sa prepocitava tu, nie az pri odchode z obrazovky: kto nastavi
        // cas a appku zabije, ma mat pripomienku aj tak naplanovanu.
        Pripomienky.naplanuj(this)
    }

    private fun pytajPovolenie() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (Pripomienky.mozeUpozornovat(this)) return
        povolenie.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun toast(text: String) =
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    // --- zaloha ---

    private fun spustiExport() {
        val dnes = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        ulozZalohu.launch(
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(Zaloha.MIME)
                .putExtra(Intent.EXTRA_TITLE, Zaloha.menoSuboru(dnes))
        )
    }

    private fun spustiImport() {
        nacitajZalohu.launch(
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                // Nie len application/json: subor prekopirovany cez chat alebo
                // Disk casto pride ako text/plain alebo octet-stream a pri
                // uzkom filtri by sa v prehliadaci vobec nedal vybrat.
                .setType("*/*")
        )
    }

    private fun zapis(kam: Uri) {
        val vysledok = runCatching {
            contentResolver.openOutputStream(kam)?.use {
                it.write(Zaloha.json(this).toByteArray(Charsets.UTF_8))
            } ?: error("súbor sa nedá otvoriť na zápis")
        }
        stavZalohy.text = vysledok.fold(
            onSuccess = { getString(R.string.n_export_ok, Prefs.oslavenci(this).size) },
            onFailure = { getString(R.string.n_zaloha_chyba, it.message ?: "?") },
        )
    }

    private fun citaj(odkial: Uri) {
        val vysledok = runCatching {
            val text = contentResolver.openInputStream(odkial)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: error("súbor sa nedá otvoriť")
            val obsah = Zaloha.precitaj(text)
            Zaloha.pouzi(this, obsah)
            obsah
        }
        stavZalohy.text = vysledok.fold(
            onSuccess = { o ->
                // Formular musi ukazat, co sa prave naimportovalo — inak by
                // clovek stlacil Ulozit a starymi hodnotami z poli by import
                // hned prepisal.
                val nove = Prefs.updateConfig(this)
                host.setText(nove.host)
                share.setText(nove.shareName)
                cesta.setText(nove.remotePath)
                subor.setText(nove.apkFileName)
                user.setText(nove.username)
                zapnute.isChecked = Prefs.pripomienkyZapnute(this)
                hodina = Prefs.pripomienkaHodina(this)
                minuta = Prefs.pripomienkaMinuta(this)
                obnovCas()
                // Cas pripomienky sa mohol zmenit, takze budik musi ist nanovo.
                Pripomienky.naplanuj(this)
                MeninyWidgetProvider.prekresliVsetky(this)
                getString(R.string.n_import_ok, o.oslavenci.size)
            },
            onFailure = { getString(R.string.n_zaloha_chyba, it.message ?: "?") },
        )
    }

    companion object {
        /** Vratil sa s prosbou skontrolovat aktualizaciu hned teraz. */
        const val VYSLEDOK_SKONTROLUJ = 42

        /** Spatna lomka cez unicode — v zdrojaku sa tak nema co escapovat. */
        private const val L = "\u005C"

        /** UNC cesta k APK na NAS-e, teda presne to, odkial sa appka aktualizuje. */
        internal fun cestaNaNas(cfg: UpdateConfig): String =
            L + L + cfg.host + L + cfg.shareName + L + cfg.remotePath + L + cfg.apkFileName
    }
}
