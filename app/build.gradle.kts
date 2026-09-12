plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---- spolocny blok pre vsetky appky (nemen len tu, viz docs/shared-standard.md) ----
/**
 * Pristupove udaje k NAS-u sa citaju z JEDNEHO suboru spolocneho pre vsetky
 * appky: nas-credentials.local. Odtial sa pri builde vlozia do APK ako
 * predvolby, takze na telefone sa uz nic nezadava.
 *
 * Prepis v Nastaveniach appky ma prednost — to je cesta pre zmenu hesla bez
 * rebuildu. Ked subor chyba, hodnoty ostanu prazdne a appka si udaje vypyta
 * v Nastaveniach ako predtym; build kvoli tomu nikdy nespadne.
 *
 * Heslo timto konci v APK. Je to vedome rozhodnutie: APK lezia na tom istom
 * NAS-e pod tym istym uctom, takze kto sa dostane k nim, ten ucet uz ma.
 */
fun nasSetting(key: String): String {
    val f = file(System.getenv("NAS_CREDENTIALS") ?: "nas-credentials.local")
    if (!f.exists()) return ""
    return f.readLines()
        .firstOrNull { it.trimStart().startsWith("$key=") }
        ?.substringAfter("=")?.trim().orEmpty()
}

/** Heslo moze obsahovat spatne lomitko aj uvodzovku — bez tohto by build nepreslo. */
fun nasField(key: String): String =
    "\"" + nasSetting(key).replace("\\", "\\\\").replace("\"", "\\\"") + "\""
// ---- koniec spolocneho bloku -------------------------------------------------

android {
    namespace = "sk.lukac.meniny"
    compileSdk = 35

    defaultConfig {
        applicationId = "sk.lukac.meniny"
        minSdk = 26
        targetSdk = 35
        // Vydanie 3. 9. 2026: pocitadlo prenesenych dat rozdelene na Wi-Fi
        // a mobilne (Prenos.kt, spolocny subor) a sirsi rozsah velkosti
        // pisma (-6 az +6 namiesto -1 az +3).
        // 1.12 (13) — ponuka novej verzie ma nadpis a hovori aj to, co mas
        // TERAZ (docs/shared-standard.md 2.6 §2). Bez druheho cisla sa z ponuky nedalo
        // poznat, ci je skok o jednu verziu alebo o pat.
        versionCode = 13
        versionName = "1.12"

        buildConfigField("String", "NAS_HOST", nasField("NAS_HOST"))
        buildConfigField("String", "NAS_SHARE", nasField("NAS_SHARE"))
        buildConfigField("String", "NAS_USER", nasField("NAS_USER"))
        buildConfigField("String", "NAS_PASS", nasField("NAS_PASS"))
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Testy citaju meniny.json priamo zo zdrojov (nie cez AssetManager), aby
    // sa parsovanie a vyhladavanie dali overit bez emulatora.
    sourceSets {
        getByName("test") {
            resources.srcDir("src/main/assets")
        }
    }

    // Pevne pomenovany vystup kvoli self-update konvencii na NAS-e
    // (Android/Meniny/meniny.apk sa pri kazdom vydani len prepise; skutocnu
    // verziu appka cita z APK manifestu, nie z nazvu suboru).
    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "meniny.apk"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // Potiahnutie dole = obnov; rovnake gesto vo vsetkych appkach.
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    // SMB2/3 klient pre self-update z NAS-u; slf4j-nop potlaci jeho logovanie.
    implementation("com.hierynomus:smbj:0.13.0")
    implementation("org.slf4j:slf4j-nop:2.0.9")
    // Sifrovane prefs pre heslo k NAS-u (MasterKey v Android Keystore).
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Databaza menin sa cita cez org.json — je v Androide, takze ziadna
    // dalsia zavislost nepribuda. V unit testoch ho dodava json:20240303,
    // lebo android.jar ma len prazdne stuby.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
