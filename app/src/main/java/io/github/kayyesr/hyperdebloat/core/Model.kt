package io.github.kayyesr.hyperdebloat.core

/** Rating tiers, numbered as in the PowerShell version (0 = flagged, 5 = ordinary app). */
enum class Tier(val code: Int, val label: String, val colour: String, val sortOrder: Int) {
    FLAGGED(0, "FLAGGED", "GREY", 5),
    SAFE(1, "SAFE", "GREEN", 1),
    MODERATE(2, "MODERATE", "YELLOW", 2),
    HIGH(3, "HIGH", "ORANGE", 3),
    UNSAFE(4, "UNSAFE", "RED", 4),
    APP(5, "APP", "BLUE", 6);

    companion object {
        fun of(code: Int): Tier = entries.first { it.code == code }
    }
}

/** What the phone reports about one package (New-Facts in the PowerShell version). */
class PackageFacts {
    var seen = false
    var appId: Int? = null
    var sharedUser = ""
    var persistent = false
    var privileged = false
    var partition = ""
    var signature = ""
    var platformSigned = false
    var hasIcon = false
    val sigPerms = ArrayList<String>()
    val usedBy = ArrayList<String>()
    val permHolders = ArrayList<String>()
    var overlayTarget = ""
    var overlayState = ""
    val roles = ArrayList<String>()
    var running = false
    var installTime = ""
    var installer = ""
    var userDisabled = false
    var userSuspended = false
}

/** What a restore row does when ticked. */
enum class RestoreAction { INSTALL, STORE, ENABLE, UNSUSPEND }

/**
 * One line in a package list: a scanned package, or an item in the Restore list.
 * [locked] rows can never be ticked (packages HyperDebloat itself needs while it runs on the phone).
 */
data class Row(
    val tier: Tier,
    val risk: String,
    val pkg: String,
    val description: String,
    val phone: String,
    val lists: String,
    val reason: String,
    val facts: PackageFacts? = null,
    val userApp: Boolean = false,
    val category: String = "",
    val ordinary: Boolean = false,
    val atSetup: Boolean = false,
    val locked: Boolean = false,
    val action: RestoreAction? = null,
)

/** One community-list entry. */
data class ListEntry(val tier: Int, val rating: String, val desc: String)

/** The three community lists, keyed by lower-case package name (lookups ignore case, as in PowerShell). */
class ListIndex {
    val uad = HashMap<String, ListEntry>()
    val fadeltd = HashMap<String, ListEntry>()
    val ovsky = HashMap<String, ListEntry>()
    val ovskyDesc = HashMap<String, String>()
    val loaded: Boolean get() = uad.size + fadeltd.size + ovsky.size > 0

    /** Description in the same order of preference as the PowerShell version. */
    fun describe(pkg: String): String {
        val k = pkg.lowercase()
        fadeltd[k]?.desc?.takeIf { it.isNotEmpty() }?.let { return it }
        ovskyDesc[k]?.takeIf { it.isNotEmpty() }?.let { return it }
        uad[k]?.desc?.let { return it }
        return ""
    }
}

/** Phone details read with getprop. */
data class DeviceInfo(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val marketName: String,
    val codename: String,
    val osName: String,
    val osBuild: String,
    val android: String,
    val sdk: Int,
) {
    /** Folder name for this phone and OS build, e.g. 25098PN5AI_OS3.0.5.0.WOQINXM. */
    val deviceDir: String get() = "${model}_$osBuild".replace(Regex("[^\\w.-]"), "_")
    val key: String get() = "$model|$osBuild"
}

/** Runs a shell command on the phone (over ADB) and returns its output lines. */
interface Shell {
    /** Calls [onLine] for every output line; large outputs (dumpsys, logcat) are never held in memory at once. */
    fun stream(command: String, onLine: (String) -> Unit)

    fun run(command: String): List<String> {
        val out = ArrayList<String>()
        stream(command) { out.add(it) }
        return out
    }

    /** All output joined with spaces and trimmed, like (Invoke-Shell ...) -join ' '. */
    fun text(command: String): String = run(command).joinToString(" ").trim()
}

/** App storage. Paths are relative to the work folder (the PowerShell Documents\HyperDebloat folder). */
interface Storage {
    fun readText(path: String): String?
    /** [export] also copies the file to the phone's Documents/HyperDebloat folder for the user. */
    fun writeText(path: String, text: String, export: Boolean = false)
    fun appendText(path: String, text: String)
    fun exists(path: String): Boolean
    fun lastModified(path: String): Long
    fun move(from: String, to: String)
    fun delete(path: String)
    /** Folder names directly in the work folder. */
    fun listDirs(): List<String>
    /** File names in one folder. */
    fun listFiles(dir: String): List<String>
}

/** Downloads a URL as text, or throws. */
fun interface Fetcher {
    fun fetch(url: String): String
}

/** Progress lines (the PowerShell console window). */
fun interface Log {
    fun line(text: String)
}

/** Package names are checked before they are put in a shell command. */
val PACKAGE_NAME = Regex("^[A-Za-z][\\w.]*$")

fun requirePackageName(pkg: String): String {
    require(PACKAGE_NAME.matches(pkg)) { "Not a valid package name: $pkg" }
    return pkg
}
