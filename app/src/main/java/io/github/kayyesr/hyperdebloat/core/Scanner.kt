package io.github.kayyesr.hyperdebloat.core

/** Read-only phone scan (Get-DeviceInfo, Get-PackageSets, Get-DeviceFacts). */
class Scanner(private val shell: Shell, private val log: Log) {

    private val I = setOf(RegexOption.IGNORE_CASE)
    private fun rx(p: String) = Regex(p, I)

    fun deviceInfo(): DeviceInfo {
        // Only the properties needed, one short command: "name=value" per line.
        val names = listOf("ro.product.model", "ro.mi.os.version.incremental", "ro.build.version.incremental",
            "ro.product.marketname", "ro.product.manufacturer", "ro.product.brand", "ro.product.device",
            "ro.mi.os.version.name", "ro.build.version.release", "ro.build.version.sdk")
        val props = HashMap<String, String>()
        for (l in shell.run(names.joinToString("; ") { "echo \"$it=$(getprop $it)\"" })) {
            val i = l.indexOf('=')
            if (i > 0) props[l.substring(0, i).trim()] = l.substring(i + 1)
        }
        fun p(name: String) = props[name].orEmpty().trim()
        val model = p("ro.product.model")
        var build = p("ro.mi.os.version.incremental")
        if (build.isEmpty()) build = p("ro.build.version.incremental")
        // A blank model means the answer did not arrive; never carry on with a nameless phone.
        if (model.isEmpty() && p("ro.product.manufacturer").isEmpty()) throw java.io.IOException("Could not read the phone's details")
        val market = p("ro.product.marketname").ifEmpty { model }
        return DeviceInfo(
            manufacturer = p("ro.product.manufacturer"),
            brand = p("ro.product.brand"),
            model = model,
            marketName = market,
            codename = p("ro.product.device"),
            osName = p("ro.mi.os.version.name"),
            osBuild = build,
            android = p("ro.build.version.release"),
            sdk = p("ro.build.version.sdk").toIntOrNull() ?: 0,
        )
    }

    class PackageSets(val all: LinkedHashMap<String, String>, val system: HashSet<String>) {
        /** Lower-case key -> package name as the phone spells it. */
        fun isSystem(pkg: String) = system.contains(pkg.lowercase())
    }

    private val pkgLine = Regex("^package:(\\S+)", RegexOption.IGNORE_CASE)

    fun packageSet(command: String): LinkedHashMap<String, String> {
        val set = LinkedHashMap<String, String>()
        for (l in shell.run(command)) pkgLine.find(l)?.let { set[it.groupValues[1].lowercase()] = it.groupValues[1] }
        return set
    }

    fun packageSets(): PackageSets {
        val all = packageSet("pm list packages --user 0")
        val sys = HashSet(packageSet("pm list packages -s --user 0").keys)
        if (all.isEmpty()) throw IllegalStateException("Could not read the package list from the phone.")
        return PackageSets(all, sys)
    }

    companion object {
        fun partition(codePath: String): String {
            val p = codePath.lowercase()
            return when {
                p.startsWith("/data/") -> "data"
                p.startsWith("/system_ext/") -> "system_ext"
                p.startsWith("/system/") -> "system"
                p.startsWith("/product/") -> "product"
                p.startsWith("/vendor/") -> "vendor"
                p.startsWith("/odm/") -> "odm"
                p.startsWith("/apex/") -> "apex"
                else -> Regex("^/([^/]+)/").find(codePath)?.groupValues?.get(1) ?: "unknown"
            }
        }

        private val pkgSlash = Regex("([A-Za-z]\\w*(?:\\.\\w+)+)/")
        private val pkgName = Regex("packageName=([\\w.]+)", RegexOption.IGNORE_CASE)

        /** Package names from "pkg/.Class" and "packageName=pkg" forms. */
        fun packagesFromText(lines: List<String>): Set<String> {
            val set = LinkedHashSet<String>()
            for (l in lines) {
                for (m in pkgSlash.findAll(l)) set.add(m.groupValues[1])
                pkgName.find(l)?.let { set.add(it.groupValues[1]) }
            }
            return set
        }
    }

