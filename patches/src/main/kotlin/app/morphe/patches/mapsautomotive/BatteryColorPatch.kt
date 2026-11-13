package app.morphe.patches.mapsautomotive

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resource.resourceId
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10t
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21s
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction22t
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction31i
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import java.util.logging.Logger

// Maps' EV battery icon helper picks a battery drawable by SoC percentage and applies a two-tier
// tint (warning tint when <= 10%, default tint otherwise). This patch changes the tint rule to
// three colors resolved from Maps' own palette resources:
//   <= 20%: red   (gm_ref_palette_red500)
//   21..49%: yellow (mod_google_dark_yellow600)
//   >= 50%: green (geo_sys_color_positive)
// Required: a missing resource, missing/ambiguous method anchor, or unknown instruction shape
// fails the patch.

private const val RED_THRESHOLD = 20
private const val GREEN_THRESHOLD = 50
private const val ORIGINAL_LOW_THRESHOLD = 10

private const val RAW_BATTERY_LOW = "ic_battery_0_36dp"
private const val COLOR_RED_NAME = "gm_ref_palette_red500"
private const val COLOR_YELLOW_NAME = "mod_google_dark_yellow600"
private const val COLOR_GREEN_NAME = "geo_sys_color_positive"
private const val LRU_CACHE_TYPE = "Landroid/util/LruCache;"

private val batteryColorLogger = Logger.getLogger("app.morphe.patches.mapsautomotive.BatteryColorPatch")

private data class BatteryColorTarget(
    val method: Method,
    val tintPatternIndex: Int,
    val rTemp: Int,
    val rPercent: Int,
    val rTint: Int,
    val colorLookupMethod: ImmutableMethodReference,
)

@Suppress("unused")
val batteryColorPatch = bytecodePatch(
    name = "EV battery icon colors",
    description = "Colors the EV arrival/charge battery icon by percentage: green (>= 50%), yellow (21-49%), red (<= 20%).",
    default = true,
) {
    compatibleWith(compatibility)
    dependsOn(automotiveMicroGPatch)

    execute {
        applyBatteryColors()
        IncludedFeatures.features += "battery_color"
    }
}

private fun BytecodePatchContext.applyBatteryColors() {
    val lowIconId = requireResourceId(ResourceType.RAW, RAW_BATTERY_LOW)
    val redColorId = requireResourceId(ResourceType.COLOR, COLOR_RED_NAME).toInt()
    val yellowColorId = requireResourceId(ResourceType.COLOR, COLOR_YELLOW_NAME).toInt()
    val greenColorId = requireResourceId(ResourceType.COLOR, COLOR_GREEN_NAME).toInt()

    val target = findBatteryColorTarget(lowIconId)
    val impl = mutableMethodOf(target.method).implementation as? MutableMethodImplementation
        ?: throw PatchException("Battery icon method ${target.method} has no mutable implementation")

    val idx = target.tintPatternIndex
    val rTemp = target.rTemp
    val rPercent = target.rPercent
    val rTint = target.rTint

    // Replace idx in-place so all incoming goto branches from the icon-level switch stay intact.
    impl.replaceInstruction(idx, BuilderInstruction21s(Opcode.CONST_16, rTemp, RED_THRESHOLD))

    val tailIdx = idx + 3
    impl.addInstruction(
        tailIdx,
        BuilderInstruction35c(Opcode.INVOKE_STATIC, 1, rTint, 0, 0, 0, 0, target.colorLookupMethod),
    )
    val lblSetTint = impl.newLabelForIndex(tailIdx)
    impl.addInstruction(tailIdx + 1, BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, rTint))

    impl.addInstruction(tailIdx, BuilderInstruction31i(Opcode.CONST, rTint, yellowColorId))
    val lblYellow = impl.newLabelForIndex(tailIdx)

    impl.addInstruction(tailIdx, BuilderInstruction31i(Opcode.CONST, rTint, greenColorId))
    impl.addInstruction(tailIdx + 1, BuilderInstruction10t(Opcode.GOTO, lblSetTint))

    impl.addInstruction(tailIdx, BuilderInstruction21s(Opcode.CONST_16, rTemp, GREEN_THRESHOLD))
    val lblYellowOrGreen = impl.newLabelForIndex(tailIdx)
    impl.addInstruction(tailIdx + 1, BuilderInstruction22t(Opcode.IF_LT, rPercent, rTemp, lblYellow))

    impl.replaceInstruction(idx + 1, BuilderInstruction22t(Opcode.IF_GT, rPercent, rTemp, lblYellowOrGreen))
    impl.replaceInstruction(idx + 2, BuilderInstruction31i(Opcode.CONST, rTint, redColorId))
    impl.addInstruction(idx + 3, BuilderInstruction10t(Opcode.GOTO, lblSetTint))

    batteryColorLogger.info(
        "Battery color: patched ${target.method.definingClass}->${target.method.name} " +
            "via ${target.colorLookupMethod.definingClass}->${target.colorLookupMethod.name}",
    )
}

