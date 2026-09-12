package sk.lukac.meniny

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Self-update z domaceho NAS-u cez SMB2/3 (kniznica SMBJ) - rovnaka konvencia
 * ako pouziva Tankomat, len iny podpriecinok, aby sa appky nebili:
 *
 *     \\nas.local\Android\WakeNB\wakenb.apk
 *
 * Na Synology je /volume1/Android v sieti viditelne ako share "Android"
 * (volume1 je len fyzicky zvazok, nie share). Subor ma pevne meno a pri novom
 * vydani sa len prepise - verzia sa necita z nazvu, ale az po stiahnuti priamo
 * z APK (ApkInstaller.readVersionCode).
 *
 * Kratky timeout (5 s) je zamerny: NAS je len na domacej sieti, takze mimo domu
 * ma kontrola rychlo zlyhat namiesto dlheho cakania.
 *
 * Blokujuce - volaj z Thread, nie z hlavneho vlakna.
 */
object SmbUpdater {

    fun download(cfg: UpdateConfig, destination: File): File {
        withShare(cfg) { s ->
            val remotePath = joinPath(cfg.remotePath, cfg.apkFileName)
            if (!s.fileExists(remotePath)) {
                error(
                    "Na NAS-e som nenašiel \"${cfg.apkFileName}\" v priečinku " +
                        "\"${cfg.remotePath.ifBlank { "/" }}\"."
                )
            }
            open(s, remotePath).use { file ->
                destination.parentFile?.mkdirs()
                file.inputStream.use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        return destination
    }

    /**
     * Precita maly textovy subor vedla APK (pouziva sa na `output-metadata.json`).
     *
     * Kvoli tomuto uz kontrola pri starte netiahne cely 7 MB balik zakazdym, ked
     * sa appka otvori — metadata maju par stoviek bajtov. Cele APK sa stahuje az
     * po potvrdeni v ponuke.
     */
    fun readText(cfg: UpdateConfig, fileName: String): String {
        var out = ""
        withShare(cfg) { s ->
            val path = joinPath(cfg.remotePath, fileName)
            if (!s.fileExists(path)) error("Na NAS-e nie je \"$fileName\".")
            out = open(s, path).use { it.inputStream.readBytes().decodeToString() }
        }
        return out
    }

    private fun open(share: DiskShare, path: String) = share.openFile(
        path,
        setOf(AccessMask.GENERIC_READ),
        null,
        SMB2ShareAccess.ALL,
        SMB2CreateDisposition.FILE_OPEN,
        null
    )

    private inline fun withShare(cfg: UpdateConfig, body: (DiskShare) -> Unit) {
        val client = SMBClient(
            SmbConfig.builder()
                .withSoTimeout(5, TimeUnit.SECONDS)
                .withTimeout(5, TimeUnit.SECONDS)
                .build()
        )
        client.connect(cfg.host).use { connection ->
            val auth = AuthenticationContext(
                cfg.username,
                cfg.password.toCharArray(),
                cfg.domain.ifBlank { null }
            )
            val session = connection.authenticate(auth)
            val share = session.connectShare(cfg.shareName) as? DiskShare
                ?: error("\"${cfg.shareName}\" nie je súborový share.")
            share.use { body(it) }
        }
    }

    /** SMB pouziva spatne lomitka; prazdna cesta = koren share-u. */
    internal fun joinPath(dir: String, fileName: String): String {
        val clean = dir.trim().trim('/', '\\').replace('/', '\\')
        return if (clean.isEmpty()) fileName else "$clean\\$fileName"
    }
}
