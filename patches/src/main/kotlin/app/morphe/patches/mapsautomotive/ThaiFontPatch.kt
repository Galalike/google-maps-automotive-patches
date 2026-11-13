package app.morphe.patches.mapsautomotive

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.logging.Logger

internal const val THAI_FONT_CLASS = "Lapp/morphe/extension/mapsautomotive/patches/ThaiFontPatch;"
private const val THAI_FONT_SOURCE = "morphe-fonts"
private const val THAI_FONT_ASSET_DIR = "assets/morphe_fonts"
private val THAI_FONT_FILES = listOf(
    "noto_sans_thai_regular.ttf",
    "noto_sans_thai_medium.ttf",
    "noto_sans_thai_semibold.ttf",
    "noto_sans_thai_bold.ttf",
)

private val KNOWN_TEXTVIEW_CLASSES = setOf(
    "Landroid/widget/TextView;",
    "Landroid/widget/Button;",
    "Landroid/widget/EditText;",
    "Landroid/widget/CheckedTextView;",
    "Landroid/widget/CompoundButton;",
    "Landroid/widget/CheckBox;",
    "Landroid/widget/RadioButton;",
    "Landroid/widget/Switch;",
    "Landroid/widget/ToggleButton;",
    "Landroid/widget/AutoCompleteTextView;",
    "Landroid/widget/MultiAutoCompleteTextView;",
    "Landroidx/appcompat/widget/AppCompatTextView;",
)

private val KNOWN_PAINT_CLASSES = setOf(
    "Landroid/graphics/Paint;",
    "Landroid/text/TextPaint;",
)

private val thaiFontLogger = Logger.getLogger("app.morphe.patches.mapsautomotive.ThaiFontPatch")

