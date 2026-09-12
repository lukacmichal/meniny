package sk.lukac.meniny

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Citanie zalohy nastaveni. Zapis potrebuje Context, takze sa tu testuje
 * ta polovica, ktora rozhoduje o tom, ci import nieco nepokazi.
 */
class ZalohaTest {

    private val platna = """
        {"verzia":1,"appka":"meniny","vytvorene":1787000000000,
         "oslavenci":[
           {"meno":"Peter","meniny":"06-29","narodeniny":"03-14","rok":1985},
           {"meno":"Dedo","narodeniny":"02-29"},
           {"meno":"","meniny":"01-01"}
         ],
         "pripomienky":{"zapnute":true,"hodina":7,"minuta":30},
         "nas":{"host":"nas.local","share":"Android","cesta":"Meniny",
                "subor":"meniny.apk","meno":"nas"}}
    """.trimIndent()

    @Test
    fun `precita oslavencov aj s rokom narodenia`() {
        val o = Zaloha.precitaj(platna)
        assertEquals(2, o.oslavenci.size)
        assertEquals("Peter", o.oslavenci[0].meno)
        assertEquals("06-29", o.oslavenci[0].meniny)
        assertEquals(1985, o.oslavenci[0].rok)
    }

    @Test
    fun `zaznam bez mena sa zahodi`() {
        // `Oslavenec.platny` chce meno aj aspon jeden den. Neplatny zaznam by
        // v zozname len zavadzal a nikdy by sa neozval.
        assertTrue(Zaloha.precitaj(platna).oslavenci.none { it.meno.isBlank() })
    }

    @Test
    fun `29 februar prezije import`() {
        // Prave takyto clovek si pripomienku pridava, lebo na neho vsetci
        // zabudaju — zaloha ho nesmie stratit.
        val dedo = Zaloha.precitaj(platna).oslavenci.first { it.meno == "Dedo" }
        assertEquals("02-29", dedo.narodeniny)
        assertNull(dedo.meniny)
    }

    @Test
    fun `precita cas pripomienky`() {
        val o = Zaloha.precitaj(platna)
        assertEquals(true, o.pripomienkyZapnute)
        assertEquals(7, o.hodina)
        assertEquals(30, o.minuta)
    }

    @Test
    fun `nezmyselny cas sa ignoruje`() {
        // Rucne upraveny subor s hodinou 99 by inak ticho posunul budik.
        val o = Zaloha.precitaj(
            """{"verzia":1,"pripomienky":{"zapnute":true,"hodina":99,"minuta":-5}}"""
        )
        assertNull(o.hodina)
        assertNull(o.minuta)
    }

    @Test
    fun `cudzi subor sa odmietne, nie ticho prehltne`() {
        val e = runCatching { Zaloha.precitaj("""{"nieco":"ine"}""") }.exceptionOrNull()
        assertTrue(e!!.message!!, e.message!!.contains("záloha"))
    }

    @Test
    fun `nie JSON sa odmietne`() {
        val e = runCatching { Zaloha.precitaj("toto nie je json") }.exceptionOrNull()
        assertTrue(e!!.message!!.contains("JSON"))
    }

    @Test
    fun `zaloha z novsej verzie sa odmietne`() {
        // Inak by starsia appka precitala len to, comu rozumie, a zvysok by
        // ticho zahodila — a clovek by si myslel, ze ma vsetko spat.
        val e = runCatching { Zaloha.precitaj("""{"verzia":99}""") }.exceptionOrNull()
        assertTrue(e!!.message!!, e.message!!.contains("novšej"))
    }

    @Test
    fun `zaloha bez oslavencov sa da precitat`() {
        // Prazdny zoznam je platny stav; `pouzi` ho zamerne neaplikuje, aby
        // chybny subor nevymazal vsetkych ludi.
        val o = Zaloha.precitaj("""{"verzia":1,"nas":{"host":"h"}}""")
        assertTrue(o.oslavenci.isEmpty())
        assertEquals("h", o.host)
    }
}
