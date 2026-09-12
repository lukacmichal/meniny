package sk.lukac.meniny

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
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

        // Zakladne velkosti pisma hlavicky (sp) — rovnake ako v layoute.
        private const val SP_DATUM = 13f
        private const val SP_MENA = 22f
        private const val SP_OSLAVENEC = 14f

        fun prekresliVsetky(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(
                ComponentName(ctx, MeninyWidgetProvider::class.java)
            )
            ids.forEach { prekresli(ctx, manager, it) }
        }

        private fun prekresli(ctx: Context, manager: AppWidgetManager, id: Int) {
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
            v.setInt(R.id.w_koren, "setBackgroundColor",
                ContextCompat.getColor(tema, R.color.widget_pozadie))
            v.setTextColor(R.id.w_dnes_datum, tlmena)
            v.setTextColor(R.id.w_prazdno, tlmena)
            v.setTextColor(R.id.w_dnes_mena,
                ContextCompat.getColor(tema, R.color.zvyraznenie))
            v.setTextColor(R.id.w_dnes_oslavenci,
                ContextCompat.getColor(tema, R.color.oslava))

            // Velkost pisma sa widgetu musi povedat CISLOM — `fontScale`
            // z kontextu appky RemoteViews nepocuvaju, kresli ich launcher.
            val faktor = Vzhlad.faktorWidgetu(ctx)
            v.setTextViewTextSize(R.id.w_dnes_datum, TypedValue.COMPLEX_UNIT_SP,
                SP_DATUM * faktor)
            v.setTextViewTextSize(R.id.w_dnes_mena, TypedValue.COMPLEX_UNIT_SP,
                SP_MENA * faktor)
            v.setTextViewTextSize(R.id.w_dnes_oslavenci, TypedValue.COMPLEX_UNIT_SP,
                SP_OSLAVENEC * faktor)

            val dnesnyDen = data.preDatum(dnes)
            v.setTextViewText(R.id.w_dnes_datum, Datumy.popis(ctx, dnes))
            v.setTextViewText(
                R.id.w_dnes_mena,
                dnesnyDen?.menaSpolu?.ifBlank { null }
                    ?: ctx.getString(R.string.ziadne_meno),
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
            v.setOnClickPendingIntent(R.id.w_dnes_datum, doAppky)
            v.setOnClickPendingIntent(R.id.w_dnes_mena, doAppky)
            v.setPendingIntentTemplate(R.id.w_dni, doAppky)

            manager.updateAppWidget(id, v)
            // Bez tohto by launcher kreslil to, co mal odlozene — obsah
            // kolekcie si sam od seba neoveruje.
            manager.notifyAppWidgetViewDataChanged(id, R.id.w_dni)
        }
    }
}
