"""Testy generátora databázy menín. Bez siete — HTML sú výrezy zo skutočných
stránok zachytených 18. 8. 2026.
"""

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

import generuj_mena as g  # noqa: E402


# --- normalizácia mien -------------------------------------------------------

@pytest.mark.parametrize("a,b", [
    ("Antónia", "antonia"),
    ("Ľubica", "lubica"),
    ("Žaneta", "zaneta"),
    ("Adrián", "adrian"),
    ("ELENA", "elena"),
])
def test_kluc_mena_zjednoti_diakritiku_a_velkost(a, b):
    assert g.kluc_mena(a) == g.kluc_mena(b)


def test_kluc_mena_nezlucuje_rozne_mena():
    assert g.kluc_mena("Elena") != g.kluc_mena("Helena")


# --- ročný kalendár ----------------------------------------------------------

def _den(mesiac_nadpis, cislo, polozky):
    riadky = "".join(polozky)
    return f"<h3>{mesiac_nadpis}</h3><ul><li>{riadky}</li></ul>"


ROK_HTML = (
    "<h3>Február</h3><ul>"
    "<li><a href='/meninovy/den/2026-2-28/'>28.</a>"
    "<a href='/meninovy/zlatica/'>Zlatica</a></li>"
    # Pretečená bunka februárovej mriežky: odkazuje na 1. marca, ale nesie
    # Radomíra (meno 29. februára). Toto sa nesmie dostať do marca.
    "<li><a href='/meninovy/den/2026-3-1/'>1.</a>"
    "<a href='/meninovy/radomir/'>Radomír</a></li>"
    "</ul>"
    "<h3>Marec</h3><ul>"
    "<li><a href='/meninovy/den/2026-3-1/'>1.</a>"
    "<a href='/meninovy/albin/'>Albín</a></li>"
    "</ul>"
)


def test_pretecena_bunka_predosleho_mesiaca_sa_ignoruje():
    """Regresia: bez tejto kontroly vyšlo 1. marca „Radomír, Albín"."""
    dni = {d.kluc: d for d in g.parsuj_rok(ROK_HTML)}
    assert [m.meno for m in dni["03-01"].mena] == ["Albín"]
    assert [m.meno for m in dni["02-28"].mena] == ["Zlatica"]


PORADIE_HTML = (
    "<h3>August</h3><ul>"
    "<li><a href='/meninovy/den/2026-8-18/'>18.</a>"
    "<a href='/meninovy/elena/'>Elena</a>"
    "<a href='/meninovy/helena/'>Helena</a></li>"
    "</ul>"
)


def test_poradie_mien_sa_zachova():
    """Zadanie chce najdôležitejšie meno prvé — poradie zo zdroja sa nesmie triediť."""
    dni = {d.kluc: d for d in g.parsuj_rok(PORADIE_HTML)}
    assert [m.meno for m in dni["08-18"].mena] == ["Elena", "Helena"]


SVIATKY_HTML = (
    "<h3>Január</h3><ul>"
    "<li><a href='/meninovy/den/2026-1-1/'>1.</a>"
    "<a href='/meninovy/popis/2026-1-1/'>Deň vzniku Slovenskej republiky</a>"
    "<a href='/meninovy/popis/2026-1-1/'>Svetový deň mieru</a></li>"
    "<li><a href='/meninovy/den/2026-1-6/'>6.</a>"
    "<a href='/meninovy/antonia/'>Antónia</a>"
    "<a href='/meninovy/popis/2026-1-6/'>Zjavenie Pána</a></li>"
    "</ul>"
)


def test_sviatky_sa_odlisia_od_mien():
    dni = {d.kluc: d for d in g.parsuj_rok(SVIATKY_HTML)}
    assert dni["01-01"].mena == []
    assert dni["01-01"].sviatky == ["Deň vzniku Slovenskej republiky", "Svetový deň mieru"]
    assert [m.meno for m in dni["01-06"].mena] == ["Antónia"]
    assert dni["01-06"].sviatky == ["Zjavenie Pána"]


def test_den_bez_mena_je_platny_stav():
    """1. januára a 25. decembra naozaj meno nemajú — nie je to chyba parsera."""
    dni = g.parsuj_rok(SVIATKY_HTML)
    assert any(not d.mena for d in dni)


# --- významy -----------------------------------------------------------------

VYZNAM_HTML = (
    "<div class='rounded-lg border bg-card'>"
    "<div><a href='/meno/elena'>elena</a></div>"
    "<div>Pôvod: grécky pôvod Význam: svetlo Meniny: 18. augusta</div>"
    "</div>"
)


def test_parsuj_vyznamy():
    v = g.parsuj_vyznamy(VYZNAM_HTML)
    assert g.kluc_mena("Elena") in v
    m = v[g.kluc_mena("Elena")]
    assert m.povod == "grécky pôvod"
    assert m.vyznam == "svetlo"


def test_spojenie_doplni_vyznam_naprieč_diakritikou():
    dni = g.parsuj_rok(PORADIE_HTML)
    vyznamy = g.parsuj_vyznamy(VYZNAM_HTML)
    doplnene, chybajuce = g.spoj(dni, vyznamy)
    assert doplnene == 1 and chybajuce == 1     # Elena áno, Helena nie
    elena = dni[0].mena[0]
    assert elena.meno == "Elena" and elena.vyznam == "svetlo"
    # Meno bez významu sa nesmie zahodiť.
    assert dni[0].mena[1].meno == "Helena"


# --- kontroly ----------------------------------------------------------------

def test_kontrola_chyti_mojibake():
    """Rozbité kódovanie appku nezhodí, len ticho zmaže významy — musí spadnúť tu."""
    dni = g.parsuj_rok(PORADIE_HTML)
    dni[0].mena[0].meno = "Å¾aneta"
    problemy = g.skontroluj(dni)
    assert any("kódovanie" in p for p in problemy), problemy


def test_kontrola_chyti_chybajuce_dni():
    problemy = g.skontroluj(g.parsuj_rok(PORADIE_HTML))
    assert any("chýbajú dni" in p for p in problemy)


def test_kontrola_prejde_na_uplnom_roku():
    dlzky = {1: 31, 2: 28, 3: 31, 4: 30, 5: 31, 6: 30,
             7: 31, 8: 31, 9: 30, 10: 31, 11: 30, 12: 31}
    dni = [g.Den(mesiac=m, den=d, mena=[g.Meno("Test")])
           for m, dl in dlzky.items() for d in range(1, dl + 1)]
    assert g.skontroluj(dni) == []
