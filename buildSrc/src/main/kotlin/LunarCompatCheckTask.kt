/*
 * LiquidBounce ⇄ Lunar Client compatibility check.
 *
 * Resolves every LiquidBounce mixin's @Mixin target + injector selectors (@Inject/@ModifyArg/@WrapOperation/... method
 * selectors and their @At INVOKE/FIELD/NEW targets) against the classes Lunar Client actually runs — i.e. Lunar's
 * "baked" Minecraft (vanilla remapped + Lunar's inflight patches + Lunar mixins), extracted from a Genesis bake.
 *
 * Lunar uses the same mojmap-named namespace as LiquidBounce, so selectors resolve directly; a BLOCKER means the
 * target class/method/injection-point does NOT exist in Lunar's runtime, i.e. that mixin would fail to apply on Lunar
 * (InvalidInjectionException / "target not found") and the feature it backs is broken. Reuses the NeoForge divergence
 * checker's proven selector-matching engine, pointed at Lunar's classes instead of the NeoForge-patched jar.
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

    /** Lunar's baked Minecraft classes jar (produced by `scripts/bake-lunar.sh`) to resolve the mixin targets against. */
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
        // Check every LiquidBounce mixin under injection/mixins/ — all of them apply on the Fabric loader Lunar runs.
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
                    "Produce it first with scripts/bake-lunar.sh, then pass -PlunarJar=<path>."
            )
        }

        val mixins = mixinFiles.map { file ->
            file.inputStream().use { stream ->
                ClassNode().also { ClassReader(stream).accept(it, ClassReader.SKIP_FRAMES) }
            }
        }.map(::parseMixin).filter { it.injectors.isNotEmpty() }

        // Only load the target classes actually referenced by a mixin, for speed.
        val referenced = mixins.flatMap { it.targets }.toSet()
        val targets = loadTargets(referenced)

        val findings = ArrayList<Finding>()
        for (mixin in mixins) {
            checkMixin(mixin, targets, findings)
        }

        report(findings)
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
    )

    private class MixinInfo(
        val name: String,
        /** Internal names of the `@Mixin(value = …)` class targets. */
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

        // Injectors whose leading params must be a PREFIX of the target args.
        private val PREFIX_CAPTURE = setOf("Inject")

        // Injectors whose trailing params (after the modified value) must be a
        // SUFFIX of the target args. @ModifyVariable is deliberately excluded: its
        // capture convention (argsOnly/ordinal/index/prefix semantics) is too fiddly
        // to model reliably, and it is not the failure mode this check targets.
        private val SUFFIX_CAPTURE = setOf("ModifyReturnValue", "ModifyExpressionValue")

        // Sugar annotations: an annotated param is a captured local (or callback), not a
        // target arg, so it is REMOVED from the prefix/suffix compatibility check.
        // @Coerce is deliberately NOT here: it occupies a captured target-arg position
        // and is handled as a positional wildcard (see COERCE_DESC / coerceParams).
        private val SUGAR_ANNOTATIONS = setOf(
            "Lcom/llamalad7/mixinextras/sugar/Local;",
            "Lcom/llamalad7/mixinextras/sugar/Share;",
            "Lcom/llamalad7/mixinextras/sugar/Cancellable;",
        )
        private const val LOCAL_DESC = "Lcom/llamalad7/mixinextras/sugar/Local;"
        private const val COERCE_DESC = "Lorg/spongepowered/asm/mixin/injection/Coerce;"

        // Callback types that Mixin appends/allows outside the captured target args
        // (trailing for @Inject, and a @Cancellable CallbackInfo tail for MixinExtras).
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

    /** Reads the class targets of a `@Mixin` annotation: `value = {A.class, …}` (Type) AND the string form
     *  `targets = {"a.b.C$1"}` (fully-qualified names, normalised to internal names — resolvable against the patched
     *  jar exactly like value targets). Skipping the string form silently drops those mixins from the divergence
     *  check (e.g. an anonymous-class target with a real @ModifyReceiver would go unvalidated). */
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
                // targets = "a.b.C" or {"a.b.C", …} — dotted binary names; '.' -> '/' (inner-class '$' is preserved).
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
        // whereas @Mixin itself is CLASS-retention (invisible); check both lists.
        val annotation = (method.visibleAnnotations.orEmpty() + method.invisibleAnnotations.orEmpty())
            .firstOrNull { it.desc in INJECTOR_ANNOTATIONS } ?: return null
        val kind = INJECTOR_ANNOTATIONS.getValue(annotation.desc)

        val selectors = ArrayList<String>()
        val atTargets = ArrayList<String>()

        val values = annotation.values.orEmpty()
        var i = 0
        while (i < values.size) {
            val key = values[i] as String
            val v = values[i + 1]
            when (key) {
                "method" -> collectStrings(v, selectors)
                "at" -> collectAtTargets(v, atTargets)
                // slice(from/to) can carry @At too, but is rarely the divergence source.
                "slice" -> collectAtTargets(v, atTargets)
            }
            i += 2
        }

        val paramTypes = Type.getArgumentTypes(method.desc).toList()
        val sugarParams = HashSet<Int>()
        val coerceParams = HashSet<Int>()
        val nameOnlyLocals = HashMap<Int, String>()
        // @Local is CLASS-retention (invisible), but merge both lists defensively.
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
                        // @Slice carries from/to that are themselves @At annotations.
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
                            // Method bodies are needed for the @At reference scan, and the
                            // LocalVariableTable (kept by NOT passing SKIP_DEBUG) is needed to
                            // resolve name-only @Local params against the target's actual locals.
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
        for (injector in mixin.injectors) {
            // Resolve every selector against each target class, collecting the matched
            // target methods so the name-only @Local check can consult their actual
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
     * LocalVariableTable of the resolved target method(s). The NeoForge-patched jar
     * RETAINS local names, so Mixin binds by name first; a finding is only warranted
     * when that name is genuinely absent from the target's LVT.
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
                        "patched target method has no LocalVariableTable; the name cannot bind.",
                )
                ambiguousByType -> Finding(
                    Severity.BLOCKER, mixin.name, injector.handlerName, where,
                    "@Local(name=\"$localName\") param (type $typeName) has no ordinal/index and the " +
                        "name is absent from the patched target's LVT; the type fallback is ambiguous " +
                        "(>1 local of that type). Add an ordinal/index or fix the name.",
                )
                else -> Finding(
                    Severity.SUSPECT, mixin.name, injector.handlerName, where,
                    "@Local(name=\"$localName\") param (type $typeName) has no ordinal/index and the " +
                        "name is absent from the patched target's LVT; it can only bind via the type " +
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

        if (matches.isEmpty()) {
            findings += Finding(
                Severity.BLOCKER, mixin.name, injector.handlerName, where,
                "selector resolves to zero methods in the patched target class. " +
                    "The vanilla method was renamed/reshaped or removed by NeoForge.",
            )
            return emptyList()
        }

        // SUSPECT: a bare-name selector hitting more than one overload may double-inject
        // (e.g. NeoForge added an overload that vanilla didn't have). A glob selector
        // hitting many methods is intentional, so it is not flagged here.
        if (desc == null && !isGlob && matches.size > 1) {
            findings += Finding(
                Severity.SUSPECT, mixin.name, injector.handlerName, where,
                "bare-name selector matches ${matches.size} overloads " +
                    "(${matches.joinToString { it.name + it.desc }}); it will inject into all of them. " +
                    "Qualify the selector if only one is intended.",
            )
        }

        // BLOCKER: @At member reference must exist in the body of at least one match.
        for (atTarget in injector.atTargets) {
            val ref = parseMemberRef(atTarget) ?: continue
            val present = matches.any { method -> bodyReferences(method, ref) }
            if (!present) {
                findings += Finding(
                    Severity.BLOCKER, mixin.name, injector.handlerName, where,
                    "@At target \"$atTarget\" references ${ref.owner}#${ref.member}, which does not " +
                        "appear in any matched overload's body. The call/field site was moved or " +
                        "removed by NeoForge.",
                )
            }
        }

        // BLOCKER: captured-arg incompatibility (the ModelBlockRenderer failure mode).
        // Checked against EACH overload: if incompatible with ANY, Mixin throws.
        // Skipped for glob selectors, where the intended overload set is ambiguous.
        if (!isGlob && (injector.kind in PREFIX_CAPTURE || injector.kind in SUFFIX_CAPTURE)) {
            val captured = capturedParams(injector)
            for (match in matches) {
                val targetArgs = Type.getArgumentTypes(match.desc).toList()
                val compatible = if (injector.kind in PREFIX_CAPTURE) {
                    isPrefix(captured, targetArgs)
                } else {
                    isSuffix(captured, targetArgs)
                }
                if (!compatible) {
                    val relation = if (injector.kind in PREFIX_CAPTURE) "prefix" else "suffix"
                    findings += Finding(
                        Severity.BLOCKER, mixin.name, injector.handlerName, where,
                        "@${injector.kind} captured args ${captured.map { it?.className ?: "@Coerce *" }} " +
                            "are not a $relation of matched overload ${match.name}${match.desc} " +
                            "(target args ${targetArgs.map { it.className }}). This is what triggers " +
                            "InvalidInjectionException at load time.",
                    )
                }
            }
        }

        return matches
    }

    /**
     * The target arguments the handler captures, as an ordered sequence. Sugar/callback
     * params are removed; `@Coerce` params are KEPT but represented as `null` (a
     * positional wildcard that matches any target type, since @Coerce only widens the
     * declared type without changing the captured position).
     *
     * - `@Inject`: strip the trailing CallbackInfo/CIR and any sugar params; the rest is
     *   the captured PREFIX of the target args.
     * - `@ModifyReturnValue`/`@ModifyExpressionValue`: the first param is the modified
     *   value; the remaining non-sugar params are the captured SUFFIX.
     */
    private fun capturedParams(injector: InjectorInfo): List<Type?> {
        val result = ArrayList<Type?>()
        injector.paramTypes.forEachIndexed { index, type ->
            // Drop the leading modified value (@Modify*), sugar/callback params, and any
            // CallbackInfo/CIR - none of these are captured target args.
            val dropped = (injector.kind in SUFFIX_CAPTURE && index == 0) ||
                index in injector.sugarParams ||
                type.descriptor in CALLBACK_TYPES
            if (dropped) {
                return@forEachIndexed
            }
            // @Coerce stays in the sequence but as a wildcard (null) that matches any type.
            result += if (index in injector.coerceParams) null else type
        }
        return result
    }

    /** A captured position matches if either side is a `@Coerce` wildcard (null). */
    private fun capturedMatches(captured: Type?, target: Type): Boolean =
        captured == null || captured == target

    private fun isPrefix(captured: List<Type?>, target: List<Type>): Boolean =
        captured.size <= target.size && captured.indices.all { capturedMatches(captured[it], target[it]) }

    private fun isSuffix(captured: List<Type?>, target: List<Type>): Boolean {
        if (captured.size > target.size) {
            return false
        }
        val offset = target.size - captured.size
        return captured.indices.all { capturedMatches(captured[it], target[offset + it]) }
    }

    private fun report(findings: List<Finding>) {
        val blockers = findings.filter { it.severity == Severity.BLOCKER }
        val suspects = findings.filter { it.severity == Severity.SUSPECT }

        for (finding in findings) {
            val line = "[${finding.severity}] ${finding.mixinClass}#${finding.handler} -> " +
                "${finding.target}: ${finding.explanation}"
            if (finding.severity == Severity.BLOCKER) {
                logger.error(line)
            } else {
                logger.warn(line)
            }
        }

        val summary = "LiquidBounce ⇄ Lunar compatibility: ${blockers.size} broken mixin(s), ${suspects.size} suspect(s)."

        // Write the summary report (declared @OutputFile) so Gradle can mark the task
        // UP-TO-DATE when nothing changed. Written before any failure is thrown so the
        // report reflects the run either way.
        report.orNull?.asFile?.let { reportFile ->
            reportFile.parentFile?.mkdirs()
            reportFile.writeText(buildString {
                appendLine(summary)
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
                    "These LiquidBounce mixins would FAIL to apply on Lunar Client — their target class, method, or " +
                    "injection point does not exist in Lunar's baked 26.2 runtime (InvalidInjectionException / " +
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
     * Handles an optional owner qualifier before the name (`Lowner;name…` or
     * `owner.name…`), without corrupting a descriptor that itself contains `;`.
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

    private class MemberRef(val owner: String, val member: String)

    /**
     * Parses an `@At` target string like `Lowner;name(desc)ret` or `Lowner;field:desc`
     * into owner + member name. The descriptor tail is intentionally ignored to keep
     * matching robust across trivial descriptor churn.
     */
    private fun parseMemberRef(target: String): MemberRef? {
        val t = target.trim()
        if (!t.startsWith("L")) {
            return null
        }
        val semi = t.indexOf(';')
        if (semi < 0) {
            return null
        }
        val owner = t.substring(1, semi)
        val rest = t.substring(semi + 1)
        val member = rest.substringBefore('(').substringBefore(':').trim()
        if (member.isEmpty()) {
            return null
        }
        return MemberRef(owner, member)
    }

    /** True if [method]'s body contains a method/field/NEW insn matching [ref] by owner+name. */
    private fun bodyReferences(method: MethodNode, ref: MemberRef): Boolean {
        for (insn in method.instructions) {
            val matches = when (insn) {
                is MethodInsnNode -> insn.owner == ref.owner && insn.name == ref.member
                is FieldInsnNode -> insn.owner == ref.owner && insn.name == ref.member
                // NEW targets carry only the owner type; the member is the type name.
                is TypeInsnNode -> insn.desc == ref.owner || insn.desc == ref.member
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
