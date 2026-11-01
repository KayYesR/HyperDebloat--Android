package io.github.kayyesr.hyperdebloat.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Canned phone output in the formats the PowerShell parsers were written against. */
class FakeShell(private val outputs: Map<String, String>) : Shell {
    val calls = ArrayList<String>()
    override fun stream(command: String, onLine: (String) -> Unit) {
        calls.add(command)
        (outputs[command] ?: "").split("\n").forEach(onLine)
    }
}

class CoreTest {

    private val dumpsys = """
Database versions:
  Internal:
Packages:
  Package [com.miui.analytics] (a1b2c3):
    userId=10101
    pkg=Package{d4 com.miui.analytics}
    codePath=/system/app/AnalyticsCore
    flags=[ SYSTEM HAS_CODE ALLOW_CLEAR_USER_DATA ]
    privateFlags=[ PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE ]
    signatures=PackageSignatures{e5 version:3, signatures:[abc123], past signatures:[]}
    firstInstallTime=2009-01-01 08:00:00
    installerPackageName=null
    User 0: ceDataInode=1 installed=true hidden=false suspended=false distractionFlags=0 stopped=false notLaunched=false enabled=0 instant=false virtual=false
  Package [android] (f6):
    userId=1000
    codePath=/system/framework/framework-res.apk
    flags=[ SYSTEM PERSISTENT ]
    signatures=PackageSignatures{a7 version:3, signatures:[platformsig], past signatures:[]}
    declared permissions:
      android.permission.FOO: prot=signature|privileged, INSTALLED
    User 0: installed=true hidden=false suspended=false enabled=0
  Package [com.miui.securitycenter] (g8):
    userId=1000
    sharedUser=SharedUserSetting{h9 android.uid.system/1000}
    codePath=/data/app/~~x/com.miui.securitycenter-1
    flags=[ SYSTEM UPDATED_SYSTEM_APP PERSISTENT ]
    privateFlags=[ PRIVILEGED ]
    signatures=PackageSignatures{i0 version:3, signatures:[platformsig], past signatures:[]}
    User 0: installed=true hidden=false suspended=false enabled=0
  Package [com.example.ownperm] (j1):
    userId=10200
    codePath=/product/app/OwnPerm
    flags=[ SYSTEM ]
    signatures=PackageSignatures{k2 version:3, signatures:[other], past signatures:[]}
    declared permissions:
      com.example.ownperm.permission.USE: prot=signature, INSTALLED
    User 0: installed=true hidden=false suspended=false enabled=0
  Package [com.mi.game.one] (l3):
    userId=10300
    codePath=/data/app/~~y/com.mi.game.one-1
    flags=[ HAS_CODE ]
    firstInstallTime=2026-10-20 10:00:00
    installerPackageName=com.xiaomi.mipicks
    install permissions:
      com.example.ownperm.permission.USE: granted=true
    User 0: installed=true hidden=false suspended=false enabled=0
  Package [com.user.app] (m4):
    userId=10301
    codePath=/data/app/~~z/com.user.app-1
    flags=[ HAS_CODE ]
    firstInstallTime=2026-10-22 18:30:00
    installerPackageName=com.android.vending
    User 0: installed=true hidden=false suspended=false enabled=0
  Package [com.frozen.one] (n5):
    userId=10302
    codePath=/system/app/Frozen
    flags=[ SYSTEM ]
    User 0: installed=true hidden=false suspended=true enabled=0
  Package [com.vendor.thing] (o6):
    userId=10303
    codePath=/vendor/app/Thing
    flags=[ SYSTEM ]
    User 0: installed=true hidden=false suspended=false enabled=0
Hidden system packages:
  Package [com.miui.securitycenter] (p7):
    userId=1000
    codePath=/system/priv-app/SecurityCenter
    flags=[ SYSTEM PERSISTENT ]
Queries:
  system apps queryable: false
""".trimIndent()

