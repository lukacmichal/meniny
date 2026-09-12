package sk.lukac.meniny

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Testy pripomienok. Je to jediny kus appky, kde sa da tichym omylom prist
 * o upozornenie — a to sa zisti az tym, ze neprislo.
 */
class OslavenciTest {

    private val elena = Oslavenec(meno = "Elena", meniny = "08-18")
    private val peter = Oslavenec(meno = "Peter", narodeniny = "04-03", rok = 1980)

    @Test
    fun preDatumNajdeMeninyAjNarodeniny() {
        val zoznam = listOf(elena, peter)
        assertEquals(
            listOf(Druh.MENINY),
            Oslavenci.preDatum(zoznam, LocalDate.of(2026, 8, 18)).map { it.druh },
        )
        val narodeniny = Oslavenci.preDatum(zoznam, LocalDate.of(2026, 4, 3))
        assertEquals(listOf(Druh.NARODENINY), narodeniny.map { it.druh })
        assertEquals("vek k datumu", 46, narodeniny.first().vek)
    }

    @Test
    fun bezRokuSaVekNepocita() {
        val bezRoku = peter.copy(rok = 0)
        assertEquals(0, Oslavenci.preDatum(listOf(bezRoku), LocalDate.of(2026, 4, 3)).first().vek)
    }

    @Test
    fun narodeninyIduPredMeninami() {
        // V ten isty den: narodeniny slavi konkretny clovek, meniny pol krajiny.
        val obe = Oslavenec(meno = "Elena", meniny = "08-18", narodeniny = "08-18")
        assertEquals(
            listOf(Druh.NARODENINY, Druh.MENINY),
            Oslavenci.preDatum(listOf(obe), LocalDate.of(2026, 8, 18)).map { it.druh },
        )
    }

    @Test
    fun dvadsiatyDeviatyFebruarSaVNepriestupnomRokuSlaviPrvehoMarca() {
        val prestupny = Oslavenec(meno = "Matej", narodeniny = "02-29", rok = 2000)
        // 2027 nie je priestupny — bez posunu by pripomienka prisla raz za styri roky.
        assertTrue(Oslavenci.preDatum(listOf(prestupny), LocalDate.of(2027, 3, 1)).isNotEmpty())
        // V priestupnom roku ostava na svojom dni.
        assertTrue(Oslavenci.preDatum(listOf(prestupny), LocalDate.of(2028, 2, 29)).isNotEmpty())
        assertTrue(Oslavenci.preDatum(listOf(prestupny), LocalDate.of(2028, 3, 1)).isEmpty())
    }

    @Test
    fun dalsiCasPreskociUzMinulyCas() {
        val rano = LocalDateTime.of(2026, 8, 18, 7, 30)
        assertEquals(
            LocalDateTime.of(2026, 8, 18, 8, 0),
            Oslavenci.dalsiCas(rano, 8, 0),
        )
        val vecer = LocalDateTime.of(2026, 8, 18, 21, 0)
        assertEquals(
            "cas uz presiel, plati zajtrajsi",
            LocalDateTime.of(2026, 8, 19, 8, 0),
            Oslavenci.dalsiCas(vecer, 8, 0),
        )
    }

    @Test
    fun jsonPrezijeUlozenieACitanie() {
        val zoznam = listOf(elena, peter, Oslavenec(meno = "Jano", meniny = "05-16", narodeniny = "12-01"))
        assertEquals(zoznam, Oslavenci.zJson(Oslavenci.doJson(zoznam)))
    }

    @Test
    fun neuplnyZaznamSaNeuklada() {
        // Meno bez jedineho datumu by sa nikdy neozvalo a v zozname by zavadzalo.
        assertFalse(Oslavenec(meno = "Nikto").platny)
        assertEquals("[]", Oslavenci.doJson(listOf(Oslavenec(meno = "Nikto"))))
        assertTrue(Oslavenci.zJson("""[{"meno":"Nikto"}]""").isEmpty())
    }

    @Test
    fun rozbityJsonNezhodiAppku() {
        // Radsej prazdny zoznam nez pad pri starte widgetu.
        assertTrue(Oslavenci.zJson("toto nie je json").isEmpty())
        assertTrue(Oslavenci.zJson("").isEmpty())
    }

    @Test
    fun dniDoRataAjCezPrelomRoka() {
        val silvester = LocalDate.of(2026, 12, 31)
        assertEquals(0, Oslavenci.dniDo("12-31", silvester))
        assertEquals(1, Oslavenci.dniDo("01-01", silvester))
    }

    @Test
    fun zoradenePodlaNajblizsejOslavy() {
        val den = LocalDate.of(2026, 8, 1)
        val zoradene = Oslavenci.zoradene(listOf(peter, elena), den)
        // Elena (18. 8.) je blizsie nez Peter (3. 4. buduceho roka).
        assertEquals(listOf("Elena", "Peter"), zoradene.map { it.meno })
    }

    @Test
    fun klucMeninNajdeLenPresneMeno() {
        val json = javaClass.classLoader!!.getResourceAsStream("meniny.json")!!
            .bufferedReader().use { it.readText() }
        val data = MeninyData.zJson(json)
        assertEquals("08-18", data.klucMenin("elena"))
        assertEquals("diakritika sa ignoruje", "08-18", data.klucMenin("Eléna"))
        // Zaciatok mena nestaci: pri pridavani cloveka by "Mar" ticho chytilo Mariu.
        assertNull(data.klucMenin("Mar"))
        assertNull(data.klucMenin(""))
    }
}
