package sk.lukac.meniny

import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.core.content.ContextCompat
import java.time.LocalDate

/**
 * Obsah rolovatelneho zoznamu dni vo widgete.
 *
 * **Preco sluzba a nie pevne riadky** (30. 8. 2026). Widget mal do vtedy
 * dvanast riadkov natvrdo v layoute; posledny sa na ploche orezaval napoly
 * a dalej sa clovek nedostal. Zadanie znelo „ked prstom kliknem do toho
 * widgetu, tak mozem s nim hybat hore a dole" — teda skutocne rolovanie tahom,
 * ktore sa v RemoteViews da jedine cez kolekciu plnenu z `RemoteViewsService`.
 *
 * Rolovanim navyse zmizol aj ten orezany riadok: `ListView` si vysku riesi
 * sama a nikto uz nemusi hadat, kolko riadkov sa do widgetu zmesti.
 *
 * Kalendar je offline asset v APK, takze factory nesiaha ani na siet, ani nikam
 * inam nez do [MeninyRepo] a [Prefs].
 */
class DniService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        DniFactory(applicationContext)
}

class DniFactory(private val ctx: Context) : RemoteViewsService.RemoteViewsFactory {

    private data class Riadok(val text: String, val oslavenci: String)

    /**
     * Odfotene dni. Factory sa pyta z ineho vlakna, nez v ktorom sa meni zoznam
     * oslavencov — bez odfotenia by sa pocet poloziek menil pod rukami.
     */
    private var riadky: List<Riadok> = emptyList()

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        val data = MeninyRepo.data(ctx)
        val ludia = Prefs.oslavenci(ctx)
        val dnes = LocalDate.now()
        riadky = data.odDatumu(dnes.plusDays(1), DNI).mapNotNull { (datum, den) ->
            if (den == null) return@mapNotNull null
            // Den bez mena (1. januara, 2. novembra...) nie je chyba dat — ma
            // len sviatok. Prazdny riadok by vo widgete vyzeral ako chyba.
            val obsah = den.menaSpolu.ifBlank { den.sviatky.firstOrNull().orEmpty() }
            Riadok(
                text = "${Datumy.kratky(ctx, datum)}  $obsah",
                // Sledovany clovek v ten den. Pri meninach uz meno v riadku je,
                // pri narodeninach v kalendari nie je vobec — inak by som ich
                // vo widgete nevidel.
                oslavenci = Oslavenci.preDatum(ludia, datum)
                    .joinToString(", ") { Pripomienky.kratky(ctx, it) },
            )
        }
    }

    override fun onDestroy() {
        riadky = emptyList()
    }

    override fun getCount() = riadky.size

    override fun getItemId(position: Int) = position.toLong()

    override fun hasStableIds() = true

    override fun getViewTypeCount() = 1

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewAt(position: Int): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.item_widget_den)
        val r = riadky.getOrNull(position) ?: return v

        val tema = Vzhlad.temaKontext(ctx)
        val bezna = ContextCompat.getColor(tema, R.color.text_hlavny)
        val oslava = ContextCompat.getColor(tema, R.color.oslava)
        v.setInt(R.id.d_ciara, "setBackgroundColor",
            ContextCompat.getColor(tema, R.color.widget_ciara))

        val faktor = Vzhlad.faktorWidgetu(ctx)
        val slavi = r.oslavenci.isNotEmpty()

        v.setTextViewText(R.id.d_text, r.text)
        v.setTextViewTextSize(R.id.d_text, TypedValue.COMPLEX_UNIT_SP, SP_RIADOK * faktor)
        v.setTextColor(R.id.d_text, if (slavi) oslava else bezna)

        v.setTextViewText(R.id.d_oslavenec, r.oslavenci)
        v.setTextViewTextSize(R.id.d_oslavenec, TypedValue.COMPLEX_UNIT_SP,
            SP_OSLAVENEC * faktor)
        v.setTextColor(R.id.d_oslavenec, oslava)
        v.setViewVisibility(R.id.d_oslavenec, if (slavi) View.VISIBLE else View.GONE)

        // Tuknutie kdekolvek v zozname otvori appku — to iste, co tuknutie na
        // dnesok. Bez toho by riadky pod dneskom boli jedine miesto widgetu,
        // ktore na tuknutie nereaguje.
        v.setOnClickFillInIntent(R.id.d_text, Intent())
        return v
    }

    private companion object {
        /**
         * Kolko dni dopredu zoznam ponuka. Rok: kalendar je kazdy rok rovnaky,
         * takze dalej uz nie je co ukazat, a nekonecny zoznam by nedaval zmysel.
         */
        const val DNI = 365

        // Zakladne velkosti pisma (sp) — rovnake ako v layoute riadku.
        const val SP_RIADOK = 15f
        const val SP_OSLAVENEC = 14f
    }
}
