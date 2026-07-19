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
 * LiquidBounce <-> Lunar Client compatibility check.
 *
 * Resolves every LiquidBounce mixin's `@Mixin` target + injector selectors (`@Inject`/`@ModifyArg`/`@WrapOperation`/... method
 * selectors and their `@At` INVOKE/FIELD/NEW targets) against the classes Lunar Client actually runs - i.e. Lunar's
 * "baked" Minecraft (vanilla remapped + Lunar's inflight patches + Lunar mixins), extracted from a Genesis bake.
 *
 * Lunar uses the same mojmap-named namespace as LiquidBounce, so selectors resolve directly; a BLOCKER means the
 * target class/method/injection-point does NOT exist in Lunar's runtime, i.e. that mixin would fail to apply on Lunar
 * (InvalidInjectionException / "target not found") and the feature it backs is broken.
 */
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.LocalVariableNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import java.io.File
import java.util.jar.JarFile

abstract class LunarCompatCheckTask : DefaultTask() {

    /**
     * Compiled class tree of the mixins. The whole compile output may be passed; the task filters to the
     * `injection/mixins/` package (every LiquidBounce mixin that applies on the Fabric loader Lunar uses).
     */
    @get:InputFiles
    abstract val mixinClasses: ConfigurableFileCollection

    /** Lunar's baked Minecraft classes jar (produced by `scripts/fetch-and-bake.sh`) to resolve the mixin targets against. */
    @get:Classpath
    abstract val targetClasses: ConfigurableFileCollection

    /** When true, SUSPECT findings also fail the build (default: BLOCKER only). */
    @get:Input
    abstract val strict: Property<Boolean>

    /**
     * Summary report of the check (counts + findings). Declaring it as an output
     * lets Gradle treat the task as UP-TO-DATE when nothing it depends on changed,
     * instead of re-running every build.
     */
    @get:OutputFile
    abstract val report: RegularFileProperty

    init {
        strict.convention(false)
    }

    private enum class Severity { BLOCKER, SUSPECT }

    private class Finding(
        val severity: Severity,
        val mixinClass: String,
        val handler: String,
        val target: String,
        val explanation: String,
    )

    @TaskAction
    fun run() {
        val mixinFiles = mixinClasses.asFileTree.files.filter { file ->
            file.extension == "class" &&
                file.path.replace(File.separatorChar, '/')
                    .contains("net/ccbluex/liquidbounce/injection/mixins/")
        }

        if (mixinFiles.isEmpty()) {
            throw GradleException(
                "No compiled mixins found under injection/mixins/ in " +
                    "${mixinClasses.files}. Did :compileJava / :classes run?"
            )
        }

        if (targetClasses.isEmpty || targetClasses.files.none { it.exists() }) {
            throw GradleException(
                "Lunar baked-classes jar not found (looked at ${targetClasses.files}). " +
                    "Produce it first with lunar-compat/scripts/fetch-and-bake.sh, then pass -PlunarJar=<path>."
            )
        }

        val mixins = mixinFiles.map { file ->
            file.inputStream().use { stream ->
                ClassNode().also { ClassReader(stream).accept(it, ClassReader.SKIP_FRAMES) }
            }
        }.map(::parseMixin)

        // Load the target classes referenced by any mixin. ALL mixins are checked, not only injector-bearing
        // ones: an `@Overwrite`/`@Shadow`/accessor mixin whose target class Lunar removed is a hard load failure
        // too, caught by the target-class existence check in checkMixin.
        val referenced = mixins.flatMap { it.targets }.toSet()
        val targets = loadTargets(referenced)

        val findings = ArrayList<Finding>()
        for (mixin in mixins) {
            checkMixin(mixin, targets, findings)
        }

        // Coverage - so a partial bake (few classes) can't read as "0 broken, passed": count how many of the
        // net/minecraft target classes the mixins reference actually resolved against Lunar's baked jar.
        val injectorMixins = mixins.count { it.injectors.isNotEmpty() }
        val mcTargets = referenced.filter { it.startsWith("net/minecraft/") }
        val mcResolved = mcTargets.count { targets[it] != null }
        val coverage = "Checked ${mixins.size} mixin(s) ($injectorMixins with injectors); " +
            "net.minecraft targets resolved $mcResolved/${mcTargets.size} against Lunar's bake."
        if (mcTargets.isNotEmpty() && mcResolved == 0) {
            // Write the report so the CI grade can tell this deterministic bad-bake abort from a transient
            // build error, and so it is not pointlessly retried.
            report.orNull?.asFile?.let {
                it.parentFile?.mkdirs()
                it.writeText("[ABORTED] $coverage\nEvery net.minecraft target failed to resolve against Lunar's " +
                    "bake (empty/partial/failed bake); the check would pass vacuously, so it was aborted.\n")
            }
            throw GradleException(
                "$coverage\nEvery net.minecraft target failed to resolve - Lunar's baked classes look empty or " +
                    "wrong (a partial/failed bake), so this check would pass vacuously. Aborting.",
            )
        }

        report(findings, coverage)
    }

    // region parsing --------------------------------------------------------

    private class InjectorInfo(
        /** Simple annotation name, e.g. `Inject`, `ModifyReturnValue`. */
        val kind: String,
        val handlerName: String,
        val handlerDesc: String,
        /** `method` selectors, e.g. `["foo", "bar(Lx;)V"]`. */
        val selectors: List<String>,
        /** `@At.target` reference strings gathered from the injector. */
        val atTargets: List<String>,
        /** Handler parameter types (ASM). */
        val paramTypes: List<Type>,
        /**
         * Indices of params that are NOT captured target args (`@Local`/`@Share`/
         * `@Cancellable`): they are removed from the prefix/suffix sequence.
         */
        val sugarParams: Set<Int>,
        /**
         * Indices of `@Coerce` params. Unlike sugar params, these DO occupy a captured
         * target-arg position (they only widen the declared type), so they are kept in
         * the sequence as positional wildcards that match any target type.
         */
        val coerceParams: Set<Int>,
        /** Name-only `@Local` params (no ordinal/index): index -> declared name. */
        val nameOnlyLocals: Map<Int, String>,
        /** `@Inject(require = N)`; null means default. `require = 0` marks an intentionally-optional injection. */
        val requireValue: Int?,
        /** `@Inject(locals = ...)` in a capture mode other than NO_CAPTURE: the handler has trailing captured
         *  locals that this checker does not model, so the captured-arg check is skipped for it. */
        val hasLocalCapture: Boolean,
    )

    private class MixinInfo(
        val name: String,
        /** Internal names of the `@Mixin(value = ...)` class targets. */
        val targets: List<String>,
        val injectors: List<InjectorInfo>,
    )

    private companion object {
        private const val MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;"

        // Injector annotation descriptor -> simple name.
        private val INJECTOR_ANNOTATIONS = mapOf(
            "Lorg/spongepowered/asm/mixin/injection/Inject;" to "Inject",
            "Lorg/spongepowered/asm/mixin/injection/Redirect;" to "Redirect",
            "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;" to "ModifyVariable",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArg;" to "ModifyArg",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;" to "ModifyArgs",
            "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;" to "ModifyConstant",
            "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;" to "ModifyExpressionValue",
            "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;" to "ModifyReturnValue",
            "Lcom/llamalad7/mixinextras/injector/ModifyReceiver;" to "ModifyReceiver",
            "Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;" to "WrapWithCondition",
            "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;" to "WrapOperation",
            "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;" to "WrapMethod",
        )

        // `@Inject` captures target args as a PREFIX and must capture ALL of them or none: a partial prefix
        // (e.g. Lunar appended a parameter to the target method) throws InvalidInjectionException at load.
        private val PREFIX_CAPTURE = setOf("Inject")

        // `@ModifyReturnValue`/`@ModifyExpressionValue` take the modified value as the FIRST param, then
        // capture target args as a PREFIX from index 0 (a partial prefix is allowed). `@ModifyVariable` is
        // deliberately excluded: its capture convention is too fiddly to model reliably and is not the
        // failure mode this check targets.
        private val VALUE_FIRST_CAPTURE = setOf("ModifyReturnValue", "ModifyExpressionValue")

        // Every kind that captures target args (matched as a prefix; `@Inject` additionally all-or-none).
        private val CAPTURE_KINDS = PREFIX_CAPTURE + VALUE_FIRST_CAPTURE

        // Sugar annotations: an annotated param is a captured local (or callback), not a
        // target arg, so it is REMOVED from the prefix/suffix compatibility check.
        // `@Coerce` is deliberately NOT here: it occupies a captured target-arg position
        // and is handled as a positional wildcard (see COERCE_DESC / coerceParams).
        private val SUGAR_ANNOTATIONS = setOf(
            "Lcom/llamalad7/mixinextras/sugar/Local;",
            "Lcom/llamalad7/mixinextras/sugar/Share;",
            "Lcom/llamalad7/mixinextras/sugar/Cancellable;",
        )
        private const val LOCAL_DESC = "Lcom/llamalad7/mixinextras/sugar/Local;"
        private const val COERCE_DESC = "Lorg/spongepowered/asm/mixin/injection/Coerce;"

        // Callback types that Mixin appends/allows outside the captured target args
        // (trailing for `@Inject`, and a `@Cancellable` CallbackInfo tail for MixinExtras).
        private val CALLBACK_TYPES = setOf(
            "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;",
            "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;",
        )
    }

    private fun parseMixin(node: ClassNode): MixinInfo {
        val targets = (node.invisibleAnnotations.orEmpty() + node.visibleAnnotations.orEmpty())
            .firstOrNull { it.desc == MIXIN_DESC }
            ?.let(::readMixinTargets)
            .orEmpty()

        val injectors = node.methods.mapNotNull { method -> parseInjector(method) }

        return MixinInfo(node.name, targets, injectors)
    }

    /** Reads the class targets of a `@Mixin` annotation: `value = {A.class, ...}` (Type) AND the string form
     *  `targets = {"a.b.C$1"}` (fully-qualified names, normalised to internal names - resolvable against Lunar's
     *  baked jar exactly like value targets). Skipping the string form silently drops those mixins from the
     *  check (e.g. an anonymous-class target with a real `@ModifyReceiver` would go unvalidated). */
    private fun readMixinTargets(annotation: AnnotationNode): List<String> {
        val value = annotation.values ?: return emptyList()
        val result = ArrayList<String>()
        var i = 0
        while (i < value.size) {
            val key = value[i] as String
            val v = value[i + 1]
            when (key) {
                // Either a single Type or a List<Type> for value = {A.class, B.class}.
                "value" -> when (v) {
                    is Type -> result += v.internalName
                    is List<*> -> v.filterIsInstance<Type>().mapTo(result) { it.internalName }
                }
                // targets = "a.b.C" or {"a.b.C", ...} - dotted binary names; '.' -> '/' (inner-class '$' is preserved).
                "targets" -> when (v) {
                    is String -> result += v.replace('.', '/')
                    is List<*> -> v.filterIsInstance<String>().mapTo(result) { it.replace('.', '/') }
                }
            }
            i += 2
        }
        return result
    }

    private fun parseInjector(method: MethodNode): InjectorInfo? {
        // Sponge/MixinExtras injector annotations have RUNTIME retention (visible),
        // whereas `@Mixin` itself is CLASS-retention (invisible); check both lists.
        val annotation = (method.visibleAnnotations.orEmpty() + method.invisibleAnnotations.orEmpty())
            .firstOrNull { it.desc in INJECTOR_ANNOTATIONS } ?: return null
        val kind = INJECTOR_ANNOTATIONS.getValue(annotation.desc)

        val selectors = ArrayList<String>()
        val atTargets = ArrayList<String>()

        var requireValue: Int? = null
        var hasLocalCapture = false
        val values = annotation.values.orEmpty()
        var i = 0
        while (i < values.size) {
            val key = values[i] as String
            val v = values[i + 1]
            when (key) {
                "method" -> collectStrings(v, selectors)
                "at" -> collectAtTargets(v, atTargets)
                // slice(from/to) can carry `@At` too, but is rarely the source of a mismatch.
                "slice" -> collectAtTargets(v, atTargets)
                "require" -> requireValue = v as? Int
                // `locals` is an enum ref `[desc, NAME]`; any mode but NO_CAPTURE adds trailing captured locals.
                // If it isn't the expected String[] (never happens for a valid enum value), leave the default.
                "locals" -> hasLocalCapture = (v as? Array<*>)?.getOrNull(1)?.let { it != "NO_CAPTURE" } ?: false
            }
            i += 2
        }

        val paramTypes = Type.getArgumentTypes(method.desc).toList()
        val sugarParams = HashSet<Int>()
        val coerceParams = HashSet<Int>()
        val nameOnlyLocals = HashMap<Int, String>()
        // `@Local` is CLASS-retention (invisible), but merge both lists defensively.
        val paramCount = paramTypes.size
        for (index in 0 until paramCount) {
            val paramAnnotations = method.invisibleParameterAnnotations?.getOrNull(index).orEmpty() +
                method.visibleParameterAnnotations?.getOrNull(index).orEmpty()
            for (paramAnnotation in paramAnnotations) {
                if (paramAnnotation.desc in SUGAR_ANNOTATIONS) {
                    sugarParams += index
                }
                if (paramAnnotation.desc == COERCE_DESC) {
                    coerceParams += index
                }
                if (paramAnnotation.desc == LOCAL_DESC && isNameOnlyLocal(paramAnnotation)) {
                    readLocalName(paramAnnotation)?.let { nameOnlyLocals[index] = it }
                }
            }
        }

        return InjectorInfo(
            kind = kind,
            handlerName = method.name,
            handlerDesc = method.desc,
            selectors = selectors,
            atTargets = atTargets,
            paramTypes = paramTypes,
            sugarParams = sugarParams,
            coerceParams = coerceParams,
            nameOnlyLocals = nameOnlyLocals,
            requireValue = requireValue,
            hasLocalCapture = hasLocalCapture,
        )
    }

    /** True when a `@Local` provides neither `ordinal` nor `index` (name-only resolution). */
    private fun isNameOnlyLocal(annotation: AnnotationNode): Boolean {
        val values = annotation.values ?: return true
        var i = 0
        while (i < values.size) {
            val key = values[i] as String
            if (key == "ordinal" || key == "index") {
                return false
            }
            i += 2
        }
        return true
    }

    private fun readLocalName(annotation: AnnotationNode): String? {
        val values = annotation.values ?: return null
        var i = 0
        while (i < values.size) {
            if (values[i] == "name") {
                return when (val v = values[i + 1]) {
                    is String -> v
                    is List<*> -> v.filterIsInstance<String>().firstOrNull()
                    else -> null
                }
            }
            i += 2
        }
        return null
    }

    private fun collectStrings(value: Any?, into: MutableList<String>) {
        when (value) {
            is String -> into += value
            is List<*> -> value.filterIsInstance<String>().forEach { into += it }
        }
    }

    /** Extracts `target` strings from an `@At` (single or array) or a `@Slice`. */
    private fun collectAtTargets(value: Any?, into: MutableList<String>) {
        when (value) {
            is AnnotationNode -> {
                val vals = value.values.orEmpty()
                var i = 0
                while (i < vals.size) {
                    val key = vals[i] as String
                    val v = vals[i + 1]
                    when (key) {
                        "target" -> collectStrings(v, into)
                        // `@Slice` carries from/to that are themselves `@At` annotations.
                        "from", "to" -> collectAtTargets(v, into)
                    }
                    i += 2
                }
            }
            is List<*> -> value.forEach { collectAtTargets(it, into) }
        }
    }

    // endregion

    // region target loading -------------------------------------------------

    private fun loadTargets(referenced: Set<String>): Map<String, ClassNode> {
        val classes = HashMap<String, ClassNode>()
        for (jar in targetClasses.files) {
            if (!jar.exists()) {
                continue
            }
            JarFile(jar).use { jarFile ->
                for (entry in jarFile.entries()) {
                    if (!entry.name.endsWith(".class")) {
                        continue
                    }
                    val internal = entry.name.removeSuffix(".class")
                    if (internal !in referenced || internal in classes) {
                        continue
                    }
                    jarFile.getInputStream(entry).use { stream ->
                        classes[internal] = ClassNode().also {
                            // Method bodies are needed for the `@At` reference scan, and the
                            // LocalVariableTable (kept by NOT passing SKIP_DEBUG) is needed to
                            // resolve name-only `@Local` params against the target's actual locals.
                            ClassReader(stream).accept(it, ClassReader.SKIP_FRAMES)
                        }
                    }
                }
            }
        }
        return classes
    }

    // endregion

    // region checking -------------------------------------------------------

    private fun checkMixin(mixin: MixinInfo, targets: Map<String, ClassNode>, findings: MutableList<Finding>) {
        // A `net/minecraft/` target absent from Lunar's baked runtime means Lunar removed/renamed that class
        // (or the bake is incomplete) -> the mixin cannot apply. Flag it once instead of silently skipping.
        // Non-minecraft targets (other mods Lunar may not ship) are legitimately absent and skipped below.
        for (targetInternal in mixin.targets.toSet()) {
            if (targets[targetInternal] == null && targetInternal.startsWith("net/minecraft/")) {
                findings += Finding(
                    Severity.BLOCKER, mixin.name, "*", targetInternal,
                    "target class is not present in Lunar's baked runtime (removed/renamed by Lunar, or an " +
                        "incomplete bake) - the mixin cannot apply.",
                )
            }
        }

        for (injector in mixin.injectors) {
            // Resolve every selector against each target class, collecting the matched
            // target methods so the name-only `@Local` check can consult their actual
            // LocalVariableTables (see checkNameOnlyLocals).
            val resolvedTargetMethods = ArrayList<MethodNode>()
            for (selector in injector.selectors) {
                for (targetInternal in mixin.targets) {
                    val targetClass = targets[targetInternal] ?: continue
                    resolvedTargetMethods += checkSelector(
                        mixin, injector, selector, targetInternal, targetClass, findings,
                    )
                }
            }

            checkNameOnlyLocals(mixin, injector, resolvedTargetMethods, findings)
        }
    }

    /**
     * Verifies name-only `@Local(name=X)` params (no ordinal/index) against the actual
     * LocalVariableTable of the resolved target method(s). Mixin binds a name-only `@Local` by
     * matching the target's LVT; where Lunar's bake carries no LVT for a method it surfaces as the
     * no-LVT SUSPECT below, and a name-absent finding is only warranted when the LVT is present.
     */
    private fun checkNameOnlyLocals(
        mixin: MixinInfo,
        injector: InjectorInfo,
        resolvedTargetMethods: List<MethodNode>,
        findings: MutableList<Finding>,
    ) {
        if (injector.nameOnlyLocals.isEmpty() || resolvedTargetMethods.isEmpty()) {
            return
        }

        val where = injector.selectors.joinToString(",")

        for ((index, localName) in injector.nameOnlyLocals) {
            val paramType = injector.paramTypes.getOrNull(index)
            val typeName = paramType?.className ?: "?"

            // Aggregate the outcome across every matched target method. If the name
            // binds in any of them we treat it as OK; findings describe the worst case.
            var boundByName = false
            var anyLvtPresent = false
            var ambiguousByType = false

            for (method in resolvedTargetMethods) {
                val locals: List<LocalVariableNode> = method.localVariables.orEmpty()
                if (locals.isNotEmpty()) {
                    anyLvtPresent = true
                }

                val named = locals.filter { it.name == localName }
                if (named.isNotEmpty()) {
                    // Mixin binds by name first; a name hit is OK regardless of type.
                    boundByName = true
                    break
                }

                // The name is absent here; Mixin would fall back to type. Note whether
                // that fallback is itself ambiguous (>1 local of the requested type).
                if (paramType != null && locals.count { it.desc == paramType.descriptor } > 1) {
                    ambiguousByType = true
                }
            }

            if (boundByName) {
                continue
            }

            findings += when {
                !anyLvtPresent -> Finding(
                    Severity.SUSPECT, mixin.name, injector.handlerName, where,
                    "@Local(name=\"$localName\") param (type $typeName) has no ordinal/index and the " +
                        "target method has no LocalVariableTable; the name cannot bind.",
                )
                ambiguousByType -> Finding(
                    Severity.BLOCKER, mixin.name, injector.handlerName, where,
                    "@Local(name=\"$localName\") param (type $typeName) has no ordinal/index and the " +
                        "name is absent from the target's LVT; the type fallback is ambiguous " +
                        "(>1 local of that type). Add an ordinal/index or fix the name.",
                )
                else -> Finding(
                    Severity.SUSPECT, mixin.name, injector.handlerName, where,
                    "@Local(name=\"$localName\") param (type $typeName) has no ordinal/index and the " +
                        "name is absent from the target's LVT; it can only bind via the type " +
                        "fallback. Add an ordinal/index or fix the name.",
                )
            }
        }
    }

    /** Returns the target methods this selector matched (empty on a zero-match BLOCKER). */
    private fun checkSelector(
        mixin: MixinInfo,
        injector: InjectorInfo,
        selector: String,
        targetInternal: String,
        targetClass: ClassNode,
        findings: MutableList<Finding>,
    ): List<MethodNode> {
        val (name, desc) = splitSelector(selector)
        // Mixin selectors may use `*`/`?` wildcards on the name; match with a glob.
        val isGlob = '*' in name || '?' in name
        val nameRegex = if (isGlob) globToRegex(name) else null
        val matches = targetClass.methods.filter { method ->
            val nameMatch = if (nameRegex != null) nameRegex.matches(method.name) else method.name == name
            nameMatch && (desc == null || method.desc == desc)
        }

        val where = "$targetInternal#$selector"

        // `@Inject(require = 0)` marks an injection the author declared optional: Mixin does not error when it
        // fails to bind, so a non-resolving selector/@At there is a soft signal, not a hard failure. Its binding
        // findings are downgraded from BLOCKER to SUSPECT.
        val bindSev = if (injector.requireValue == 0) Severity.SUSPECT else Severity.BLOCKER

        if (matches.isEmpty()) {
            findings += Finding(
                bindSev, mixin.name, injector.handlerName, where,
                "selector resolves to zero methods in the target class. " +
                    "The vanilla method was renamed/reshaped or removed by Lunar.",
            )
            return emptyList()
        }

        // SUSPECT: a bare-name selector hitting more than one overload may double-inject
        // (e.g. Lunar added an overload that vanilla didn't have). A glob selector
        // hitting many methods is intentional, so it is not flagged here.
        if (desc == null && !isGlob && matches.size > 1) {
            findings += Finding(
                Severity.SUSPECT, mixin.name, injector.handlerName, where,
                "bare-name selector matches ${matches.size} overloads " +
                    "(${matches.joinToString { it.name + it.desc }}); it will inject into all of them. " +
                    "Qualify the selector if only one is intended.",
            )
        }

        // BLOCKER: `@At` member/NEW reference must appear in at least one matched overload's body;
        // SUSPECT if it is present by owner+name but its descriptor (signature) changed on Lunar.
        for (atTarget in injector.atTargets) {
            val ref = parseMemberRef(atTarget) ?: continue
            val ownerNamePresent = matches.any { bodyReferences(it, ref, matchDesc = false) }
            if (!ownerNamePresent) {
                findings += Finding(
                    bindSev, mixin.name, injector.handlerName, where,
                    "@At target \"$atTarget\" references ${ref.owner}#${if (ref.isNew) "<new>" else ref.member}, which " +
                        "does not appear in any matched overload's body. The call/field/NEW site was moved or " +
                        "removed by Lunar.",
                )
            } else if (ref.desc != null && matches.none { bodyReferences(it, ref, matchDesc = true) }) {
                findings += Finding(
                    Severity.SUSPECT, mixin.name, injector.handlerName, where,
                    "@At target \"$atTarget\" is present by name but no matched overload references it with " +
                        "descriptor ${ref.desc}; Lunar may have changed its signature, so the injection point " +
                        "may not bind.",
                )
            }
        }

        // BLOCKER: captured-arg incompatibility (the ModelBlockRenderer failure mode). Checked against EACH
        // overload: if incompatible with ANY, Mixin throws. Skipped for glob selectors (ambiguous overloads)
        // and for handlers with trailing `@Inject(locals = ...)` captured locals, whose captured-arg shape this
        // checker does not model (the local slots follow the target args and are resolved by Mixin at load time).
        if (!isGlob && !injector.hasLocalCapture && injector.kind in CAPTURE_KINDS) {
            val captured = capturedParams(injector)
            if (captured.isNotEmpty()) {
                for (match in matches) {
                    val targetArgs = Type.getArgumentTypes(match.desc).toList()
                    // Captured target args are matched from the front (a prefix). `@Inject` must additionally
                    // capture ALL target args or none - a partial prefix throws InvalidInjectionException.
                    val prefixOk = isPrefix(captured, targetArgs)
                    val allOrNoneOk = injector.kind !in PREFIX_CAPTURE || captured.size == targetArgs.size
                    if (!prefixOk || !allOrNoneOk) {
                        findings += Finding(
                            bindSev, mixin.name, injector.handlerName, where,
                            "@${injector.kind} captured args ${captured.map { it?.className ?: "@Coerce *" }} " +
                                "are not compatible with matched overload ${match.name}${match.desc} " +
                                "(target args ${targetArgs.map { it.className }}). This triggers " +
                                "InvalidInjectionException at load time.",
                        )
                    }
                }
            }
        }

        return matches
    }

    /**
     * The target arguments the handler captures, as an ordered sequence. Sugar/callback
     * params are removed; `@Coerce` params are KEPT but represented as `null` (a
     * positional wildcard that matches any target type, since `@Coerce` only widens the
     * declared type without changing the captured position).
     *
     * - `@Inject`: strip the trailing CallbackInfo/CIR and any sugar params; the rest is
     *   the captured prefix of the target args (all-or-none, enforced by the caller).
     * - `@ModifyReturnValue`/`@ModifyExpressionValue`: the first param is the modified
     *   value; the remaining non-sugar params are the captured prefix of the target args.
     */
    private fun capturedParams(injector: InjectorInfo): List<Type?> {
        val result = ArrayList<Type?>()
        injector.paramTypes.forEachIndexed { index, type ->
            // Drop the leading modified value (`@ModifyReturnValue`/`@ModifyExpressionValue`), sugar/callback
            // params, and any CallbackInfo/CIR - none of these are captured target args.
            val dropped = (injector.kind in VALUE_FIRST_CAPTURE && index == 0) ||
                index in injector.sugarParams ||
                type.descriptor in CALLBACK_TYPES
            if (dropped) {
                return@forEachIndexed
            }
            // `@Coerce` stays in the sequence but as a wildcard (null) that matches any type.
            result += if (index in injector.coerceParams) null else type
        }
        return result
    }

    /** A captured position matches if either side is a `@Coerce` wildcard (null). */
    private fun capturedMatches(captured: Type?, target: Type): Boolean =
        captured == null || captured == target

    private fun isPrefix(captured: List<Type?>, target: List<Type>): Boolean =
        captured.size <= target.size && captured.indices.all { capturedMatches(captured[it], target[it]) }

    private fun report(findings: List<Finding>, coverage: String) {
        val blockers = findings.filter { it.severity == Severity.BLOCKER }
        val suspects = findings.filter { it.severity == Severity.SUSPECT }
        logger.lifecycle(coverage)

        for (finding in findings) {
            val line = "[${finding.severity}] ${finding.mixinClass}#${finding.handler} -> " +
                "${finding.target}: ${finding.explanation}"
            if (finding.severity == Severity.BLOCKER) {
                logger.error(line)
            } else {
                logger.warn(line)
            }
        }

        val summary = "LiquidBounce <-> Lunar compatibility: ${blockers.size} broken mixin(s), ${suspects.size} suspect(s)."

        // Written before any failure is thrown so the report reflects the run either way.
        report.orNull?.asFile?.let { reportFile ->
            reportFile.parentFile?.mkdirs()
            reportFile.writeText(buildString {
                appendLine(summary)
                appendLine(coverage)
                for (finding in findings) {
                    appendLine(
                        "[${finding.severity}] ${finding.mixinClass}#${finding.handler} -> " +
                            "${finding.target}: ${finding.explanation}",
                    )
                }
            })
        }

        if (blockers.isNotEmpty() || (strict.get() && suspects.isNotEmpty())) {
            throw GradleException(
                "$summary\n" +
                    "These LiquidBounce mixins would FAIL to apply on Lunar Client - their target class, method, or " +
                    "injection point does not exist in Lunar's baked runtime (InvalidInjectionException / " +
                    "\"target not found\" at load), so the features they back are broken on Lunar.\n" +
                    (blockers + if (strict.get()) suspects else emptyList()).joinToString("\n") {
                        "  [${it.severity}] ${it.mixinClass}#${it.handler} -> ${it.target}: ${it.explanation}"
                    }
            )
        }
        logger.lifecycle(summary + if (suspects.isNotEmpty()) " (suspects are advisory; passing)" else " Passed.")
    }

    // endregion

    // region selector / reference helpers -----------------------------------

    /**
     * Splits `"name"` or `"name(desc)ret"` into name + optional full descriptor.
     * Handles an optional owner qualifier before the name (`Lowner;name...` or
     * `owner.name...`), without corrupting a descriptor that itself contains `;`.
     */
    private fun splitSelector(selector: String): Pair<String, String?> {
        val s = selector.trim()
        val paren = s.indexOf('(')
        val namePart = if (paren < 0) s else s.substring(0, paren)
        val descPart = if (paren < 0) null else s.substring(paren)

        // The name is everything after an owner qualifier. An owner is written as a
        // field-descriptor prefix `Lpkg/Owner;` or a dotted `pkg.Owner.`; the method
        // name is the segment after the last such separator in the name part only.
        val name = when {
            namePart.contains(';') -> namePart.substringAfterLast(';')
            namePart.contains('.') -> namePart.substringAfterLast('.')
            else -> namePart
        }
        return name to descPart
    }

    /** Converts a Mixin name glob (`*` / `?`) to an anchored regex. */
    private fun globToRegex(glob: String): Regex {
        val pattern = buildString {
            for (ch in glob) {
                when (ch) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(ch.toString()))
                }
            }
        }
        return Regex(pattern)
    }

    private class MemberRef(val owner: String, val member: String, val desc: String?, val isNew: Boolean)

    /**
     * Parses an `@At` target string into an owner + member (+ optional descriptor). Handles:
     *  - method: `Lowner;name(args)ret`   - field: `Lowner;name:fieldDesc`
     *  - NEW:    `(args)Lowner;` (ctor descriptor + constructed type) or a bare `Lowner;` (the type only).
     * The descriptor tail is kept so a signature change on Lunar can be flagged separately.
     */
    private fun parseMemberRef(target: String): MemberRef? {
        val t = target.trim()
        // NEW target written as a constructor descriptor: the constructed type is the return type. Keep the
        // constructor descriptor as `(args)V` (the shape of the synthetic `<init>` call) so a ctor signature
        // change on Lunar surfaces as a descriptor SUSPECT rather than being silently dropped.
        if (t.startsWith("(")) {
            val close = t.indexOf(')')
            if (close < 0) {
                return null
            }
            val owner = t.substring(close + 1).trim().removePrefix("L").removeSuffix(";")
            if (owner.isEmpty()) {
                return null
            }
            val ctorDesc = t.substring(0, close + 1) + "V"
            return MemberRef(owner, "<init>", ctorDesc, isNew = true)
        }
        if (!t.startsWith("L")) {
            return null
        }
        val semi = t.indexOf(';')
        if (semi < 0) {
            return null
        }
        val owner = t.substring(1, semi)
        val rest = t.substring(semi + 1).trim()
        // A bare `Lowner;` (no member) is a NEW target naming the constructed type.
        if (rest.isEmpty()) {
            return MemberRef(owner, "", null, isNew = true)
        }
        val member = rest.substringBefore('(').substringBefore(':').trim()
        if (member.isEmpty()) {
            return MemberRef(owner, "", null, isNew = true)
        }
        val desc = when {
            '(' in rest -> rest.substring(rest.indexOf('('))   // method "(args)ret"
            ':' in rest -> rest.substringAfter(':').trim()      // field descriptor
            else -> null
        }.takeIf { !it.isNullOrEmpty() }
        return MemberRef(owner, member, desc, isNew = false)
    }

    /**
     * True if [method]'s body references [ref]. For a NEW ref, matches the `NEW <owner>` instruction (which
     * carries no descriptor) on the presence pass, and the paired `INVOKESPECIAL <owner>.<init>` on the
     * descriptor pass so a changed constructor signature can be told apart from a removed one. For a
     * method/field ref, matches by owner+name, and additionally by descriptor when [matchDesc] is set (and
     * the ref carries one) - used to tell "reference gone" (owner+name absent) from "signature changed".
     */
    private fun bodyReferences(method: MethodNode, ref: MemberRef, matchDesc: Boolean): Boolean {
        for (insn in method.instructions) {
            val matches = when {
                ref.isNew && insn is TypeInsnNode ->
                    // NEW carries no descriptor, so it can only confirm the type is still constructed here,
                    // not the constructor signature; skip it on the descriptor-strict pass.
                    !matchDesc && insn.opcode == Opcodes.NEW && insn.desc == ref.owner
                ref.isNew && insn is MethodInsnNode ->
                    insn.owner == ref.owner && insn.name == "<init>" &&
                        (!matchDesc || ref.desc == null || insn.desc == ref.desc)
                !ref.isNew && insn is MethodInsnNode ->
                    insn.owner == ref.owner && insn.name == ref.member &&
                        (!matchDesc || ref.desc == null || insn.desc == ref.desc)
                !ref.isNew && insn is FieldInsnNode ->
                    insn.owner == ref.owner && insn.name == ref.member &&
                        (!matchDesc || ref.desc == null || insn.desc == ref.desc)
                else -> false
            }
            if (matches) {
                return true
            }
        }
        return false
    }

    // endregion

}
