package sk.lukac.meniny

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Denne upozornenie "dnes oslavuje X".
 *
 * Naplanovany je vzdy len NAJBLIZSI budik, nie rad budikov dopredu: po zazvoneni
 * si receiver naplanuje dalsi. Retaz sa obnovuje aj pri starte appky, po zmene
 * nastaveni a po restarte telefonu — budiky restart neprezivaju a bez toho by
 * pripomienky po vypnuti telefonu ticho prestali chodit.
 *
 * Budik je NEPRESNY (`setAndAllowWhileIdle`). Pripomienka "rano o osmej" znesie
 * par minut nepresnosti a nepresny budik nepotrebuje povolenie
 * SCHEDULE_EXACT_ALARM, ktore Android 12+ pyta zvlast a da sa odmietnut —
 * vtedy by presny budik ticho neprebehol a pripomienky by neprisli vobec.
 * Radsej o par minut neskor nez nikdy.
 */
object Pripomienky {

    private const val TAG = "Pripomienky"
    private const val KANAL = "oslavenci"
    private const val ID_UPOZORNENIA = 8181
    private const val KOD_BUDIKA = 8182

    const val AKCIA_ZVON = "sk.lukac.meniny.PRIPOMENUT"

    /**
     * Naplanuje najblizsiu pripomienku, alebo zrusi budik, ked su vypnute
     * alebo nie je koho pripominat.
     *
     * Vola sa pri kazdej zmene, ktora vie plan ovplyvnit. Je to lacne (jeden
     * zapis do AlarmManagera) a je to jedine miesto, kde sa budik nastavuje.
     */
    fun naplanuj(ctx: Context) {
        val app = ctx.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val pi = budik(app)

        if (!Prefs.pripomienkyZapnute(app) || Prefs.oslavenci(app).isEmpty()) {
            am.cancel(pi)
            return
        }

        val kedy = Oslavenci.dalsiCas(
            LocalDateTime.now(),
            Prefs.pripomienkaHodina(app),
            Prefs.pripomienkaMinuta(app),
        )
        val ms = kedy.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi) }
            .onSuccess { Log.i(TAG, "pripomienka naplanovana na $kedy") }
            .onFailure { Log.w(TAG, "budik sa nepodarilo nastavit", it) }
    }

    /**
     * Zazvonilo: ukaz dnesnych oslavencov (ak nejaki su) a naplanuj dalsi den.
     *
     * Ked dnes nikto neslavi, upozornenie sa nezobrazi — kazdorannu hlasku
     * "nikto dnes nema meniny" by clovek do tyzdna vypol.
     */
    fun zvon(ctx: Context) {
        val app = ctx.applicationContext
        if (Prefs.pripomienkyZapnute(app)) {
            val dnes = LocalDate.now()
            val udalosti = Oslavenci.preDatum(Prefs.oslavenci(app), dnes)
            if (udalosti.isNotEmpty()) ukaz(app, udalosti)
        }
        naplanuj(app)
    }

    /** Presne to, co by prislo dnes — na tlacidlo "Skusit upozornenie". */
    fun skusobne(ctx: Context): Boolean {
        val app = ctx.applicationContext
        val udalosti = Oslavenci.preDatum(Prefs.oslavenci(app), LocalDate.now())
        if (udalosti.isEmpty()) return false
        ukaz(app, udalosti)
        return true
    }

    /** Ma appka povolenie ukazovat upozornenia? Do Androidu 13 ho netreba. */
    fun mozeUpozornovat(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun ukaz(ctx: Context, udalosti: List<Udalost>) {
        if (!mozeUpozornovat(ctx)) {
            Log.w(TAG, "upozornenia nie su povolene, pripomienka sa nezobrazi")
            return
        }
        val spravca = ctx.getSystemService(NotificationManager::class.java) ?: return
        kanal(spravca, ctx)

        val riadky = udalosti.map { text(ctx, it) }
        val klik = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val n = NotificationCompat.Builder(ctx, KANAL)
            .setSmallIcon(R.drawable.ic_upozornenie)
            .setContentTitle(ctx.getString(R.string.prip_nadpis))
            // Prvy riadok v zbalenom stave, cely zoznam po rozbaleni: oslavencov
            // moze byt v jeden den viac a zbaleny riadok ukaze len prveho.
            .setContentText(riadky.joinToString(" · "))
            .setStyle(
                NotificationCompat.InboxStyle().also { s -> riadky.forEach { s.addLine(it) } }
            )
            .setContentIntent(klik)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        runCatching { spravca.notify(ID_UPOZORNENIA, n) }
            .onFailure { Log.w(TAG, "upozornenie sa nepodarilo zobrazit", it) }
    }

    /** "Elena ma meniny" / "Peter ma 40. narodeniny" — vek len ked ho poznam. */
    fun text(ctx: Context, u: Udalost): String = when {
        u.druh == Druh.MENINY -> ctx.getString(R.string.prip_meniny, u.oslavenec.meno)
        u.vek > 0 -> ctx.getString(R.string.prip_narodeniny_vek, u.oslavenec.meno, u.vek)
        else -> ctx.getString(R.string.prip_narodeniny, u.oslavenec.meno)
    }

    /**
     * To iste do zoznamu, kde na oslavenca ostava len pravy okraj riadku.
     *
     * "Peter má 40. narodeniny" je veta do upozornenia. V stlpci sirokom 150 dp
     * by sa zalomila na tri riadky a rozhodila by vysku kazdeho dna, v ktorom
     * niekto slavi — preto tu je bez slovesa: "Peter · 40. narodeniny".
     */
    fun kratky(ctx: Context, u: Udalost): String {
        val co = when {
            u.druh == Druh.MENINY -> ctx.getString(R.string.osl_meniny)
            u.vek > 0 -> ctx.getString(R.string.osl_narodeniny_vek, u.vek)
            else -> ctx.getString(R.string.osl_narodeniny)
        }
        return ctx.getString(R.string.osl_riadok, u.oslavenec.meno, co)
    }

    private fun kanal(spravca: NotificationManager, ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // Vytvorenie uz existujuceho kanala je bez ucinku, takze sa to smie
        // volat pri kazdom upozorneni a netreba to riesit pri starte appky.
        spravca.createNotificationChannel(
            NotificationChannel(
                KANAL,
                ctx.getString(R.string.prip_kanal),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = ctx.getString(R.string.prip_kanal_popis)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
    }

    private fun budik(app: Context): PendingIntent = PendingIntent.getBroadcast(
        app,
        KOD_BUDIKA,
        Intent(app, PripomienkaReceiver::class.java).setAction(AKCIA_ZVON),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