internal fun ResourcePatchContext.addThaiFonts() {
    THAI_FONT_FILES.forEach { fileName ->
        val source = "$THAI_FONT_SOURCE/$fileName"
        val stream = IncludedFeatures::class.java.classLoader.getResourceAsStream(source)
            ?: throw PatchException("Missing bundled font resource $source")
        val target = get("$THAI_FONT_ASSET_DIR/$fileName", false)
        target.parentFile.mkdirs()
        stream.use { Files.copy(it, target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }
    thaiFontLogger.info("Bundled ${THAI_FONT_FILES.size} Noto Sans Thai fonts under $THAI_FONT_ASSET_DIR")
}

internal fun BytecodePatchContext.useThaiFont() {
    val getDefaultRef = hookRef("getDefault", emptyList(), "Landroid/graphics/Typeface;")
    val getBoldRef = hookRef("getBold", emptyList(), "Landroid/graphics/Typeface;")
    val getRegularRef = hookRef("getRegular", emptyList(), "Landroid/graphics/Typeface;")
    val defaultFromStyleRef = hookRef("defaultFromStyle", listOf("I"), "Landroid/graphics/Typeface;")
    val createByNameRef = hookRef("create", listOf("Ljava/lang/String;", "I"), "Landroid/graphics/Typeface;")
    val createByFamilyRef = hookRef("create", listOf("Landroid/graphics/Typeface;", "I"), "Landroid/graphics/Typeface;")
    val createByWeightRef = hookRef("create", listOf("Landroid/graphics/Typeface;", "I", "Z"), "Landroid/graphics/Typeface;")
    val createFromAssetRef = hookRef(
        "createFromAsset",
        listOf("Landroid/content/res/AssetManager;", "Ljava/lang/String;"),
        "Landroid/graphics/Typeface;",
    )
    val setTextViewTypefaceRef = hookRef(
        "setTextViewTypeface",
        listOf("Landroid/widget/TextView;", "Landroid/graphics/Typeface;"),
        "V",
    )
    val setTextViewTypefaceStyleRef = hookRef(
        "setTextViewTypeface",
        listOf("Landroid/widget/TextView;", "Landroid/graphics/Typeface;", "I"),
        "V",
    )
    val setPaintTypefaceRef = hookRef(
        "setPaintTypeface",
        listOf("Landroid/graphics/Paint;", "Landroid/graphics/Typeface;"),
        "Landroid/graphics/Typeface;",
    )

    val requiredMethods = listOf(
        getDefaultRef,
        getBoldRef,
        getRegularRef,
        defaultFromStyleRef,
        createByNameRef,
        createByFamilyRef,
        createByWeightRef,
        createFromAssetRef,
        setTextViewTypefaceRef,
        setTextViewTypefaceStyleRef,
        setPaintTypefaceRef,
    )
    requiredMethods.forEach {
        requireExtensionMethod(THAI_FONT_CLASS, it.name, it.parameterTypes.map { t -> t.toString() }, it.returnType)
    }

    val classMap = mutableMapOf<String, ClassDef>()
    classDefForEach { classMap[it.type] = it }

    val textViewCache = KNOWN_TEXTVIEW_CLASSES.associateWith { true }.toMutableMap()
    val paintCache = KNOWN_PAINT_CLASSES.associateWith { true }.toMutableMap()

    fun inheritsFrom(type: String, cache: MutableMap<String, Boolean>): Boolean {
        cache[type]?.let { return it }
        val visited = mutableListOf<String>()
        var current: String? = type
        var matched = false
        while (current != null && visited.size < 24) {
            val cached = cache[current]
            if (cached != null) {
                matched = cached
                break
            }
            visited += current
            current = classMap[current]?.superclass
        }
        visited.forEach { cache[it] = matched }
        return matched
    }

    var rewrittenCalls = 0
    var rewrittenFields = 0
    val methodsToPatch = mutableListOf<Method>()

    classDefForEach { classDef ->
        if (classDef.type.startsWith("Lapp/morphe/extension/")) return@classDefForEach
        classDef.methods.forEach { method ->
            val instructions = method.implementation?.instructions ?: return@forEach
            val interesting = instructions.any { instr ->
                when (instr.opcode) {
                    Opcode.SGET_OBJECT -> {
                        val field = (instr as ReferenceInstruction).reference as FieldReference
                        field.definingClass == "Landroid/graphics/Typeface;" &&
                            (field.name == "DEFAULT" || field.name == "DEFAULT_BOLD" || field.name == "SANS_SERIF")
                    }
                    Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE -> {
                        val target = (instr as ReferenceInstruction).reference as MethodReference
                        target.definingClass == "Landroid/graphics/Typeface;" &&
                            (target.name == "create" || target.name == "defaultFromStyle" || target.name == "createFromAsset")
                    }
                    Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE,
                    Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE -> {
                        val target = (instr as ReferenceInstruction).reference as MethodReference
                        target.name == "setTypeface"
                    }
                    else -> false
                }
            }
            if (interesting) methodsToPatch += method
        }
    }

    methodsToPatch.forEach { original ->
        val mutable = mutableMethodOf(original)
        val instructions = mutable.implementation!!.instructions
        for (index in instructions.indices.reversed()) {
            val instr = instructions[index]
            when (instr.opcode) {
                Opcode.SGET_OBJECT -> {
                    val field = (instr as ReferenceInstruction).reference as FieldReference
                    if (field.definingClass != "Landroid/graphics/Typeface;") continue
                    val replacementRef = when (field.name) {
                        "DEFAULT" -> getDefaultRef
                        "DEFAULT_BOLD" -> getBoldRef
                        "SANS_SERIF" -> getRegularRef
                        else -> continue
                    }
                    val destReg = (instr as OneRegisterInstruction).registerA
                    mutable.replaceInstruction(index, BuilderInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, replacementRef))
                    mutable.addInstruction(index + 1, BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, destReg))
                    rewrittenFields++
                }
                Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE -> {
                    val target = (instr as ReferenceInstruction).reference as MethodReference
                    val replacementRef = when ("${target.definingClass}->${target.name}(${target.parameterTypes.joinToString("")})${target.returnType}") {
                        "Landroid/graphics/Typeface;->defaultFromStyle(I)Landroid/graphics/Typeface;" -> defaultFromStyleRef
                        "Landroid/graphics/Typeface;->create(Ljava/lang/String;I)Landroid/graphics/Typeface;" -> createByNameRef
                        "Landroid/graphics/Typeface;->create(Landroid/graphics/Typeface;I)Landroid/graphics/Typeface;" -> createByFamilyRef
                        "Landroid/graphics/Typeface;->create(Landroid/graphics/Typeface;IZ)Landroid/graphics/Typeface;" -> createByWeightRef
                        "Landroid/graphics/Typeface;->createFromAsset(Landroid/content/res/AssetManager;Ljava/lang/String;)Landroid/graphics/Typeface;" -> createFromAssetRef
                        else -> continue
                    }
                    mutable.replaceInstruction(index, buildStaticCall(instr, replacementRef))
                    rewrittenCalls++
                }
                Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE,
                Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE -> {
                    val target = (instr as ReferenceInstruction).reference as MethodReference
                    if (target.name != "setTypeface") continue
                    val signature = "(${target.parameterTypes.joinToString("")})${target.returnType}"
                    val replacementRef = when {
                        signature == "(Landroid/graphics/Typeface;)Landroid/graphics/Typeface;" &&
                            inheritsFrom(target.definingClass, paintCache) -> setPaintTypefaceRef
                        signature == "(Landroid/graphics/Typeface;)V" &&
                            inheritsFrom(target.definingClass, textViewCache) -> setTextViewTypefaceRef
                        signature == "(Landroid/graphics/Typeface;I)V" &&
                            inheritsFrom(target.definingClass, textViewCache) -> setTextViewTypefaceStyleRef
                        else -> continue
                    }
                    mutable.replaceInstruction(index, buildStaticCall(instr, replacementRef))
                    rewrittenCalls++
                }
                else -> {}
            }
        }
    }

    thaiFontLogger.info("Noto Sans Thai hooked $rewrittenCalls calls and $rewrittenFields Typeface fields")
}

private fun hookRef(name: String, params: List<String>, returnType: String) =
    ImmutableMethodReference(THAI_FONT_CLASS, name, params, returnType)

private fun buildStaticCall(original: Instruction, target: ImmutableMethodReference): BuilderInstruction =
    when (original) {
        is RegisterRangeInstruction -> BuilderInstruction3rc(
            Opcode.INVOKE_STATIC_RANGE,
            original.startRegister,
            original.registerCount,
            target,
        )
        is FiveRegisterInstruction -> BuilderInstruction35c(
            Opcode.INVOKE_STATIC,
            original.registerCount,
            original.registerC,
            original.registerD,
            original.registerE,
            original.registerF,
            original.registerG,
            target,
        )
        else -> throw PatchException("Unsupported invoke instruction format: ${original.opcode}")
    }
