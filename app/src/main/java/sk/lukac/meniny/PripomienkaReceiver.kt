package sk.lukac.meniny

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Budik na pripomienku oslavencov a jeho obnova.
 *
 * Naplanovane budiky nepreziju restart telefonu ani zmenu casu, preto sa
 * receiver hlasi aj na BOOT_COMPLETED, TIME_SET a TIMEZONE_CHANGED. Bez toho by
 * pripomienky po prvom vypnuti telefonu ticho prestali chodit a nikde by sa to
 * nedalo vsimnut — az na tom, ze neprisli.
 */
class PripomienkaReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Pripomienky.AKCIA_ZVON -> Pripomienky.zvon(ctx)
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> Pripomienky.naplanuj(ctx)
        }
    }
}