private fun BytecodePatchContext.requireResourceId(type: ResourceType, name: String): Long {
    val id = resourceId(type, name)
    if (id <= 0L) throw PatchException("Missing resource ${type.name.lowercase()}/$name")
    return id
}

private fun BytecodePatchContext.findBatteryColorTarget(lowIconId: Long): BatteryColorTarget {
    val matches = mutableListOf<BatteryColorTarget>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith("Lapp/morphe/extension/")) return@classDefForEach
        for (method in classDef.methods) {
            if (!isCandidateSignature(method)) continue
            val instructions = method.implementation?.instructions?.toList() ?: continue
            val hasLowIcon = instructions.any {
                (it as? NarrowLiteralInstruction)?.narrowLiteral?.toLong() == lowIconId
            }
            if (!hasLowIcon) continue

            val patternIdx = findTwoTierTintIndex(instructions) ?: continue
            val const16 = instructions[patternIdx] as OneRegisterInstruction
            val ifLe = instructions[patternIdx + 1] as TwoRegisterInstruction
            val moveObj = instructions[patternIdx + 2] as TwoRegisterInstruction
            val rTemp = const16.registerA
            val rPercent = ifLe.registerA
            val rTint = moveObj.registerA
            if (rTemp !in 0..15 || rPercent !in 0..15 || rTint !in 0..15) continue

            val tintType = method.parameterTypes[3].toString()
            val lookupMethod = resolveColorLookupMethod(instructions, tintType) ?: continue
            matches += BatteryColorTarget(method, patternIdx, rTemp, rPercent, rTint, lookupMethod)
        }
    }
    return matches.singleOrNull()
        ?: throw PatchException("Expected 1 battery icon tint method, found ${matches.size}")
}

private fun isCandidateSignature(method: Method): Boolean {
    if (!AccessFlags.PUBLIC.isSet(method.accessFlags) || !AccessFlags.STATIC.isSet(method.accessFlags)) return false
    if (method.returnType == "V") return false
    val params = method.parameterTypes
    return params.size == 5 &&
        params[0].toString() == "I" &&
        params[1].toString() != "I" &&
        params[1] == params[2] &&
        params[3].toString() != "I" &&
        params[3] == params[4]
}

private fun findTwoTierTintIndex(instructions: List<com.android.tools.smali.dexlib2.iface.instruction.Instruction>): Int? {
    val hits = (0 until instructions.size - 2).filter { i ->
        val c = instructions[i]
        val branch = instructions[i + 1]
        val move = instructions[i + 2]
        c.opcode == Opcode.CONST_16 &&
            (c as NarrowLiteralInstruction).narrowLiteral == ORIGINAL_LOW_THRESHOLD &&
            branch.opcode == Opcode.IF_LE &&
            (branch as TwoRegisterInstruction).registerB == (c as OneRegisterInstruction).registerA &&
            move.opcode == Opcode.MOVE_OBJECT &&
            (move as TwoRegisterInstruction).registerA != move.registerB
    }
    return hits.singleOrNull()
}

private fun BytecodePatchContext.resolveColorLookupMethod(
    instructions: List<com.android.tools.smali.dexlib2.iface.instruction.Instruction>,
    tintType: String,
): ImmutableMethodReference? {
    val cacheOwnerTypes = instructions.mapNotNullTo(LinkedHashSet()) { ins ->
        if (ins.opcode != Opcode.SGET_OBJECT) return@mapNotNullTo null
        val field = (ins as? ReferenceInstruction)?.reference as? FieldReference ?: return@mapNotNullTo null
        if (field.type == LRU_CACHE_TYPE) field.definingClass else null
    }
    val candidates = cacheOwnerTypes.flatMap { ownerType ->
        val ownerClass = classDefByOrNull(ownerType) ?: return@flatMap emptyList()
        ownerClass.methods.filter { m ->
            AccessFlags.PUBLIC.isSet(m.accessFlags) &&
                AccessFlags.STATIC.isSet(m.accessFlags) &&
                m.returnType == tintType &&
                m.parameterTypes.size == 1 &&
                m.parameterTypes[0].toString() == "I"
        }
    }
    val chosen = candidates.singleOrNull() ?: return null
    return ImmutableMethodReference(chosen.definingClass, chosen.name, listOf("I"), chosen.returnType)
}
