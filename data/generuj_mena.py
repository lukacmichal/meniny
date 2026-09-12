#!/usr/bin/env python3
"""Vygeneruje databázu menín, ktorá sa zabalí do APK ako asset.

    python generuj_mena.py                # stiahne a zapíše asset
    python generuj_mena.py --kontrola     # len overí, nič nezapíše

Prečo sa dáta balia do APK a nesťahujú za behu
----------------------------------------------
Kalendár menín sa medziročne prakticky nemení a appka ho potrebuje aj vtedy,
keď telefón nemá signál — widget na ploche musí ukázať dnešný deň vždy.
Sťahovať kvôli tomu 900 kB HTML pri každom otvorení by bolo aj pomalé, aj
zbytočné na mobilných dátach (viď ANDROID-1). Preto sa dáta generujú tu na
stroji, uložia do `app/src/main/assets/` a appka číta iba lokálny súbor.

Dva zdroje, každý na niečo iné
------------------------------
* **kalendar.aktuality.sk** — pre každý deň mená **v poradí dôležitosti**.
  Zadanie hovorí „to najdôležitejšie ako prvé" a toto je jediný zdroj, ktorý
  poradie určuje; abecedné zoradenie by ho zničilo. Odtiaľ sú aj sviatky.
* **dnesmeniny.sk** — pôvod a význam mena. Poradie odtiaľ nebrať, stránka je
  abecedná.

Mená, ktoré má len jeden zdroj, sa nezahadzujú: bez pôvodu a významu je meno
stále meno, a deň bez mena (napr. 1. januára) je legitímny stav, nie chyba.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import unicodedata
from dataclasses import dataclass, field
from pathlib import Path

import requests
from bs4 import BeautifulSoup

KOREN = Path(__file__).resolve().parent
VYSTUP = KOREN.parent / "app" / "src" / "main" / "assets" / "meniny.json"

ROK_URL = "https://kalendar.aktuality.sk/kalendar-2026/"
MENA_URL = "https://dnesmeniny.sk/vsetky-mena/"

# Bez tohto vracajú obe stránky inú (alebo žiadnu) podobu obsahu.
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0 Safari/537.36")

DEN_RE = re.compile(r"/meninovy/den/\d{4}-(\d{1,2})-(\d{1,2})/?$")
MENO_RE = re.compile(r"/meninovy/(?!den/|popis/)([^/]+)/?$")
POPIS_RE = re.compile(r"/meninovy/popis/")

# "24. decembra" -> (24, 12). Slovenské mesiace v genitíve.
MESIACE = {
    "januára": 1, "februára": 2, "marca": 3, "apríla": 4, "mája": 5, "júna": 6,
    "júla": 7, "augusta": 8, "septembra": 9, "októbra": 10, "novembra": 11,
    "decembra": 12,
}
DATUM_RE = re.compile(r"(\d{1,2})\.\s*(" + "|".join(MESIACE) + ")")


@dataclass
class Meno:
    meno: str
    povod: str = ""
    vyznam: str = ""


@dataclass
class Den:
    mesiac: int
    den: int
    mena: list[Meno] = field(default_factory=list)
    sviatky: list[str] = field(default_factory=list)

    @property
    def kluc(self) -> str:
        return f"{self.mesiac:02d}-{self.den:02d}"


def stiahni(url: str) -> str:
    """Stiahne stránku ako UTF-8.

    Kódovanie sa nastavuje natvrdo zámerne: `requests` pri `text/html` bez
    deklarovaného charsetu spadne podľa RFC na ISO-8859-1, čo z „Žaneta"
    spraví „Å¾aneta". Nespadne pri tom nič — len sa mená prestanú párovať
    s druhým zdrojom a ticho zmizne pôvod aj význam. Obe stránky sú UTF-8.
    """
    r = requests.get(url, headers={"User-Agent": UA}, timeout=30)
    r.raise_for_status()
    r.encoding = "utf-8"
    return r.text


def kluc_mena(s: str) -> str:
    """Porovnávací tvar mena — bez diakritiky a veľkosti písmen.

    Zdroje píšu to isté meno rôzne („Antónia" vs „antonia"), takže spájať sa
    musí na normalizovanom tvare, inak by polovica mien ostala bez významu.
    """
    bez = unicodedata.normalize("NFKD", s.casefold())
    return "".join(c for c in bez if not unicodedata.combining(c)).strip()


NADPISY_MESIACOV = {
    "január": 1, "február": 2, "marec": 3, "apríl": 4, "máj": 5, "jún": 6,
    "júl": 7, "august": 8, "september": 9, "október": 10, "november": 11,
    "december": 12,
}


def _mesiac_sekcie(li) -> int | None:
    """Číslo mesiaca podľa najbližšieho nadpisu nad zoznamom, alebo None."""
    ul = li.find_parent("ul")
    if ul is None:
        return None
    nadpis = ul.find_previous(["h1", "h2", "h3", "h4"])
    if nadpis is None:
        return None
    return NADPISY_MESIACOV.get(nadpis.get_text(strip=True).strip().casefold())


def parsuj_rok(html: str) -> list[Den]:
    """Z ročného kalendára vytiahne dni s menami v poradí a so sviatkami.

    Riadok sa berie len vtedy, keď sedí s nadpisom mesiaca, pod ktorým stojí.
    Bez tejto kontroly sa do dát dostane zlé meno: februárová mriežka končí
    pretečenou bunkou, ktorá odkazuje na `2026-3-1`, ale nesie **Radomíra** —
    meno 29. februára, ktorý v nepriestupnom roku neexistuje. Zlúčením by
    1. marca vyšlo „Radomír, Albín" a nesprávne meno by bolo prvé, teda presne
    to, čo zadanie pýta správne („to najdôležitejšie ako prvé").
    """
    s = BeautifulSoup(html, "lxml")
    podla_kluca: dict[str, Den] = {}

    for a in s.select("a[href]"):
        m = DEN_RE.search(a.get("href", ""))
        if not m:
            continue
        li = a.find_parent("li")
        if li is None:
            continue

        mesiac = int(m.group(1))
        if _mesiac_sekcie(li) not in (None, mesiac):
            continue

        kluc = f"{mesiac:02d}-{int(m.group(2)):02d}"
        den = podla_kluca.setdefault(
            kluc, Den(mesiac=mesiac, den=int(m.group(2))))
        for x in li.select("a[href]"):
            href = x.get("href", "")
            text = " ".join(x.get_text(" ", strip=True).split())
            if not text:
                continue
            if POPIS_RE.search(href):
                if text not in den.sviatky:
                    den.sviatky.append(text)
            elif MENO_RE.search(href):
                # Poradie v HTML = poradie dôležitosti, preto sa nesmie triediť.
                if all(text != y.meno for y in den.mena):
                    den.mena.append(Meno(meno=text))

    return sorted(podla_kluca.values(), key=lambda d: (d.mesiac, d.den))


def parsuj_vyznamy(html: str) -> dict[str, Meno]:
    """Z abecedného zoznamu vytiahne pôvod a význam každého mena."""
    s = BeautifulSoup(html, "lxml")
    von: dict[str, Meno] = {}

    for a in s.select("a[href]"):
        if not re.match(r"^/meno/[^/]+/?$", a.get("href", "")):
            continue
        karta = a
        for _ in range(6):
            karta = karta.parent
            if karta is None or "Meniny:" in karta.get_text(" ", strip=True):
                break
        if karta is None:
            continue
        t = " ".join(karta.get_text(" ", strip=True).split())
        if "Meniny:" not in t:
            continue

        meno = " ".join(a.get_text(" ", strip=True).split())
        povod = re.search(r"Pôvod:\s*(.+?)\s*(?:Význam:|Meniny:|$)", t)
        vyznam = re.search(r"Význam:\s*(.+?)\s*(?:Meniny:|$)", t)
        von[kluc_mena(meno)] = Meno(
            meno=meno,
            povod=(povod.group(1).strip() if povod else ""),
            vyznam=(vyznam.group(1).strip() if vyznam else ""),
        )
    return von


def spoj(dni: list[Den], vyznamy: dict[str, Meno]) -> tuple[int, int]:
    """Doplní pôvod a význam k menám v kalendári. Vracia (doplnené, bez_vyznamu)."""
    doplnene = chybajuce = 0
    for d in dni:
        for m in d.mena:
            zdroj = vyznamy.get(kluc_mena(m.meno))
            if zdroj:
                m.povod, m.vyznam = zdroj.povod, zdroj.vyznam
                doplnene += 1
            else:
                chybajuce += 1
    return doplnene, chybajuce


def na_json(dni: list[Den]) -> dict:
    return {
        "zdroje": {"poradie_a_sviatky": ROK_URL, "povod_a_vyznam": MENA_URL},
        "dni": [
            {
                "den": d.kluc,
                "mena": [
                    {"meno": m.meno, "povod": m.povod, "vyznam": m.vyznam}
                    for m in d.mena
                ],
                "sviatky": d.sviatky,
            }
            for d in sorted(dni, key=lambda x: (x.mesiac, x.den))
        ],
    }


def skontroluj(dni: list[Den]) -> list[str]:
    """Vráti zoznam problémov. Prázdny zoznam = dáta sú použiteľné."""
    problemy: list[str] = []

    if len(dni) < 365:
        problemy.append(f"dní je {len(dni)}, čakalo sa aspoň 365")

    kluce = [d.kluc for d in dni]

    # 29. februára v nepriestupnom roku chýbať smie, iné diery nie.
    chybaju = []
    dlzky = {1: 31, 2: 28, 3: 31, 4: 30, 5: 31, 6: 30,
             7: 31, 8: 31, 9: 30, 10: 31, 11: 30, 12: 31}
    mam = set(kluce)
    for mes, dl in dlzky.items():
        for d in range(1, dl + 1):
            if f"{mes:02d}-{d:02d}" not in mam:
                chybaju.append(f"{mes:02d}-{d:02d}")
    if chybaju:
        problemy.append(f"chýbajú dni: {', '.join(chybaju[:10])}")

    # Poistka proti mojibake. Keď sa kódovanie znova rozbije, dáta nespadnú —
    # len sa prestanú párovať a v menách sa objavia zvyšky ako "Ã", "Å¾", "Â".
    # Ticho by to prešlo až do APK, preto radšej tvrdá kontrola.
    podozrive = [m.meno for d in dni for m in d.mena
                 if any(z in m.meno for z in ("Ã", "Å", "Â", "¾", "¡"))]
    if podozrive:
        problemy.append(
            f"rozbité kódovanie v menách ({len(podozrive)}): {', '.join(podozrive[:5])}")

    bez_mien = [d.kluc for d in dni if not d.mena]
    # Pár dní naozaj meno nemá (1. januára, 25. decembra) — veľa ich ale byť
    # nemôže, to by znamenalo, že sa zmenil formát stránky.
    if len(bez_mien) > 10:
        problemy.append(f"{len(bez_mien)} dní bez mena — asi sa zmenilo HTML")

    return problemy


def main() -> int:
    p = argparse.ArgumentParser(description="Generátor databázy menín")
    p.add_argument("--kontrola", action="store_true",
                   help="len overí zdroje, nič nezapíše")
    args = p.parse_args()

    print(f"sťahujem {ROK_URL}")
    dni = parsuj_rok(stiahni(ROK_URL))
    print(f"  dní: {len(dni)}, mien spolu: {sum(len(d.mena) for d in dni)}")

    print(f"sťahujem {MENA_URL}")
    vyznamy = parsuj_vyznamy(stiahni(MENA_URL))
    print(f"  mien s významom: {len(vyznamy)}")

    doplnene, chybajuce = spoj(dni, vyznamy)
    print(f"spojené: {doplnene} mien s významom, {chybajuce} bez")

    problemy = skontroluj(dni)
    if problemy:
        print("\nPROBLÉMY:")
        for x in problemy:
            print(f"  - {x}")
        return 1

    if args.kontrola:
        print("\nkontrola v poriadku, nič som nezapísal")
        return 0

    VYSTUP.parent.mkdir(parents=True, exist_ok=True)
    VYSTUP.write_text(
        json.dumps(na_json(dni), ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8")
    print(f"\nzapísané: {VYSTUP} ({VYSTUP.stat().st_size / 1024:.0f} kB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
