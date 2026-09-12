# Meniny — slovenský kalendár menín s widgetom

Appka na vlastnú potrebu, bez reklamy. Ukáže, kto má dnes meniny, koho čakajú
najbližšie dni, a mená sa dajú vyhľadať. Widget na ploche ukazuje dnešok
a toľko ďalších dní, koľko sa doň zmestí. Vybraných ľudí — meniny aj
narodeniny — vie appka raz denne pripomenúť upozornením.

**Rieši úlohu MENINY-1** zo `C:\NAS\chybajuce veci.txt`.

---

## Ako to funguje

Kalendár je **v APK ako asset**, nie sťahovaný za behu. Dôvody sú tri: kalendár
sa medziročne prakticky nemení, widget musí ukázať dnešok aj bez signálu,
a sťahovať kvôli tomu ~900 kB HTML pri každom otvorení by zbytočne žralo
mobilné dáta (viď úloha ANDROID-1).

Na sieť teda appka chodí **iba** kvôli self-updatu.

```
data/generuj_mena.py   →  app/src/main/assets/meniny.json  →  APK
     (na stroji)                    (57 kB)                   (telefón)
```

Generátor a jeho pasce sú popísané v [`data/README.md`](data/README.md).
Po zmene dát treba appku prebuildovať — asset sa balí do APK.

---

## Súbory

| Súbor | Načo |
|---|---|
| `data/generuj_mena.py` | stiahne oba zdroje a vyrobí asset |
| `MeninyData.kt` | parsovanie assetu, deň podľa dátumu, vyhľadávanie |
| `MeninyRepo.kt` | jedna inštancia na proces (widget sa budí často) |
| `MainActivity.kt` | zoznam dní, vyhľadávanie, kontrola novej verzie |
| `MeninyWidgetProvider.kt` | widget na plochu |
| `Oslavenci.kt` | sledovaní ľudia: ukladanie, „kto dnes slávi", čas budíka |
| `OslavenciActivity.kt` | zoznam ľudí, pridávanie menín a narodenín |
| `Pripomienky.kt` | budík cez `AlarmManager` a samotné upozornenie |
| `PripomienkaReceiver.kt` | zazvonenie a obnova budíka po reštarte |
| `Datumy.kt` | slovenské popisy dátumov |
| `Prefs.kt`, `UpdateConfig.kt`, `SmbUpdater.kt`, `ApkInstaller.kt`, `VersionUi.kt` | self-update z NAS-u |

Self-update je prevzatý z `wake-nb` — appky sú samostatné Gradle projekty,
takže spoločný modul by znamenal krížne závislosti. Keď sa mení, mení sa
vo všetkých naraz.

---

## Vydanie

```bash
cd C:\NAS\meniny && .\gradlew assembleDebug
cd C:\NAS && .\nahraj-na-nas.ps1 -Appka meniny -Skus
```

Bez `-Skus` sa nahrá APK aj `output-metadata.json` do
`\\nas.local\Android\Meniny\`. **Pred nahratím vždy zvýš `versionCode`**
v `app/build.gradle.kts` — appka porovnáva výhradne jeho.

Nahráva sa **debug** build, rovnako ako pri ostatných appkách: debug keystore
je na tomto stroji stabilný, takže OTA na už nainštalovaných telefónoch prejde.

---

## Testy

```bash
cd C:\NAS\meniny && .\gradlew testDebugUnitTest     # 24 testov (Kotlin)
cd C:\NAS\meniny\data && python -m pytest -q        # 15 testov (Python)
```

Kotlinské testy bežia **proti skutočnému assetu**, nie proti vymyslenému
vstupu — kontrolujú teda aj správnosť dát, nielen že sa parser nezosype.
Preto majú v `build.gradle.kts` pripojený `src/main/assets` do test resources.

---

## Čo treba vedieť pri úpravách

**Poradie mien sa nesmie triediť.** Zadanie chce „to najdôležitejšie ako prvé"
a to poradie určuje zdroj (`kalendar.aktuality.sk`). Abecedné zoradenie by ho
zničilo — 18. augusta je „Elena, Helena", nie naopak.

**Deň bez mena je platný stav.** 1. januára, 1. mája, 2. novembra, 18. a
25. decembra meno nemajú, majú len sviatok. Nie je to chyba dát.

**Meno bez významu je stále meno.** `dnesmeniny.sk` nemá mená na `Ľ`
(Ľubica, Ľudovít…), tých je 11. Zahodiť ich by znamenalo, že v kalendári
chýbajú úplne.

**Vyhľadávanie ignoruje diakritiku** a zhoda na začiatku mena ide pred zhodou
v strede — kto píše „mar", chce najprv Máriu, nie Otmara.

**Widget si počíta počet riadkov z vlastnej výšky.** RemoteViews sa nedajú
stavať za behu, takže layout má pevných dvanásť riadkov a provider tie navyše
skryje (`pocetRiadkov`). Predtým boli vždy štyri: na roztiahnutom widgete
zostávalo prázdno a na malom sa orezali. Po zmene veľkosti to prepočíta
`onAppWidgetOptionsChanged`.

**Pripomienka je nepresný budík.** `setAndAllowWhileIdle` nepotrebuje povolenie
`SCHEDULE_EXACT_ALARM`, ktoré Android 12+ pýta zvlášť a dá sa odmietnuť — vtedy
by presný budík ticho neprebehol a upozornenie by neprišlo vôbec. Ranná
pripomienka pár minút nepresnosti znesie.

**Naplánovaný je vždy len najbližší budík**, nie rad dopredu; po zazvonení sa
naplánuje ďalší. Reťaz sa obnovuje aj pri štarte appky, po zmene nastavení
a po reštarte telefónu (`PripomienkaReceiver`) — budíky reštart neprežijú
a bez toho by pripomienky ticho prestali chodiť.

**Keď dnes nikto neslávi, upozornenie nepríde.** Každoranné „nikto nemá
meniny" by človek do týždňa vypol. Preto je v Nastaveniach tlačidlo
*Skúsiť upozornenie teraz* — inak sa na spätnú väzbu čaká do rána.

**29. február sa v nepriestupnom roku slávi 1. marca.** Inak by pripomienka
prišla raz za štyri roky práve tomu, na koho aj tak všetci zabúdajú. Platí to
zhodne pre zvonenie aj pre poradie v zozname.

**Widget sa nespolieha na `updatePeriodMillis`.** Ten má minimum 30 minút
a systém ho beztak združuje, takže po polnoci by widget ešte dlho ukazoval
včerajšok. Provider preto počúva aj `DATE_CHANGED`, `TIME_SET`
a `TIMEZONE_CHANGED`.

**Diakritika v `.kt` nie.** V `strings.xml` áno (viď `docs/shared-standard.md` kap. 6) —
preto sú názvy dní a mesiacov v `string-array`, nie natvrdo v `Datumy.kt`.
