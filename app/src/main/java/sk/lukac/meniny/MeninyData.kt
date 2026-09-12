package sk.lukac.meniny

import org.json.JSONObject
import java.time.LocalDate
import java.text.Normalizer

/**
 * Jedno meno v kalendari — pripadne aj s povodom a vyznamom.
 *
 * Povod a vyznam mozu chybat: dnesmeniny.sk nema mena zacinajuce na "L" s makcenom
 * (Lubica, Ludovit a spol.). Meno bez vyznamu je stale platne meno, takze sa
 * nezahadzuje — len sa v UI nezobrazi popis.
 */
data class Meno(
    val meno: String,
    val povod: String = "",
    val vyznam: String = "",
) {
    val maPopis: Boolean get() = povod.isNotBlank() || vyznam.isNotBlank()
}

/**
 * Jeden den kalendara. Poradie v [mena] je poradie doleziosti zo zdroja —
 * NESMIE sa triedit, zadanie chce najdolezitejsie meno prve.
 */
data class Den(
    val kluc: String,          // "MM-DD"
    val mena: List<Meno>,
    val sviatky: List<String>,
) {
    val mesiac: Int get() = kluc.substring(0, 2).toInt()
    val den: Int get() = kluc.substring(3, 5).toInt()

    /** "Elena, Helena" — na widget aj do zoznamu. */
    val menaSpolu: String get() = mena.joinToString(", ") { it.meno }
}

/**
 * Databaza menin nacitana z assetu `meniny.json`.
 *
 * Data su v APK zamerne (viz data/README.md): kalendar sa medzirocne nemeni
 * a widget musi ukazat dnesny den aj bez signalu. Ziadne sietove volanie tu
 * teda nie je a ani nema byt.
 */
class MeninyData(dni: List<Den>) {

    private val podlaKluca: Map<String, Den> = dni.associateBy { it.kluc }

    /** Vsetky dni v poradi od 1. januara. */
    val dni: List<Den> = dni.sortedBy { it.kluc }

    fun preDatum(datum: LocalDate): Den? = podlaKluca[kluc(datum)]

    /**
     * Den a najblizsich [dni] dalsich — presne to, co ma ukazat widget.
     *
     * Prelom roka sa rieti tym, ze sa pocita cez [LocalDate], nie cez kluce:
     * po 31. decembri nasleduje 1. januar, nie koniec zoznamu.
     */
    fun odDatumu(datum: LocalDate, dni: Int): List<Pair<LocalDate, Den?>> =
        (0 until dni).map { datum.plusDays(it.toLong()).let { d -> d to preDatum(d) } }

    /**
     * Den menin daneho mena ako "MM-DD", alebo null ked take meno v kalendari
     * nie je.
     *
     * Hlada sa PRESNA zhoda (bez diakritiky a velkosti pismen), nie zaciatok:
     * pri pridavani oslavenca by "Mar" nemalo ticho chytit Mariu. Kto ma
     * v kalendari meno, ktore tam nie je (prezyvka, cudzie meno), si den vyberie
     * rucne.
     */
    fun klucMenin(meno: String): String? {
        val q = normalizuj(meno)
        if (q.isEmpty()) return null
        return dni.firstOrNull { d -> d.mena.any { normalizuj(it.meno) == q } }?.kluc
    }

    /**
     * Hlada meno bez ohladu na diakritiku a velkost pismen.
     *
     * Zhoda na zaciatku mena je vzdy pred zhodou v strede: kto pise "mar",
     * chce najprv Mariu a Mariana, nie Otmara. Bez toho je vysledok nahodny.
     */
    fun hladaj(dopyt: String): List<Pair<Den, Meno>> {
        val q = normalizuj(dopyt)
        if (q.isEmpty()) return emptyList()

        val zaciatok = mutableListOf<Pair<Den, Meno>>()
        val vnutri = mutableListOf<Pair<Den, Meno>>()
        for (d in dni) {
            for (m in d.mena) {
                val n = normalizuj(m.meno)
                when {
                    n.startsWith(q) -> zaciatok += d to m
                    n.contains(q) -> vnutri += d to m
                }
            }
        }
        return zaciatok + vnutri
    }

    companion object {
        fun kluc(datum: LocalDate): String =
            "%02d-%02d".format(datum.monthValue, datum.dayOfMonth)

        /**
         * Porovnavaci tvar: bez diakritiky a velkosti pismen.
         *
         * Rovnaka logika ako v generatore (data/generuj_mena.py). Bez toho by
         * "lubica" nenaslo "Ľubica" a hladanie by na slovenskych menach zlyhalo
         * prave tam, kde ho clovek najviac potrebuje.
         */
        fun normalizuj(s: String): String =
            Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .trim()

        /** Rozobere obsah `meniny.json`. */
        fun zJson(json: String): MeninyData {
            val korene = JSONObject(json).getJSONArray("dni")
            val dni = ArrayList<Den>(korene.length())
            for (i in 0 until korene.length()) {
                val d = korene.getJSONObject(i)
                val menaJson = d.getJSONArray("mena")
                val mena = ArrayList<Meno>(menaJson.length())
                for (j in 0 until menaJson.length()) {
                    val m = menaJson.getJSONObject(j)
                    mena += Meno(
                        meno = m.getString("meno"),
                        povod = m.optString("povod", ""),
                        vyznam = m.optString("vyznam", ""),
                    )
                }
                val sviatkyJson = d.getJSONArray("sviatky")
                val sviatky = ArrayList<String>(sviatkyJson.length())
                for (j in 0 until sviatkyJson.length()) sviatky += sviatkyJson.getString(j)

                dni += Den(kluc = d.getString("den"), mena = mena, sviatky = sviatky)
            }
            return MeninyData(dni)
        }
    }
}
