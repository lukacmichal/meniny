# Databáza menín — generátor

Vyrobí `app/src/main/assets/meniny.json`, ktorý sa zabalí do APK.

```bash
python generuj_mena.py            # stiahne a zapíše asset
python generuj_mena.py --kontrola # len overí zdroje, nič nezapíše
python -m pytest test_generuj_mena.py -q
```

## Prečo je to asset a nie sťahovanie za behu

Kalendár menín sa medziročne prakticky nemení a appka ho potrebuje aj bez
signálu — widget na ploche musí ukázať dnešný deň vždy. Sťahovať kvôli tomu
~900 kB HTML pri každom otvorení by bolo pomalé aj zbytočné na mobilných dátach
(viď ANDROID-1). Dáta sa preto generujú tu a appka číta iba lokálny súbor.

## Dva zdroje, každý na niečo iné

| Zdroj | Čo z neho je |
|---|---|
| `kalendar.aktuality.sk/kalendar-2026/` | pre každý deň mená **v poradí dôležitosti** + sviatky |
| `dnesmeniny.sk/vsetky-mena/` | pôvod a význam mena |

Poradie určuje **iba** aktuality — zadanie chce „to najdôležitejšie ako prvé"
a abecedné zoradenie by ho zničilo. Preto sa mená nikdy netriedia; berú sa
v poradí, v akom sú v HTML.

## Aktuálny stav dát

365 dní, 418 mien, z toho 407 s pôvodom a významom. Bez významu je 11 mien
(`Ľubica`, `Ľudovít`, …) — dnesmeniny.sk ich jednoducho nemá, jeho abeceda
obsahuje `Ĺ`, ale nie `Ľ`. Meno bez významu sa nezahadzuje.

Päť dní nemá meno (`01-01`, `05-01`, `11-02`, `12-18`, `12-25`) — to je
v slovenskom kalendári správne, sú to sviatky bez mena.

## Dve pasce, na ktoré sa už narazilo

**Kódovanie.** `requests` pri `text/html` bez deklarovaného charsetu spadne
podľa RFC na ISO-8859-1 a z „Žaneta" spraví „Å¾aneta". Nespadne pri tom nič —
len sa mená prestanú párovať s druhým zdrojom a ticho zmizne pôvod aj význam.
Pri prvom behu takto vypadlo **155 zo 419** mien. `stiahni()` preto nastavuje
UTF-8 natvrdo a `skontroluj()` má tvrdú kontrolu na zvyšky mojibake.

**Pretečené bunky mriežky.** Februárový zoznam končí bunkou, ktorá odkazuje na
`2026-3-1`, ale nesie **Radomíra** — meno 29. februára, ktorý v nepriestupnom
roku neexistuje. Bez kontroly mesiaca vyšlo 1. marca „Radomír, Albín", a to
nesprávne meno bolo prvé. Riadok sa preto berie, len keď sedí s nadpisom
mesiaca, pod ktorým stojí. Správne je 1. marca **Albín**.

Obe pasce majú vlastný test — sú to tiché chyby, ktoré by inak prešli až do APK.

## Keď sa zmení HTML zdroja

`skontroluj()` zachytí: chýbajúce dni, podozrivo veľa dní bez mena a rozbité
kódovanie. Spusti `--kontrola` a pozri, čo hlási; selektory sú v `DEN_RE`,
`MENO_RE`, `POPIS_RE` a v `_mesiac_sekcie()`.