    private fun shell(extra: Map<String, String> = emptyMap()) = FakeShell(mapOf(
        "pm list packages --user 0" to listOf("android", "com.miui.analytics", "com.miui.securitycenter", "com.example.ownperm",
            "com.mi.game.one", "com.user.app", "com.frozen.one", "com.vendor.thing", "io.github.kayyesr.hyperdebloat", "com.android.settings")
            .joinToString("\n") { "package:$it" },
        "pm list packages -s --user 0" to listOf("android", "com.miui.analytics", "com.miui.securitycenter", "com.example.ownperm",
            "com.frozen.one", "com.vendor.thing", "com.android.settings").joinToString("\n") { "package:$it" },
        "dumpsys package packages" to dumpsys,
        "cmd package query-activities --user 0 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER" to
            "Activity #0:\n  priority=0\n  ActivityInfo:\n    name=com.user.app.Main\n    packageName=com.user.app\n",
        "dumpsys activity services" to """
  * ServiceRecord{q8 u0 com.example.ownperm/.Svc}
    app=ProcessRecord{r9 1234:com.example.ownperm/u0a200}
    Bindings:
      * IntentBindRec{s0}:
        * Client AppBindRecord{t1 ProcessRecord{u2 4321:com.user.app/u0a301}}
""".trimIndent(),
        "cmd role get-role-holders --user 0 android.app.role.HOME" to "com.miui.home",
        "ps -A -o NAME" to "NAME\ncom.miui.analytics\ncom.user.app:remote",
    ) + extra)

    @Test fun jsonRoundTrip() {
        val m = Json.parse("""{"a": "x\"y", "b": [1, 2.5, true, null], "c": {"d": "é"}}""").asMap()!!
        assertEquals("x\"y", m["a"])
        assertEquals(4, m["b"].asList()!!.size)
        assertEquals("é", m["c"].asMap()!!["d"])
        val s = Json.stringify(mapOf("com.a" to "2026-10-28 11:00:00", "b" to "suspended x"))
        assertEquals("suspended x", Json.parse(s).asMap()!!["b"])
    }

    @Test fun csvRoundTrip() {
        val text = Csv.write(listOf("Time", "Package", "Result"), listOf(listOf("2026-10-28T11:00:00", "com.a", "Failure [-1000], \"x\"")))
        val rows = Csv.read(text)
        assertEquals("Failure [-1000], \"x\"", rows[0]["Result"])
        assertEquals("com.a", rows[0]["Package"])
    }

    @Test fun partitions() {
        assertEquals("system_ext", Scanner.partition("/system_ext/priv-app/X"))
        assertEquals("vendor", Scanner.partition("/vendor/app/X"))
        assertEquals("mi_ext", Scanner.partition("/mi_ext/product/app/X"))
    }

    @Test fun factsAndRatings() {
        val sh = shell()
        val sc = Scanner(sh) {}
        val sets = sc.packageSets()
        val facts = sc.deviceFacts(sets.all)

        val sec = facts["com.miui.securitycenter"]!!
        assertTrue(sec.persistent)
        assertTrue(sec.privileged)
        assertEquals("system", sec.partition) // updated system app keeps its original partition
        assertTrue(sec.platformSigned)
        assertEquals(listOf("com.mi.game.one"), facts["com.example.ownperm"]!!.permHolders)
        assertEquals(listOf("com.user.app"), facts["com.example.ownperm"]!!.usedBy)
        assertTrue(facts["com.user.app"]!!.hasIcon)
        assertTrue(facts["com.frozen.one"]!!.userSuspended)
        assertTrue(facts["com.miui.analytics"]!!.running)

        val idx = ListIndex()
        idx.uad["com.miui.analytics"] = ListEntry(1, "Recommended", "Analytics")
        idx.fadeltd["com.miui.analytics"] = ListEntry(1, "safe", "Xiaomi analytics")
        idx.ovsky["com.miui.analytics"] = ListEntry(1, "phase1_safe", "")
        idx.uad["com.example.ownperm"] = ListEntry(1, "Recommended", "")
        idx.uad["com.vendor.thing"] = ListEntry(1, "Recommended", "")
        idx.fadeltd["com.vendor.thing"] = ListEntry(1, "safe", "")

        val rows = Classifier.classify(idx, sets, facts, "io.github.kayyesr.hyperdebloat")
        val by = rows.associateBy { it.pkg }

        assertFalse(by.containsKey("io.github.kayyesr.hyperdebloat")) // itself left out
        assertFalse(by.containsKey("com.frozen.one"))                // frozen: listed in Restore instead
        assertEquals(Tier.SAFE, by["com.miui.analytics"]!!.tier)
        assertEquals("Ads & tracking", by["com.miui.analytics"]!!.category)
        // persistent, but unknown to every list: flagged (as in the PowerShell version)
        assertEquals(Tier.FLAGGED, by["com.miui.securitycenter"]!!.tier)
        assertTrue(by["com.miui.securitycenter"]!!.phone.startsWith("RED: persistent"))
        assertEquals(Tier.FLAGGED, by["android"]!!.tier)
        // in use right now by another app -> ORANGE; one list says safe -> stays
        assertEquals(Tier.HIGH, by["com.example.ownperm"]!!.tier)
        // vendor partition -> yellow at least; two lists say safe but hardware stays yellow
        assertEquals(Tier.MODERATE, by["com.vendor.thing"]!!.tier)
        assertTrue(by["com.android.settings"]!!.locked)
        // ordinary apps: game installed within 2 h of the first app -> setup
        assertTrue(by["com.mi.game.one"]!!.atSetup)
        assertFalse(by["com.user.app"]!!.atSetup)
        assertEquals(Tier.APP, by["com.user.app"]!!.tier)
        // order: green first, blue last
        assertEquals(Tier.SAFE, rows.first().tier)
        assertEquals(Tier.APP, rows.last().tier)
    }