    private val ICONS = "cmd package query-activities --user 0 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
    private val SERVICES = "dumpsys activity services"
    private val PROVIDERS = "dumpsys activity providers"
    private val OVERLAYS = "cmd overlay list --user 0"
    private fun role(r: String) = "cmd role get-role-holders --user 0 android.app.role.$r"
    private val IME = "settings get secure default_input_method"
    private val ADMINS = "dumpsys device_policy"
    private val LOCATION = listOf(
        "com.android.location.service.v3.NetworkLocationProvider",
        "com.android.location.service.v2.NetworkLocationProvider",
        "com.android.location.service.FusedLocationProvider",
        "com.android.location.service.GeocodeProvider",
        "com.android.location.service.GeofenceProvider",
    ).joinToString("; ") { "cmd package query-services --user 0 -a $it" }
    private val PROCESSES = "ps -A -o NAME"

    /** Facts for every installed package, keyed by lower-case package name. */
    fun deviceFacts(installed: Map<String, String>): HashMap<String, PackageFacts> {
        // The small reads run together first (on the phone they run at the same time).
        log.line("Reading app connections, roles and processes...")
        val small = shell.runAll(listOf(ICONS, SERVICES, PROVIDERS, OVERLAYS, role("HOME"), role("DIALER"), role("SMS"),
            role("BROWSER"), IME, ADMINS, LOCATION, PROCESSES))
        fun out(c: String) = small[c].orEmpty()

        val facts = HashMap<String, PackageFacts>()
        for (k in installed.keys) facts[k] = PackageFacts()
        fun f(pkg: String?): PackageFacts? = if (pkg.isNullOrEmpty()) null else facts[pkg.lowercase()]
        fun addUnique(list: MutableList<String>, v: String) { if (list.none { it.equals(v, ignoreCase = true) }) list.add(v) }

        // 1. Package manager dump: partition, privileged, persistent, identity, signature, permissions.
        log.line("Reading package details...")
        var cur: String? = null
        var inPk = false
        var permMode = ""
        var permIndent = -1
        var androidSig = ""
        val granted = HashMap<String, HashSet<String>>() // holder (lower) -> permissions (lower)

        val rSection = rx("^(Packages|Hidden system packages):")
        val rPackage = rx("^\\s*Package \\[([^\\]]+)\\]")
        val rDeclared = rx("^\\s*([\\w.]+):\\s*prot=(\\S+)")
        val rGranted = rx("^\\s*([\\w.]+):\\s*granted=true")
        val rPermHead = rx("^\\s*(declared|install|runtime) permissions:")
        val rSig = rx("^\\s*signatures=PackageSignatures\\{.*?signatures:\\[([^\\]]*)\\]")
        val rId = rx("^\\s*(userId|appId)=(\\d+)")
        val rShared = rx("^\\s*sharedUser=SharedUserSetting\\{\\w+\\s+([^/\\s}]+)")
        val rFirst = rx("^\\s*firstInstallTime=(.+)$")
        val rInstaller = rx("^\\s*installerPackageName=(\\S+)")
        val rUser0 = rx("^\\s*User 0:")
        val rDisabled3 = rx("\\benabled=3\\b")
        val rSuspended = rx("\\binstalled=true\\b.*\\bsuspended=true\\b")
        val rCode = rx("^\\s*codePath=(\\S+)")
        val rFlags = rx("^\\s*(pkgFlags|flags)=\\[(.*)\\]")
        val rPersistent = rx("(^|\\s)PERSISTENT(\\s|$)")
        val rPrivFlags = rx("^\\s*privateFlags=\\[(.*)\\]")
        val rPrivileged = rx("(^|[\\s_])PRIVILEGED(\\s|$)")
        val rPrivApp = rx("/priv-app/")

        shell.streamLarge("dumpsys package packages") { line ->
            if (line.isNotEmpty() && !line[0].isWhitespace()) {
                inPk = rSection.containsMatchIn(line)
                cur = null; permMode = ""
                return@streamLarge
            }
            if (!inPk) return@streamLarge
            rPackage.find(line)?.let { m ->
                cur = m.groupValues[1]; permMode = ""
                f(cur)?.seen = true
                return@streamLarge
            }
            val c = cur ?: return@streamLarge
            val fc = f(c)
            val indent = line.length - line.trimStart().length

            if (permMode.isNotEmpty()) {
                if (indent > permIndent) {
                    if (permMode.equals("declared", ignoreCase = true)) {
                        if (fc != null) rDeclared.find(line)?.let { m ->
                            if (m.groupValues[2].contains("signature", ignoreCase = true)) addUnique(fc.sigPerms, m.groupValues[1])
                        }
                    } else {
                        rGranted.find(line)?.let { m -> granted.getOrPut(c.lowercase()) { HashSet() }.add(m.groupValues[1].lowercase()) }
                    }
                    return@streamLarge
                }
                permMode = ""
            }
            rPermHead.find(line)?.let { m -> permMode = m.groupValues[1]; permIndent = indent; return@streamLarge }

            rSig.find(line)?.let { m ->
                if (c.equals("android", ignoreCase = true) && androidSig.isEmpty()) androidSig = m.groupValues[1]
                if (fc != null && fc.signature.isEmpty()) fc.signature = m.groupValues[1]
                return@streamLarge
            }
            if (fc == null) return@streamLarge
            val mId = rId.find(line)
            if (mId != null) {
                val id = mId.groupValues[2].toIntOrNull()
                if (id != null && (fc.appId == null || id < fc.appId!!)) fc.appId = id
                return@streamLarge
            }
            rShared.find(line)?.let { fc.sharedUser = it.groupValues[1]; return@streamLarge }
            rFirst.find(line)?.let { if (fc.installTime.isEmpty()) fc.installTime = it.groupValues[1].trim(); return@streamLarge }
            rInstaller.find(line)?.let { if (fc.installer.isEmpty()) fc.installer = it.groupValues[1]; return@streamLarge }
            if (rUser0.containsMatchIn(line)) {
                if (rDisabled3.containsMatchIn(line)) fc.userDisabled = true // switched off by a user or a tool
                if (rSuspended.containsMatchIn(line)) fc.userSuspended = true // frozen by this tool
                return@streamLarge
            }
            rCode.find(line)?.let { m ->
                val part = partition(m.groupValues[1])
                // The active entry comes first; an updated system app keeps its original partition.
                if (fc.partition.isEmpty() || fc.partition.equals("data", ignoreCase = true)) fc.partition = part
                if (rPrivApp.containsMatchIn(m.groupValues[1])) fc.privileged = true
                return@streamLarge
            }
            rFlags.find(line)?.let { m -> if (rPersistent.containsMatchIn(m.groupValues[2])) fc.persistent = true; return@streamLarge }
            rPrivFlags.find(line)?.let { m -> if (rPrivileged.containsMatchIn(m.groupValues[1])) fc.privileged = true }
        }
        if (androidSig.isNotEmpty()) {
            for ((k, v) in facts) if (v.signature == androidSig && k != "android") v.platformSigned = true
        } else log.line("! Could not read the platform signature; that check is skipped.")

        // Real permission dependency: other installed apps that hold a protected permission this package defines.
        val owner = HashMap<String, String>()
        for ((k, v) in facts) for (perm in v.sigPerms) owner[perm.lowercase()] = k
        for ((holder, perms) in granted) {
            val hf = facts[holder] ?: continue
            for (perm in perms) {
                val o = owner[perm] ?: continue
                if (o == holder) continue
                val hs = hf.sharedUser
                if (hs.isNotEmpty() && hs.equals(facts[o]!!.sharedUser, ignoreCase = true)) continue
                addUnique(facts[o]!!.permHolders, installed[holder] ?: holder)
            }
        }

        // 2. Which packages have an app icon (a launcher entry) - i.e. are user-facing apps.
        log.line("Reading app icons...")
        for (p in packagesFromText(out(ICONS))) {
            f(p)?.hasIcon = true
        }

        // 3. Live dependencies: other processes bound to this package's services or providers right now.
        // This is a snapshot, so connections only show for apps that are open or running at this moment.
        log.line("Reading live service and provider connections (only apps running right now)...")
        val rSvc = rx("ServiceRecord\\{\\w+\\s+u\\d+\\s+([\\w.]+)/")
        val rApp = rx("^\\s*app=")
        val rProc = rx("ProcessRecord\\{\\w+\\s+\\d+:([^/\\s}]+)/")
        var svc: String? = null
        for (l in out(SERVICES)) {
            val ms = rSvc.find(l)
            if (ms != null) { svc = ms.groupValues[1]; continue }
            val s = svc ?: continue
            if (rApp.containsMatchIn(l)) continue
            rProc.find(l)?.let { m ->
                val client = m.groupValues[1].split(":")[0]
                val fs = f(s)
                if (fs != null && !client.equals(s, ignoreCase = true)) addUnique(fs.usedBy, client)
            }
        }
        val rProv = rx("ContentProviderRecord\\{\\w+\\s+u\\d+\\s+([\\w.]+)/")
        val rConn = rx("->\\s*\\d+:([^/\\s]+)/")
        var prov: String? = null
        for (l in out(PROVIDERS)) {
            val mp = rProv.find(l)
            if (mp != null) { prov = mp.groupValues[1]; continue }
            val pv = prov ?: continue
            rConn.find(l)?.let { m ->
                val client = m.groupValues[1].split(":")[0]
                val fp = f(pv)
                if (fp != null && !client.equals(pv, ignoreCase = true)) addUnique(fp.usedBy, client)
            }
        }

        // 4. Runtime resource overlays and what they modify.
        val rTarget = rx("^([\\w.]+)\\s*$")
        val rOverlay = rx("^\\s*(\\[x\\]|\\[ \\]|---)\\s+([\\w.]+)")
        var target: String? = null
        for (l in out(OVERLAYS)) {
            val mt = rTarget.find(l)
            if (mt != null) { target = mt.groupValues[1]; continue }
            val t = target ?: continue
            rOverlay.find(l)?.let { m ->
                f(m.groupValues[2])?.let { fo ->
                    fo.overlayTarget = t
                    fo.overlayState = if (m.groupValues[1] == "[ ]") "disabled" else "enabled"
                }
            }
        }

        // 5. Default roles, keyboard, device admins, location providers.
        for (role in listOf("HOME", "DIALER", "SMS")) {
            for (l in out(role(role))) {
                for (p in l.split(Regex("[;\\s]+"))) f(p)?.let { addUnique(it.roles, "current default ${role.lowercase()} app") }
            }
        }
        for (l in out(role("BROWSER"))) {
            for (p in l.split(Regex("[;\\s]+"))) f(p)?.defaultBrowser = true
        }
        val ime = out(IME).joinToString("").trim()
        rx("^([\\w.]+)/").find(ime)?.let { m -> f(m.groupValues[1])?.let { addUnique(it.roles, "current default keyboard") } }
        val rAdmin = Regex("ComponentInfo\\{([\\w.]+)/")
        for (l in out(ADMINS)) {
            for (m in rAdmin.findAll(l)) f(m.groupValues[1])?.let { addUnique(it.roles, "device administrator") }
        }
        for (p in packagesFromText(out(LOCATION))) f(p)?.let { addUnique(it.roles, "provides location services") }

        // 6. Running processes (informational).
        log.line("Reading running processes...")
        val running = HashSet<String>()
        for (l in out(PROCESSES)) {
            val n = l.trim().split(":")[0]
            if (n.isNotEmpty()) running.add(n.lowercase())
        }
        for ((k, v) in facts) if (running.contains(k)) v.running = true
        return facts
    }
}
