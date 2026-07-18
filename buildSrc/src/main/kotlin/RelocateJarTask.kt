/*
 * Relocate selected packages inside a built jar (and its nested jar-in-jar entries) into a private
 * prefix, so a host that ships its own copy of those packages on a shared classloader can't shadow
 * LiquidBounce's. This is why it exists: Lunar Client bundles a stripped `okhttp3` (no Kotlin
 * `Headers.Companion`); under Lunar's Ichor classloader that copy wins over LiquidBounce's bundled
 * okhttp 5, so every LiquidBounce okhttp call throws `NoSuchFieldError` and trips the fatal error
 * handler. Relocating okhttp/okio to `net/ccbluex/liquidbounce/libs/...` makes LiquidBounce use its
 * own copy unconditionally, on Lunar or anywhere else, with no effect on normal Fabric/NeoForge.
 */

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

abstract class RelocateJarTask : DefaultTask() {

    /** Jar to rewrite in place (typically the output of `remapJar`). */
    @get:InputFile
    abstract val jar: RegularFileProperty

    /** Top-level package internal names to relocate, e.g. "okhttp3", "okio". */
    @get:Internal
    abstract val packages: ListProperty<String>

    /** Slash-terminated internal-name prefix to prepend, e.g. "net/ccbluex/liquidbounce/libs/". */
    @get:Internal
    var prefixString: String = "net/ccbluex/liquidbounce/libs/"

    private val pkgs get() = packages.get()
    private val pfx get() = prefixString
    private val pfxDot get() = pfx.replace('/', '.')

    private fun inPkgSlash(s: String) = pkgs.any { s == it || s.startsWith("$it/") }

    private inner class Reloc : Remapper() {
        override fun map(internalName: String): String =
            if (inPkgSlash(internalName)) pfx + internalName else internalName

        override fun mapValue(value: Any?): Any? {
            if (value is String) {
                for (p in pkgs) {
                    if (value == p || value.startsWith("$p.")) return pfxDot + value   // Class.forName style
                    if (value.startsWith("$p/")) return pfx + value                     // resource path style
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

    private fun processJar(jarBytes: ByteArray, recurse: Boolean): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipInputStream(ByteArrayInputStream(jarBytes)).use { zin ->
            ZipOutputStream(bos).use { zout ->
                var e: ZipEntry? = zin.nextEntry
                while (e != null) {
                    val name = e.name
                    var data = zin.readBytes()
                    var outName = name
                    when {
                        name.endsWith(".class") -> {
                            data = remapClass(data)
                            if (inPkgSlash(name)) outName = pfx + name
                        }
                        recurse && name.endsWith(".jar") -> data = processJar(data, true)
                        name.startsWith("META-INF/services/") -> {
                            val svc = name.removePrefix("META-INF/services/")
                            if (inPkgSlash(svc)) outName = "META-INF/services/" + pfxDot + svc
                            val c = String(data)
                            if (c.contains("okhttp3") || c.contains("okio"))
                                data = c.replace(Regex("(?m)^(okhttp3|okio)"), "$pfxDot$1").toByteArray()
                        }
                    }
                    if (name.endsWith("/")) {
                        zout.putNextEntry(ZipEntry(outName)); zout.closeEntry()
                    } else {
                        zout.putNextEntry(ZipEntry(outName)); zout.write(data); zout.closeEntry()
                    }
                    e = zin.nextEntry
                }
            }
        }
        return bos.toByteArray()
    }

    @TaskAction
    fun run() {
        val f = jar.get().asFile
        val relocated = processJar(f.readBytes(), recurse = true)
        f.writeBytes(relocated)
        logger.lifecycle("Relocated ${pkgs.joinToString(", ")} -> $pfx in ${f.name}")
    }
}
