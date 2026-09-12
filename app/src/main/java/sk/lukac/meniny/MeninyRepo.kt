package sk.lukac.meniny

import android.content.Context

/**
 * Jedina instancia databazy menin na cely proces.
 *
 * Asset ma 57 kB a parsuje sa raz — drzat ho v pamati je lacnejsie nez ho
 * citat znova pri kazdom otvoreni obrazovky alebo pri kazdom prekresleni
 * widgetu. Widget sa navyse budi z BroadcastReceivera, kde nie je kam
 * instanciu odlozit; bez tohto by sa asset parsoval pri kazdom tiknuti.
 */
object MeninyRepo {

    @Volatile
    private var cache: MeninyData? = null

    fun data(ctx: Context): MeninyData {
        cache?.let { return it }
        return synchronized(this) {
            cache ?: nacitaj(ctx).also { cache = it }
        }
    }

    private fun nacitaj(ctx: Context): MeninyData {
        val json = ctx.applicationContext.assets.open("meniny.json")
            .bufferedReader().use { it.readText() }
        return MeninyData.zJson(json)
    }
}
