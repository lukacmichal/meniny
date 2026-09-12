package sk.lukac.meniny

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Testy nad **skutocnym** assetom `meniny.json` — ten je pripojeny do test
 * resources cez `sourceSets` v build.gradle.kts. Kontroluje sa teda aj to, ci
 * data vygenerovane skriptom naozaj sedia, nie len ci sa parser nezosype na
 * vymyslenom vstupe.
 */
class MeninyDataTest {

    private val data: MeninyData by lazy {
        val json = javaClass.classLoader!!.getResourceAsStream("meniny.json")!!
            .bufferedReader().use { it.readText() }
        MeninyData.zJson(json)
    }

    @Test
    fun assetObsahujeCelyRok() {
        assertEquals("dni v roku", 365, data.dni.size)
        assertTrue("mien ma byt vyse 400", data.dni.sumOf { it.mena.size } > 400)
    }

    @Test
    fun poradieMienJePodlaDolezitosti() {
        // Zadanie: "to najdolezitejsie ako prve". Elena je pred Helenou.
        val d = data.preDatum(LocalDate.of(2026, 8, 18))!!
        assertEquals(listOf("Elena", "Helena"), d.mena.map { it.meno })
        assertEquals("Elena, Helena", d.menaSpolu)
    }

    @Test
    fun prvyMarecJeAlbinANieRadomir() {
        // Regresia: februarova mriezka pretecie bunkou s Radomirom (meno
        // 29. februara), ktora odkazuje na 1. marca. Do dat sa dostat nesmie.
        val d = data.preDatum(LocalDate.of(2026, 3, 1))!!
        assertEquals(listOf("Albín"), d.mena.map { it.meno })
    }

    @Test
    fun dniBezMenaSuPlatneAMajuSviatok() {
        // 1. januara v slovenskom kalendari meno nie je — nie je to chyba dat.
        val d = data.preDatum(LocalDate.of(2026, 1, 1))!!
        assertTrue("1. januara nema meno", d.mena.isEmpty())
        assertTrue("ale ma sviatok", d.sviatky.isNotEmpty())
    }

    @Test
    fun menaMajuPovodAVyznam() {
        val elena = data.preDatum(LocalDate.of(2026, 8, 18))!!.mena.first()
        assertEquals("grécky pôvod", elena.povod)
        assertEquals("svetlo", elena.vyznam)
        assertTrue(elena.maPopis)
    }

    @Test
    fun menoBezVyznamuSaNezahodi() {
        // Lubica na dnesmeniny.sk nie je, v kalendari ale je.
        val najdene = data.hladaj("lubica")
        assertTrue("Ľubica ma byt v datach", najdene.isNotEmpty())
        assertTrue("a bez popisu", !najdene.first().second.maPopis)
    }

    @Test
    fun hladanieIgnorujeDiakritikuAVelkost() {
        for (dopyt in listOf("elena", "ELENA", "Eléna".replace("é", "e"))) {
            assertTrue("$dopyt nenaslo Elenu",
                data.hladaj(dopyt).any { it.second.meno == "Elena" })
        }
        assertTrue("bez makcena musi najst Ľubicu",
            data.hladaj("lubica").any { it.second.meno == "Ľubica" })
    }

    @Test
    fun zhodaNaZaciatkuJePredZhodouVStrede() {
        val vysledky = data.hladaj("mar").map { it.second.meno }
        val prveVnutorne = vysledky.indexOfFirst {
            !MeninyData.normalizuj(it).startsWith("mar")
        }
        if (prveVnutorne >= 0) {
            val poNom = vysledky.drop(prveVnutorne)
            assertTrue(
                "po prvej vnutornej zhode uz nesmie prist zhoda na zaciatku: $poNom",
                poNom.none { MeninyData.normalizuj(it).startsWith("mar") },
            )
        }
    }

    @Test
    fun prazdnyDopytNevratiNic() {
        assertTrue(data.hladaj("").isEmpty())
        assertTrue(data.hladaj("   ").isEmpty())
    }

    @Test
    fun widgetDostaneDnesokAJDalsieDni() {
        val od = LocalDate.of(2026, 8, 18)
        val rad = data.odDatumu(od, 4)
        assertEquals(4, rad.size)
        assertEquals(od, rad.first().first)
        assertEquals("Elena, Helena", rad.first().second!!.menaSpolu)
        assertEquals(od.plusDays(3), rad.last().first)
    }

    @Test
    fun prelomRokaNeprepadne() {
        // Po 31. decembri ma nasledovat 1. januar, nie koniec zoznamu.
        val rad = data.odDatumu(LocalDate.of(2026, 12, 30), 4)
        assertEquals(listOf("12-30", "12-31", "01-01", "01-02"),
            rad.map { MeninyData.kluc(it.first) })
        assertTrue("vsetky dni sa musia najst", rad.all { it.second != null })
    }

    @Test
    fun kazdyDenRokaSaNajde() {
        var d = LocalDate.of(2026, 1, 1)
        while (d.year == 2026) {
            assertNotNull("chyba den ${MeninyData.kluc(d)}", data.preDatum(d))
            d = d.plusDays(1)
        }
    }
}
