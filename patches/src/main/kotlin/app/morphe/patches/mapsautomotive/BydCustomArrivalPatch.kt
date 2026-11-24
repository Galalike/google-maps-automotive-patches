package app.morphe.patches.mapsautomotive

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import java.util.logging.Logger

private const val CUSTOM_ARRIVAL_CLASS =
    "Lapp/morphe/extension/mapsautomotive/patches/BydCustomArrival;"
private val customArrivalLogger =
    Logger.getLogger("app.morphe.patches.mapsautomotive.BydCustomArrivalPatch")

private fun Method.containsString(value: String): Boolean =
    implementation?.instructions?.any {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == value
    } == true

private fun BytecodePatchContext.findClassByToStringPrefix(prefix: String): ClassDef {
    val matches = mutableListOf<ClassDef>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith("Lapp/morphe/extension/")) return@classDefForEach
        val toString = classDef.methods.firstOrNull {
            it.name == "toString" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;"
        } ?: return@classDefForEach
        if (toString.containsString(prefix)) {
            matches += classDef
        }
    }
    return matches.singleOrNull()
        ?: throw PatchException("Expected 1 class with toString prefix '$prefix', found ${matches.map { it.type }}")
}

internal fun BytecodePatchContext.useCustomArrivalModel() {
    requireExtensionMethod(CUSTOM_ARRIVAL_CLASS, "enterRouteContext", listOf("Ljava/lang/Object;"), "V")
    requireExtensionMethod(CUSTOM_ARRIVAL_CLASS, "exitRouteContext", emptyList(), "V")
    requireExtensionMethod(CUSTOM_ARRIVAL_CLASS, "applyCustomModel", listOf("Ljava/lang/Object;"), "V")
    requireExtensionMethod(
        CUSTOM_ARRIVAL_CLASS,
        "formatArrivalText",
        listOf("Ljava/lang/Object;", "Ljava/lang/String;"),
        "Ljava/lang/String;",
    )

    val qusClass = findClassByToStringPrefix("BatteryLevel(batteryLevelWh=")
    val qutClass = findClassByToStringPrefix("BatteryOnArrival(batteryLevel=")

    val quvMethods = mutableListOf<Method>()
    var quvClass: ClassDef? = null
    classDefForEach { classDef ->
        if (classDef.type.startsWith("Lapp/morphe/extension/")) return@classDefForEach
        val hasAnchor = classDef.methods.any {
            it.containsString("Invalid number for destinationIndex: %d, needs to be [0, %d).")
        }
        if (hasAnchor) {
            quvClass = classDef
            quvMethods += classDef.methods.filter { m ->
                !AccessFlags.STATIC.isSet(m.accessFlags) &&
                    m.returnType == qutClass.type &&
                    (m.parameterTypes.size == 3 || m.parameterTypes.size == 4) &&
                    m.parameterTypes.lastOrNull()?.toString() == "Ljava/lang/Integer;"
            }
        }
    }
    if (quvClass == null || quvMethods.size != 2) {
        throw PatchException("Expected quv class with 2 route BatteryOnArrival builders, found ${quvMethods.size}")
    }

    // 1. Hook quv.b and quv.c entry/exit to provide route distance (allv) in ThreadLocal
    for (method in quvMethods) {
        val mutable = mutableMethodOf(method)
        val impl = mutable.implementation
            ?: throw PatchException("Missing implementation for $method")
        val p1Reg = impl.registerCount - method.parameterTypes.sumOf {
            if (it.toString() == "J" || it.toString() == "D") 2 else 1
        }
        val returnIndices = impl.instructions.indices.filter {
            impl.instructions[it].opcode == Opcode.RETURN_OBJECT
        }
        if (returnIndices.isEmpty()) throw PatchException("No return-object in $method")

        // Insert bottom-up so indices remain valid
        for (retIdx in returnIndices.sortedDescending()) {
            mutable.addInstructions(
                retIdx,
                "invoke-static {}, $CUSTOM_ARRIVAL_CLASS->exitRouteContext()V",
            )
        }
        mutable.addInstructions(
            0,
            "invoke-static/range { v$p1Reg .. v$p1Reg }, $CUSTOM_ARRIVAL_CLASS->enterRouteContext(Ljava/lang/Object;)V",
        )
    }

    // 2. Hook rmq.v(int, int, aege) -> qus right before return-object
    val qusBuilders = mutableListOf<Method>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith("Lapp/morphe/extension/")) return@classDefForEach
        classDef.methods.forEach { m ->
            if (AccessFlags.STATIC.isSet(m.accessFlags) &&
                m.returnType == qusClass.type &&
                m.parameterTypes.size == 3 &&
                m.parameterTypes[0].toString() == "I" &&
                m.parameterTypes[1].toString() == "I"
            ) {
                qusBuilders += m
            }
        }
    }
    val qusBuilder = qusBuilders.singleOrNull()
        ?: throw PatchException("Expected 1 static BatteryLevel builder (I, I, *) -> ${qusClass.type}, found ${qusBuilders.size}")

    run {
        val mutable = mutableMethodOf(qusBuilder)
        val impl = mutable.implementation
            ?: throw PatchException("Missing implementation for $qusBuilder")
        val returnIndices = impl.instructions.indices.filter {
            impl.instructions[it].opcode == Opcode.RETURN_OBJECT
        }
        if (returnIndices.isEmpty()) throw PatchException("No return-object in $qusBuilder")
        for (retIdx in returnIndices.sortedDescending()) {
            val retReg = (impl.instructions[retIdx] as OneRegisterInstruction).registerA
            mutable.addInstructions(
                retIdx,
                "invoke-static/range { v$retReg .. v$retReg }, $CUSTOM_ARRIVAL_CLASS->applyCustomModel(Ljava/lang/Object;)V",
            )
        }
    }

    // 3. Hook qut.a() -> String right before return-object to display "<pct>% · วิ่งได้อีก <km> กม."
    val qusField = qutClass.fields.singleOrNull { it.type == qusClass.type }
        ?: throw PatchException("Expected 1 ${qusClass.type} field in ${qutClass.type}")
    val formatMethod = qutClass.methods.singleOrNull {
        !AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.isEmpty() &&
            it.returnType == "Ljava/lang/String;" &&
            it.name != "toString"
    } ?: throw PatchException("Expected 1 text formatter method in ${qutClass.type}")

    run {
        val origImpl = formatMethod.implementation
            ?: throw PatchException("Missing implementation for $formatMethod")
        val origRegCount = origImpl.registerCount
        val newRegCount = origRegCount + 2
        if (newRegCount > 15) {
            throw PatchException("Unexpected register count in $formatMethod: $origRegCount")
        }

        val expandedMethod = MutableMethod(
            ImmutableMethod(
                formatMethod.definingClass,
                formatMethod.name,
                formatMethod.parameters,
                formatMethod.returnType,
                formatMethod.accessFlags,
                formatMethod.annotations,
                formatMethod.hiddenApiRestrictions,
                ImmutableMethodImplementation(
                    newRegCount,
                    origImpl.instructions,
                    origImpl.tryBlocks,
                    origImpl.debugItems,
                ),
            ),
        )
        val mutableQut = mutableClassDefBy(qutClass.type)
        mutableQut.methods.removeIf {
            it.name == formatMethod.name &&
                it.parameterTypes == formatMethod.parameterTypes &&
                it.returnType == formatMethod.returnType
        }
        mutableQut.methods.add(expandedMethod)

        val oldThisReg = origRegCount - 1
        val newThisReg = newRegCount - 1
        val scratchReg = origRegCount

        val impl = expandedMethod.implementation
            ?: throw PatchException("Missing expanded implementation for $formatMethod")
        val returnIndices = impl.instructions.indices.filter {
            impl.instructions[it].opcode == Opcode.RETURN_OBJECT
        }
        if (returnIndices.isEmpty()) throw PatchException("No return-object in $formatMethod")
        for (retIdx in returnIndices.sortedDescending()) {
            val retReg = (impl.instructions[retIdx] as OneRegisterInstruction).registerA
            expandedMethod.addInstructions(
                retIdx,
                """
                    iget-object v$scratchReg, v$newThisReg, ${qutClass.type}->${qusField.name}:${qusField.type}
                    invoke-static { v$scratchReg, v$retReg }, $CUSTOM_ARRIVAL_CLASS->formatArrivalText(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v$retReg
                """.trimIndent(),
            )
        }
        expandedMethod.addInstructions(
            0,
            "move-object v$oldThisReg, v$newThisReg",
        )
    }

    customArrivalLogger.info(
        "Custom arrival model: hooked ${quvMethods.joinToString()}, $qusBuilder, and $formatMethod",
    )
}
