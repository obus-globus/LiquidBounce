/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */

/*
 * Relocate selected packages inside a built jar (and its nested jar-in-jar entries) into a private
 * prefix, so a host that ships its own copy of those packages on a shared classloader can't shadow
 * LiquidBounce's. This is why it exists: Lunar Client bundles a stripped `okhttp3` (no Kotlin
 * `Headers.Companion`); under Lunar's Ichor classloader that copy wins over LiquidBounce's bundled
 * okhttp 5, so every LiquidBounce okhttp call throws `NoSuchFieldError` and trips the fatal error
 * handler. Relocating okhttp/okio to `net/ccbluex/liquidbounce/libs/...` makes LiquidBounce use its
 * own copy unconditionally, on Lunar or anywhere else.
 *
 * Invoked from the `jar` task's own `doLast` (not a separate finalizer) so the relocated jar is the
 * jar task's declared output and its up-to-date checking keeps working.
 */

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object JarRelocator {

    /**
     * Rewrite [jarFile] in place, relocating [packages] (top-level internal names, e.g. "okhttp3") under
     * [prefix] (a slash-terminated internal-name prefix), recursing into nested jar-in-jar entries.
     */
    fun relocate(jarFile: File, packages: List<String>, prefix: String) {
        jarFile.writeBytes(Relocation(packages, prefix).processJar(jarFile.readBytes(), nested = false))
    }

    private class Relocation(private val pkgs: List<String>, private val pfx: String) {
        private val pfxDot = pfx.replace('/', '.')
        private val multiRelease = Regex("^(META-INF/versions/\\d+/)(.*)$")

        // Match a relocated package name at the start of a service-file line, up to a `.`/`/` boundary or
        // end of line, so a sibling like `okiofoo` is never touched.
        private val serviceContentPkg =
            Regex("(?m)^(" + pkgs.joinToString("|") { Regex.escape(it) } + ")(?=[./]|\$)")

        private fun inPkgSlash(s: String) = pkgs.any { s == it || s.startsWith("$it/") }
        private fun inPkgDot(s: String) = pkgs.any { s == it || s.startsWith("$it.") }

        private inner class Reloc : Remapper() {
            override fun map(internalName: String): String =
                if (inPkgSlash(internalName)) pfx + internalName else internalName

            // NOTE: only bytecode type references and string constants are remapped. Kotlin `@Metadata`
            // internal-name arrays and `META-INF/*.kotlin_module` keep the original names; that is harmless
            // for compiled call sites (only kotlin-reflect over the relocated types would notice, which
            // LiquidBounce does not do).
            override fun mapValue(value: Any?): Any? {
                if (value is String) {
                    for (p in pkgs) {
                        if (value == p || value.startsWith("$p.")) return pfxDot + value            // Class.forName style
                        if (value.startsWith("$p/")) return pfx + value                             // relative resource path
                        if (value.startsWith("/$p/")) return "/" + pfx + value.removePrefix("/")     // absolute resource path
                    }
                }
                return super.mapValue(value)
            }
        }

        private fun remapClass(input: ByteArray): ByteArray {
            val cr = ClassReader(input)
            val cw = ClassWriter(0)
            cr.accept(ClassRemapper(cw, Reloc()), 0)
            return cw.toByteArray()
        }

        /** Output name for an entry: every entry in a relocated package moves under the prefix -- classes AND
         *  resources (e.g. okhttp's publicsuffix .list) AND directory entries -- so nothing is left orphaned in
         *  the original namespace. Also handles multi-release (META-INF/versions/<n>/...) and META-INF/services. */
        private fun relocatedName(name: String): String {
            if (name.startsWith("META-INF/services/")) {
                val svc = name.removePrefix("META-INF/services/")   // dotted binary name
                return if (inPkgDot(svc)) "META-INF/services/$pfxDot$svc" else name
            }
            multiRelease.matchEntire(name)?.destructured?.let { (mrPfx, rest) ->
                return if (inPkgSlash(rest)) "$mrPfx$pfx$rest" else name
            }
            return if (inPkgSlash(name)) pfx + name else name
        }

        // Fabric dedupes nested jar-in-jar libraries by their mod id (keeping the highest version), so a
        // loom-generated id like `com_squareup_okhttp3_okhttp-jvm` could make Fabric discard our relocated
        // copy in favour of another mod's non-relocated one (breaking LiquidBounce with NoClassDefFoundError),
        // or hand a consumer a jar with none of the classes it expects. Scope the id to LiquidBounce so the
        // relocated nested jar can't collide.
        private fun rewriteModId(data: ByteArray): ByteArray =
            String(data, Charsets.UTF_8)
                .replace(Regex("(\"id\"\\s*:\\s*\")([^\"]+)(\")")) { m ->
                    m.groupValues[1] + "liquidbounce_" + m.groupValues[2] + m.groupValues[3]
                }
                .toByteArray(Charsets.UTF_8)

        fun processJar(jarBytes: ByteArray, nested: Boolean): ByteArray {
            // Buffer the entries so we can tell whether this jar actually carries relocated classes before
            // deciding to rewrite its nested-jar mod id (nested jars are small; the outer jar fits in memory).
            val entries = ArrayList<Pair<String, ByteArray>>()
            ZipInputStream(ByteArrayInputStream(jarBytes)).use { zin ->
                var e: ZipEntry? = zin.nextEntry
                while (e != null) { entries.add(e.name to zin.readBytes()); e = zin.nextEntry }
            }
            val relocatedHere = entries.any { (n, _) -> n.endsWith(".class") && inPkgSlash(n) }

            val bos = ByteArrayOutputStream()
            ZipOutputStream(bos).use { zout ->
                for ((name, orig) in entries) {
                    var data = orig
                    when {
                        name.endsWith(".class") -> data = remapClass(data)
                        name.endsWith(".jar") -> data = processJar(data, nested = true)
                        nested && relocatedHere && name == "fabric.mod.json" -> data = rewriteModId(data)
                        name.startsWith("META-INF/services/") -> {
                            val c = String(data, Charsets.UTF_8)
                            if (pkgs.any { c.contains(it) })
                                data = serviceContentPkg.replace(c) { pfxDot + it.groupValues[1] }.toByteArray(Charsets.UTF_8)
                        }
                    }
                    zout.putNextEntry(ZipEntry(relocatedName(name)))
                    if (!name.endsWith("/")) zout.write(data)
                    zout.closeEntry()
                }
            }
            return bos.toByteArray()
        }
    }
}