    @Test fun mostlyOkayAndEssentialGoogle() {
        val facts = HashMap<String, PackageFacts>()
        for (p in listOf("com.miui.analytics", "com.android.chrome", "com.google.android.gm", "com.android.vending"))
            facts[p] = PackageFacts().apply { seen = true; partition = "system" }
        val idx = ListIndex()
        for (p in facts.keys) {
            idx.uad[p] = ListEntry(1, "Recommended", "")
            idx.fadeltd[p] = ListEntry(1, "safe", "")
        }
        // Phone sees nothing using it, but only 2 of the 3 lists know it: Mostly okay, not Safe.
        assertEquals(Tier.MOSTLY, Classifier.rating("com.miui.analytics", true, idx, facts)!!.tier)
        idx.ovsky["com.miui.analytics"] = ListEntry(1, "phase1_safe", "")
        assertEquals(Tier.SAFE, Classifier.rating("com.miui.analytics", true, idx, facts)!!.tier)

        // Chrome as the default browser: kept at least yellow, with advice.
        facts["com.android.chrome"]!!.defaultBrowser = true
        val chrome = Classifier.rating("com.android.chrome", true, idx, facts)!!
        assertEquals(Tier.MODERATE, chrome.tier)
        assertTrue(chrome.note.contains("default browser"))
        assertEquals("Essential Google apps", chrome.category)
        // Another browser is default: Chrome may be removed.
        facts["com.android.chrome"]!!.defaultBrowser = false
        assertTrue(Classifier.rating("com.android.chrome", true, idx, facts)!!.note.contains("can be removed"))

        // Gmail: never ticked (userApp), advice to keep if used. Play Store: always unsafe.
        val gmail = Classifier.rating("com.google.android.gm", true, idx, facts)!!
        assertTrue(gmail.userApp)
        assertTrue(gmail.note.contains("don't use Gmail"))
        assertEquals(Tier.UNSAFE, Classifier.rating("com.android.vending", true, idx, facts)!!.tier)
    }

    @Test fun categories() {
        assertEquals("Games", Classifier.category("com.xiaomi.glgm", "Xiaomi games centre", true, "product"))
        assertEquals("Media & music", Classifier.category("com.miui.player", "Music player", true, "product"))
        assertEquals("Ads & tracking", Classifier.category("com.miui.msa.global", "", false, "system"))
        assertEquals("Other preinstalled apps", Classifier.category("com.netflix.mediaclient", "", true, "product"))
        assertEquals("Essential Google apps", Classifier.category("com.google.android.youtube", "", true, "product"))
    }

