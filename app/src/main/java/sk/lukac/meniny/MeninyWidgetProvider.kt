package sk.lukac.meniny

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.text.inSpans
import java.time.LocalDate

/**
 * Widget na plochu: dnesok velkym pismom a dalsie dni pod nim.
 *
 * Data su offline (asset v APK), takze widget funguje aj bez signalu — presne
 * preto sa kalendar nesahuje zo siete.
 *
 * **Zoznam dalsich dni sa roluje PRSTOM** (30. 8. 2026). Do vtedy mal widget
 * pevny pocet riadkov pocitany z jeho vysky — a ten odhad bol o kusok
 * optimistickejsi nez skutocnost, takze posledne meno bolo na ploche orezane
 * napoly. Riadky teraz plni `ListView` cez [DniService]: vysku si riesi sama
 * a nikto ju uz nemusi hadat. Sipky ↑ ↓ tym padli — rolovanie tahom je to,
 * co clovek od widgetu caka.
 *
 * Dnesok ostava NAD zoznamom a neroluje sa s nim — widget je predovsetkym
 * o tom, kto ma meniny dnes, a to nema odrolovat prec.
 *
 * Obnova sa nespolieha len na `updatePeriodMillis`. Ten ma minimum 30 minut
 * a system ho beztak zdruzuje, takze widget by po polnoci este dlho ukazoval
 * vcerajsok. Preto sa provider prihlasuje aj na DATE_CHANGED, TIME_SET
 * a TIMEZONE_CHANGED — tie prídu presne vtedy, ked sa datum naozaj zmeni.
 *
 * **Velkost pisma sa nastavuje CISLOM**, nie z layoutu: widget kresli launcher
 * a `fontScale` z kontextu appky nepocuva. Krok si berie vlastny, widgetovy
 * ([Vzhlad.faktorWidgetu]) — na ploche sa cita z inej vzdialenosti nez appka.
 */
class MeninyWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { prekresli(ctx, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(
        ctx: Context,
        manager: AppWidgetManager,
        id: Int,
        novy: Bundle?,
    ) {
        super.onAppWidgetOptionsChanged(ctx, manager, id, novy)
        prekresli(ctx, manager, id)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        when (intent.action) {
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_BOOT_COMPLETED,
            -> prekresliVsetky(ctx)
        }
    }

    companion object {

        // Zakladne velkosti pisma hlavicky (sp). SP_MENA je textSize
        // w_dnes_mena v layoute, SP_DATUM je "Dnes 18. oktobra" pred menami
        // v tom istom texte (RelativeSizeSpan).
        private const val SP_DATUM = 20f
        private const val SP_MENA = 26f
        private const val SP_OSLAVENEC = 14f

        fun prekresliVsetky(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(
                ComponentName(ctx, MeninyWidgetProvider::class.java)
            )
            ids.forEach { prekresli(ctx, manager, it) }
        }

        private fun prekresli(ctx: Context, manager: AppWidgetManager, id: Int) {
            manager.updateAppWidget(id, postav(ctx, id))
            // Bez tohto by launcher kreslil to, co mal odlozene — obsah
            // kolekcie si sam od seba neoveruje.
            manager.notifyAppWidgetViewDataChanged(id, R.id.w_dni)
        }

        /**
         * Cely widget, ale bez odoslania launcheru. Zvlast preto, aby sa dal
         * nafuknut a odfotit v emulatore, kde sa launcher nerozbehne.
         */
        internal fun postav(ctx: Context, id: Int): RemoteViews {
            val data = MeninyRepo.data(ctx)
            val ludia = Prefs.oslavenci(ctx)
            val v = RemoteViews(ctx.packageName, R.layout.widget_meniny)
            val dnes = LocalDate.now()

            // Farby patria rezimu zvolenemu V APPKE, nie nastaveniu telefonu.
            // Widget kresli launcher a `values-night` by sa v nom riadil
            // telefonom — kto si zapne cierny rezim v appke, mal by biely
            // widget. Preto sa tu nastavuju vyslovne.
            val tema = Vzhlad.temaKontext(ctx)
            val tlmena = ContextCompat.getColor(tema, R.color.text_vedlajsi)
            val fialova = ContextCompat.getColor(tema, R.color.zvyraznenie)
            v.setInt(R.id.w_koren, "setBackgroundColor",
                ContextCompat.getColor(tema, R.color.widget_pozadie))
            v.setInt(R.id.w_dnes, "setBackgroundColor",
                ContextCompat.getColor(tema, R.color.dnes_pozadie))
            v.setInt(R.id.w_dnes_pruh, "setBackgroundColor", fialova)
            v.setTextColor(R.id.w_prazdno, tlmena)
            v.setTextColor(R.id.w_dnes_mena, fialova)
            v.setTextColor(R.id.w_dnes_oslavenci,
                ContextCompat.getColor(tema, R.color.oslava))

            // Velkost pisma sa widgetu musi povedat CISLOM — `fontScale`
            // z kontextu appky RemoteViews nepocuvaju, kresli ich launcher.
            val faktor = Vzhlad.faktorWidgetu(ctx)
            v.setTextViewTextSize(R.id.w_dnes_mena, TypedValue.COMPLEX_UNIT_SP,
                SP_MENA * faktor)
            v.setTextViewTextSize(R.id.w_dnes_oslavenci, TypedValue.COMPLEX_UNIT_SP,
                SP_OSLAVENEC * faktor)

            val dnesnyDen = data.preDatum(dnes)
            v.setTextViewText(
                R.id.w_dnes_mena,
                dnesnyRiadok(
                    ctx, dnes, ContextCompat.getColor(tema, R.color.dnes_datum),
                    dnesnyDen?.menaSpolu?.ifBlank { null }
                        ?: ctx.getString(R.string.ziadne_meno),
                ),
            )

            // Kto z mojich dnes slavi — kvoli tomu widget vobec je.
            val dnesOslavuju = Oslavenci.preDatum(ludia, dnes)
            v.setTextViewText(
                R.id.w_dnes_oslavenci,
                // Kazdy na vlastnom riadku a bez slovesa — pravy stlpec je uzky
                // a "Peter ma 40. narodeniny" by sa v nom zalomil na tri riadky.
                dnesOslavuju.joinToString("\n") { Pripomienky.kratky(ctx, it) },
            )
            v.setViewVisibility(
                R.id.w_dnes_oslavenci,
                if (dnesOslavuju.isEmpty()) View.GONE else View.VISIBLE,
            )
            v.setTextViewText(R.id.w_prazdno, ctx.getString(R.string.ziadne_meno))

            // Kazdy widget ma vlastny adapter: `data` v intente ich odlisi,
            // inak by si dva widgety na ploche zdielali jednu factory.
            val obsah = Intent(ctx, DniService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .setData(Uri.parse("meniny://widget/" + id))
            v.setRemoteAdapter(R.id.w_dni, obsah)
            v.setEmptyView(R.id.w_dni, R.id.w_prazdno)

            val doAppky = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            // Klepnutie na dnesok otvori appku. Na koren sa to dat NEDA —
            // prebilo by to rolovanie zoznamu pod nim.
            v.setOnClickPendingIntent(R.id.w_dnes, doAppky)
            v.setPendingIntentTemplate(R.id.w_dni, doAppky)
            return v
        }

        /**
         * "Dnes 18. oktobra  Lukas" v jednom texte (18. 10. 2026).
         *
         * Predtym bol nad menom samostatny sivy riadok "DNES · NEDELA
         * 18. OKTOBRA". Datum je teraz pred menami, tucny a tyrkysovy
         * (`dnes_datum`), mena este vacsie a fialove — farbou sa od seba
         * odlisia aj v jednom riadku. V 1.14 bol datum 15sp a sivy; to bolo
         * "zase male", preto 1.15 ma 20sp a vyraznu farbu.
         * Den v tyzdni chyba zamerne: dnes ho kazdy vie a bez neho sa do
         * riadku zmestia aj dve mena.
         *
         * Medzera v "18. oktobra" je nezlomitelna: na uzkom widgete sa text
         * zalomi za "Dnes" alebo pred menami, nie medzi dnom a mesiacom.
         * Za "Dnes" je obycajna — keby bol cely datum jedno slovo, v uzkom
         * stlpci by ho Android rozsekal uprostred ("18. o / kto…").
         * Spany RemoteViews prenesu, lebo su ParcelableSpan (farba, velkost,
         * tucne).
         */
        private fun dnesnyRiadok(
            ctx: Context,
            dnes: LocalDate,
            farbaDatumu: Int,
            mena: String,
        ): CharSequence {
            val datum = ctx.getString(R.string.dnes) + " " +
                Datumy.kratky(ctx, dnes).replace(' ', '\u00A0')
            return buildSpannedString {
                inSpans(
                    ForegroundColorSpan(farbaDatumu),
                    RelativeSizeSpan(SP_DATUM / SP_MENA),
                    StyleSpan(Typeface.BOLD),
                ) {
                    append(datum)
                    append("  ")
                }
                bold { append(mena) }
            }
        }
    }
}
