package sk.lukac.meniny

import android.content.Context
import java.time.LocalDate

/**
 * Slovenske popisy datumov.
 *
 * Nazvy dni a mesiacov su v strings.xml, nie natvrdo v kode — v .kt suboroch
 * sa diakritika historicky lame (viz docs/shared-standard.md kap. 6), a "streda" bez
 * makcena vyzera na obrazovke ako chyba.
 *
 * Mesiac je v genitive ("18. augusta"), lebo tak sa datum po slovensky cita.
 * Formatovac z java.time by dal nominativ ("18. august"), co znie zle.
 */
object Datumy {

    /** "Dnes · utorok 18. augusta" — pre zoznam aj pre widget. */
    fun popis(ctx: Context, datum: LocalDate): String {
        val relativne = relativne(ctx, datum)
        val zaklad = "${denVTyzdni(ctx, datum)} ${kratky(ctx, datum)}"
        return if (relativne == null) zaklad else "$relativne · $zaklad"
    }

    /** "18. augusta" */
    fun kratky(ctx: Context, datum: LocalDate): String {
        val mesiace = ctx.resources.getStringArray(R.array.mesiace_genitiv)
        return "${datum.dayOfMonth}. ${mesiace[datum.monthValue - 1]}"
    }

    fun denVTyzdni(ctx: Context, datum: LocalDate): String =
        ctx.resources.getStringArray(R.array.dni_v_tyzdni)[datum.dayOfWeek.value - 1]

    /** "Dnes" / "Zajtra", inak null. */
    fun relativne(ctx: Context, datum: LocalDate): String? {
        val dnes = LocalDate.now()
        return when (datum) {
            dnes -> ctx.getString(R.string.dnes)
            dnes.plusDays(1) -> ctx.getString(R.string.zajtra)
            else -> null
        }
    }
}