    @Test fun unknownToListsIsFlagged() {
        val sh = shell()
        val sc = Scanner(sh) {}
        val sets = sc.packageSets()
        val facts = sc.deviceFacts(sets.all)
        val idx = ListIndex().apply { uad["something.else"] = ListEntry(1, "Recommended", "") }
        val row = Classifier.rating("com.miui.analytics", true, idx, facts)!!
        assertEquals(Tier.FLAGGED, row.tier)
        // no lists at all: green is raised to yellow
        val row2 = Classifier.rating("com.miui.analytics", true, ListIndex(), facts)!!
        assertEquals(Tier.MODERATE, row2.tier)
    }
}

class EngineTest {
    private class MemStorage : Storage {
        val files = HashMap<String, String>()
        override fun readText(path: String) = files[path]
        override fun writeText(path: String, text: String, export: Boolean) { files[path] = text }
        override fun appendText(path: String, text: String) { files[path] = (files[path] ?: "") + text }
        override fun exists(path: String) = files.containsKey(path)
        override fun lastModified(path: String) = 0L
        override fun move(from: String, to: String) { files[to] = files.remove(from)!! }
        override fun delete(path: String) { files.remove(path) }
        override fun listDirs() = files.keys.filter { it.contains('/') }.map { it.substringBefore('/') }.distinct()
        override fun listFiles(dir: String) = files.keys.filter { it.startsWith("$dir/") }.map { it.substringAfter('/') }
    }

    @Test fun removeChecksThePhoneReallyDroppedIt() {
        val sh = FakeShell(mapOf(
            "pm uninstall -k --user 0 com.gone" to "Success",
            "pm list packages --user 0 com.gone" to "",
            "pm uninstall -k --user 0 com.sticky" to "Success",
            "pm list packages --user 0 com.sticky" to "package:com.sticky",
            "dumpsys package com.sticky" to "  User 0: ceDataInode=1 installed=true hidden=false suspended=true enabled=0",
            "pm uninstall -k --user 0 com.protected" to "Failure [-1000]",
            "pm list packages --user 0 com.protected" to "package:com.protected",
            "dumpsys package com.protected" to "  User 0: installed=true suspended=false",
            "pm suspend --user 0 com.protected" to "Error: not allowed",
        ))
        val e = Engine(sh, MemStorage(), {}, 35, "test").apply { deviceDir = "M_B" }
        assertEquals("Success", e.removeOnePackage("com.gone", false))
        assertTrue(e.removeOnePackage("com.sticky", false).startsWith("Suspended (could not be removed: Phone said Success"))
        assertEquals("Failure [-1000]", e.removeOnePackage("com.protected", false, noFreeze = true))
        assertTrue(e.removeOnePackage("com.protected", false).contains("freezing refused too: Error: not allowed"))
    }

    @Test fun removalIsLoggedAndRemembered() {
        val st = MemStorage()
        val e = Engine(FakeShell(emptyMap()), st, {}, 35, "test").apply { deviceDir = "M_B" }
        val results = listOf(
            Engine.Result("2026-10-28T11:00:00", "com.a", "SAFE", "Success", "desc a"),
            Engine.Result("2026-10-28T11:00:01", "com.b", "SAFE", "Suspended (came straight back after removal)", ""),
            Engine.Result("2026-10-28T11:00:02", "com.c", "SAFE", "Failure [-1000]", ""),
        )
        val s = e.finishRemoval(results)
        assertEquals(1, s.removed); assertEquals(1, s.frozen)
        val state = e.removedState()
        assertEquals("2026-10-28 11:00:00", state["com.a"])
        assertEquals("suspended 2026-10-28 11:00:01", state["com.b"])
        assertFalse(state.containsKey("com.c"))
        // Restore reads this log for dates and descriptions
        val items = e.restoreItems("M", Engine.PhoneState(mapOf("com.a" to true), emptySet(), emptySet())) { null }
        assertEquals("removed by HyperDebloat on 2026-10-28", items[0].reason)
        assertEquals("desc a", items[0].description)
    }

    @Test(expected = IllegalArgumentException::class)
    fun packageNamesAreCheckedBeforeShellUse() {
        Engine(FakeShell(emptyMap()), MemStorage(), {}, 35, "test").removeOnePackage("com.a; reboot", false)
    }
}
