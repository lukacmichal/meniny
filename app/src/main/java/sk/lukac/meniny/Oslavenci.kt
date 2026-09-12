package sk.lukac.meniny

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Clovek, ktoreho si chcem dat pripomenut.
 *
 * Meniny aj narodeniny su nepovinne, ale aspon jedno musi byt vyplnene — inak
 * by sa zaznam nikdy neozval a v zozname by len zavadzal. Meniny sa drzia ako
 * den v kalendari ("MM-DD"), nie ako meno: mena sa v kalendari neopakuju, ale
 * clovek si moze pripomienku nechat aj pre prezyvku ("dedo"), ktora v kalendari
 * nie je — a vtedy si den vyberie sam.
 *
 * Rok narodenia je nepovinny (0 = neznamy). Bez neho sa len neukaze vek;
 * pripomienka chodi rovnako. Zadanie chcelo "kludne nech si tam viem pridat aj
 * narodeniny", nie viest evidenciu.
 */
data class Oslavenec(
    val meno: String,
    /** Den menin ako "MM-DD", alebo null ked sa meniny nesleduju. */
    val meniny: String? = null,
    /** Den narodenin ako "MM-DD", alebo null ked sa narodeniny nesleduju. */
    val narodeniny: String? = null,
    /** Rok narodenia kvoli veku; 0 = neznamy. */
    val rok: Int = 0,
) {
    val platny: Boolean get() = meno.isNotBlank() && (meniny != null || narodeniny != null)
}

/** Co dnes ten clovek slavi. */
enum class Druh { MENINY, NARODENINY }

/** Jedna dnesna oslava. [vek] je 0, ked sa neda spocitat. */
data class Udalost(val oslavenec: Oslavenec, val druh: Druh, val vek: Int = 0)

/**
 * Zoznam sledovanych ludi: ulozenie, citanie a otazka "kto dnes slavi".
 *
 * Cela logika je zamerne bez Androidu, aby sa dala otestovat bez emulatora —
 * je to jediny kus appky, kde sa da tichym omylom prist o pripomienku.
 */
object Oslavenci {

    /**
     * Kto ma v [datum] meniny alebo narodeniny.
     *
     * Poradie je narodeniny prve: ked v jeden den pripadne oboje, narodeniny sa
     * slavia raz za rok konkretnemu cloveku, meniny ma v ten den pol Slovenska.
     */
    fun preDatum(zoznam: List<Oslavenec>, datum: LocalDate): List<Udalost> {
        val kluc = MeninyData.kluc(datum)
        val narodeniny = zoznam
            .filter { jeDen(it.narodeniny, kluc, datum) }
            .map { Udalost(it, Druh.NARODENINY, vek(it, datum)) }
        val meniny = zoznam
            .filter { jeDen(it.meniny, kluc, datum) }
            .map { Udalost(it, Druh.MENINY) }
        return narodeniny + meniny
    }

    /**
     * Sedi ulozeny den na tento datum?
     *
     * 29. februar sa v nepriestupnom roku posuva na 1. marca. Bez toho by sa
     * pripomienka ozvala raz za styri roky — a prave takyto clovek si ju
     * pridava, lebo na neho vsetci zabudaju.
     */
    private fun jeDen(ulozeny: String?, kluc: String, datum: LocalDate): Boolean {
        if (ulozeny == null) return false
        if (ulozeny == kluc) return true
        return ulozeny == "02-29" && kluc == "03-01" && !datum.isLeapYear
    }

    /** Kolko dovrsi; 0 ked rok narodenia nepoznam. */
    private fun vek(o: Oslavenec, datum: LocalDate): Int =
        if (o.rok <= 0) 0 else datum.year - o.rok

    /**
     * Najblizsi cas [hodina]:[minuta] po [teraz].
     *
     * Ked uz dnesny cas presiel, plati zajtrajsi — inak by sa budik naplanoval
     * do minulosti a Android by ho spustil okamzite, cize by pripomienka prisla
     * hned po zmene casu v nastaveniach.
     */
    fun dalsiCas(teraz: LocalDateTime, hodina: Int, minuta: Int): LocalDateTime {
        val dnes = teraz.toLocalDate().atTime(hodina, minuta)
        return if (dnes.isAfter(teraz)) dnes else dnes.plusDays(1)
    }

    fun zJson(json: String): List<Oslavenec> = runCatching {
        val pole = JSONArray(json)
        (0 until pole.length()).mapNotNull { i ->
            val o = pole.getJSONObject(i)
            Oslavenec(
                meno = o.optString("meno").trim(),
                meniny = o.optString("meniny").ifBlank { null },
                narodeniny = o.optString("narodeniny").ifBlank { null },
                rok = o.optInt("rok", 0),
            ).takeIf { it.platny }
        }
    }.getOrDefault(emptyList())

    fun doJson(zoznam: List<Oslavenec>): String {
        val pole = JSONArray()
        zoznam.filter { it.platny }.forEach { o ->
            pole.put(
                JSONObject().apply {
                    put("meno", o.meno)
                    o.meniny?.let { put("meniny", it) }
                    o.narodeniny?.let { put("narodeniny", it) }
                    if (o.rok > 0) put("rok", o.rok)
                }
            )
        }
        return pole.toString()
    }

    /**
     * Zoradenie do zoznamu: podla najblizsej oslavy od [odDna].
     *
     * Abecedne poradie by znamenalo, ze koho slavim zajtra, toho hladam
     * uprostred zoznamu. Takto je najblizsia oslava vzdy hore.
     */
    fun zoradene(zoznam: List<Oslavenec>, odDna: LocalDate): List<Oslavenec> =
        zoznam.sortedBy { dniDoOslavy(it, odDna) }

    private fun dniDoOslavy(o: Oslavenec, odDna: LocalDate): Int =
        listOfNotNull(o.narodeniny, o.meniny)
            .mapNotNull { dniDo(it, odDna) }
            .minOrNull() ?: Int.MAX_VALUE

    /** Kolko dni ostava do najblizsieho vyskytu dna "MM-DD"; 0 = dnes. */
    fun dniDo(kluc: String, odDna: LocalDate): Int? {
        val mesiac = kluc.substringBefore('-').toIntOrNull() ?: return null
        val den = kluc.substringAfter('-').toIntOrNull() ?: return null
        // 29. februar v nepriestupnom roku neexistuje — pozera sa na 1. marca.
        var datum = bezpecnyDatum(odDna.year, mesiac, den)
        if (datum.isBefore(odDna)) datum = bezpecnyDatum(odDna.year + 1, mesiac, den)
        return (datum.toEpochDay() - odDna.toEpochDay()).toInt()
    }

    /**
     * Datum v danom roku; 29. februar sa v nepriestupnom roku posuva na
     * 1. marca — rovnako ako pri samotnom zvoneni (viz [jeDen]). Keby tu
     * skoncil na 28. februari, zoznam by radil o den inak, nez kedy pripomienka
     * naozaj pride.
     */
    private fun bezpecnyDatum(rok: Int, mesiac: Int, den: Int): LocalDate {
        val prvy = LocalDate.of(rok, mesiac, 1)
        if (mesiac == 2 && den == 29 && prvy.lengthOfMonth() == 28) {
            return LocalDate.of(rok, 3, 1)
        }
        return prvy.withDayOfMonth(minOf(den, prvy.lengthOfMonth()))
    }
}
