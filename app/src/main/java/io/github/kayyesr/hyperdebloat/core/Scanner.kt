package io.github.kayyesr.hyperdebloat.core

/** Read-only phone scan (Get-DeviceInfo, Get-PackageSets, Get-DeviceFacts). */
class Scanner(private val shell: Shell, private val log: Log) {

    private val I = setOf(RegexOption.IGNORE_CASE)
    private fun rx(p: String) = Regex(p, I)

    fun deviceInfo(): DeviceInfo {
        fun p(name: String) = shell.run("getprop $name").joinToString("").trim()
        val model = p("ro.product.model")
        var build = p("ro.mi.os.version.incremental")
        if (build.isEmpty()) build = p("ro.build.version.incremental")
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

    /** Facts for every installed package, keyed by lower-case package name. */
    fun deviceFacts(installed: Map<String, String>): HashMap<String, PackageFacts> {
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

        shell.stream("dumpsys package packages") { line ->
            if (line.isNotEmpty() && !line[0].isWhitespace()) {
                inPk = rSection.containsMatchIn(line)
                cur = null; permMode = ""
                return@stream
            }
            if (!inPk) return@stream
            rPackage.find(line)?.let { m ->
                cur = m.groupValues[1]; permMode = ""
                f(cur)?.seen = true
                return@stream
            }
            val c = cur ?: return@stream
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
                    return@stream
                }
                permMode = ""
            }
            rPermHead.find(line)?.let { m -> permMode = m.groupValues[1]; permIndent = indent; return@stream }

            rSig.find(line)?.let { m ->
                if (c.equals("android", ignoreCase = true) && androidSig.isEmpty()) androidSig = m.groupValues[1]
                if (fc != null && fc.signature.isEmpty()) fc.signature = m.groupValues[1]
                return@stream
            }
            if (fc == null) return@stream
            val mId = rId.find(line)
            if (mId != null) {
                val id = mId.groupValues[2].toIntOrNull()
                if (id != null && (fc.appId == null || id < fc.appId!!)) fc.appId = id
                return@stream
            }
            rShared.find(line)?.let { fc.sharedUser = it.groupValues[1]; return@stream }
            rFirst.find(line)?.let { if (fc.installTime.isEmpty()) fc.installTime = it.groupValues[1].trim(); return@stream }
            rInstaller.find(line)?.let { if (fc.installer.isEmpty()) fc.installer = it.groupValues[1]; return@stream }
            if (rUser0.containsMatchIn(line)) {
                if (rDisabled3.containsMatchIn(line)) fc.userDisabled = true // switched off by a user or a tool
                if (rSuspended.containsMatchIn(line)) fc.userSuspended = true // frozen by this tool
                return@stream
            }
            rCode.find(line)?.let { m ->
                val part = partition(m.groupValues[1])
                // The active entry comes first; an updated system app keeps its original partition.
                if (fc.partition.isEmpty() || fc.partition.equals("data", ignoreCase = true)) fc.partition = part
                if (rPrivApp.containsMatchIn(m.groupValues[1])) fc.privileged = true
                return@stream
            }
            rFlags.find(line)?.let { m -> if (rPersistent.containsMatchIn(m.groupValues[2])) fc.persistent = true; return@stream }
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
        for (p in packagesFromText(shell.run("cmd package query-activities --user 0 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"))) {
            f(p)?.hasIcon = true
        }

        // 3. Live dependencies: other processes bound to this package's services or providers right now.
        // This is a snapshot, so connections only show for apps that are open or running at this moment.
        log.line("Reading live service and provider connections (only apps running right now)...")
        val rSvc = rx("ServiceRecord\\{\\w+\\s+u\\d+\\s+([\\w.]+)/")
        val rApp = rx("^\\s*app=")
        val rProc = rx("ProcessRecord\\{\\w+\\s+\\d+:([^/\\s}]+)/")
        var svc: String? = null
        shell.stream("dumpsys activity services") { l ->
            rSvc.find(l)?.let { svc = it.groupValues[1]; return@stream }
            val s = svc ?: return@stream
            if (rApp.containsMatchIn(l)) return@stream
            rProc.find(l)?.let { m ->
                val client = m.groupValues[1].split(":")[0]
                val fs = f(s)
                if (fs != null && !client.equals(s, ignoreCase = true)) addUnique(fs.usedBy, client)
            }
        }
        val rProv = rx("ContentProviderRecord\\{\\w+\\s+u\\d+\\s+([\\w.]+)/")
        val rConn = rx("->\\s*\\d+:([^/\\s]+)/")
        var prov: String? = null
        shell.stream("dumpsys activity providers") { l ->
            rProv.find(l)?.let { prov = it.groupValues[1]; return@stream }
            val pv = prov ?: return@stream
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
        for (l in shell.run("cmd overlay list --user 0")) {
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
            for (l in shell.run("cmd role get-role-holders --user 0 android.app.role.$role")) {
                for (p in l.split(Regex("[;\\s]+"))) f(p)?.let { addUnique(it.roles, "current default ${role.lowercase()} app") }
            }
        }
        val ime = shell.run("settings get secure default_input_method").joinToString("").trim()
        rx("^([\\w.]+)/").find(ime)?.let { m -> f(m.groupValues[1])?.let { addUnique(it.roles, "current default keyboard") } }
        val rAdmin = Regex("ComponentInfo\\{([\\w.]+)/")
        for (l in shell.run("dumpsys device_policy")) {
            for (m in rAdmin.findAll(l)) f(m.groupValues[1])?.let { addUnique(it.roles, "device administrator") }
        }
        val locActions = listOf(
            "com.android.location.service.v3.NetworkLocationProvider",
            "com.android.location.service.v2.NetworkLocationProvider",
            "com.android.location.service.FusedLocationProvider",
            "com.android.location.service.GeocodeProvider",
            "com.android.location.service.GeofenceProvider",
        )
        for (a in locActions) {
            for (p in packagesFromText(shell.run("cmd package query-services --user 0 -a $a"))) {
                f(p)?.let { addUnique(it.roles, "provides location services") }
            }
        }

        // 6. Running processes (informational).
        log.line("Reading running processes...")
        val running = HashSet<String>()
        for (l in shell.run("ps -A -o NAME")) {
            val n = l.trim().split(":")[0]
            if (n.isNotEmpty()) running.add(n.lowercase())
        }
        for ((k, v) in facts) if (running.contains(k)) v.running = true
        return facts
    }
}
