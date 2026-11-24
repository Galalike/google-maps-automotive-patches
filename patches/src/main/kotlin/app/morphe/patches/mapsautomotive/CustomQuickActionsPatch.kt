package app.morphe.patches.mapsautomotive

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.util.logging.Logger

internal const val CUSTOM_QUICK_ACTIONS_CLASS =
    "Lapp/morphe/extension/mapsautomotive/patches/CustomQuickActions;"

private const val AUTO_START_COUNTDOWN_SECONDS = 3

private val quickActionsLogger = Logger.getLogger("app.morphe.patches.mapsautomotive.CustomQuickActionsPatch")

internal fun BytecodePatchContext.useCustomQuickActions() {
    hookSearchCardRegistration()
    hookAllCategoriesGasStation()
    hookQuickBarCategories()
    hookQuickIconClick()
    shortenAutoStartCountdown()
}

private fun BytecodePatchContext.hookSearchCardRegistration() {
    val irgClass = classDefByOrNull("Lirg;")
        ?: throw PatchException("Missing Lirg; search card class")
    val initMethod = irgClass.methods.firstOrNull { it.name == "<init>" }
        ?: throw PatchException("Missing Lirg;-><init>")

    val mutable = mutableClassDefBy(irgClass.type).methods.first {
        it.name == initMethod.name && it.parameterTypes == initMethod.parameterTypes
    }
    val insns = mutable.implementation?.instructions
        ?: throw PatchException("Lirg;-><init> has no implementation")

    // Capture Context right after `iput-object v14, v0, Lirg;->a:Landroid/content/Context;`
    val ctxPutIdx = insns.indexOfFirst { insn ->
        insn.opcode == Opcode.IPUT_OBJECT &&
            (insn as? ReferenceInstruction)?.reference?.let { it is FieldReference && it.definingClass == "Lirg;" && it.name == "a" } == true
    }
    if (ctxPutIdx < 0) throw PatchException("Could not find Lirg;->a assignment in <init>")
    val ctxReg = (insns[ctxPutIdx] as OneRegisterInstruction).registerA

    // Insert registerSearchCard(this) before return-void (register v0 holds `this` in Lirg.<init>)
    val returnIdx = insns.indexOfLast { it.opcode == Opcode.RETURN_VOID }
    if (returnIdx < 0) throw PatchException("Could not find return-void in Lirg;-><init>")

    // Insert bottom-up so indices stay valid
    mutable.addInstruction(
        returnIdx,
        "invoke-static {v0}, $CUSTOM_QUICK_ACTIONS_CLASS->registerSearchCard(Ljava/lang/Object;)V",
    )
    mutable.addInstruction(
        ctxPutIdx + 1,
        "invoke-static {v$ctxReg}, $CUSTOM_QUICK_ACTIONS_CLASS->registerContext(Landroid/content/Context;)V",
    )
    quickActionsLogger.info("Hooked Lirg.<init> for CustomQuickActions context & search card")
}

private fun BytecodePatchContext.hookAllCategoriesGasStation() {
    val adpcClass = classDefByOrNull("Ladpc;")
        ?: throw PatchException("Missing Ladpc; class")
    val zMethod = mutableClassDefBy(adpcClass.type).methods.firstOrNull {
        it.name == "z" && it.parameterTypes.isEmpty() && it.returnType == "Labpy;"
    } ?: throw PatchException("Missing Ladpc;->z()Labpy;")

    val insns = zMethod.implementation?.instructions
        ?: throw PatchException("Ladpc;->z() has no implementation")
    val returnIdx = insns.indexOfLast { it.opcode == Opcode.RETURN_OBJECT }
    if (returnIdx < 0) throw PatchException("Missing return-object in Ladpc;->z()")
    val retReg = (insns[returnIdx] as OneRegisterInstruction).registerA

    // Insert bottom-up
    zMethod.addInstructions(
        returnIdx,
        """
            invoke-static {v$retReg}, $CUSTOM_QUICK_ACTIONS_CLASS->enhanceAllCategories(Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v$retReg
            check-cast v$retReg, Labpy;
        """.trimIndent(),
    )
    zMethod.addInstruction(
        0,
        "invoke-static {p0}, $CUSTOM_QUICK_ACTIONS_CLASS->captureLadpc(Ljava/lang/Object;)V",
    )
    quickActionsLogger.info("Hooked Ladpc.z() to include Gas Stations in all categories")
}

private fun BytecodePatchContext.hookQuickBarCategories() {
    val adpcClass = classDefByOrNull("Ladpc;")
        ?: throw PatchException("Missing Ladpc; class")
    val aMethod = mutableClassDefBy(adpcClass.type).methods.firstOrNull {
        it.name == "A" && it.parameterTypes.isEmpty() && it.returnType == "Labpy;"
    } ?: throw PatchException("Missing Ladpc;->A()Labpy;")

    val insns = aMethod.implementation?.instructions
        ?: throw PatchException("Ladpc;->A() has no implementation")

    // Find `move-result-object v1` right after `invoke-virtual {v4}, Ladpc;->z()Labpy;`
    val zCallIdx = insns.indexOfFirst { insn ->
        (insn as? ReferenceInstruction)?.reference?.let {
            it is MethodReference && it.definingClass == "Ladpc;" && it.name == "z"
        } == true
    }
    if (zCallIdx < 0 || zCallIdx + 1 >= insns.size) {
        throw PatchException("Could not find Ladpc;->z() call in Ladpc;->A()")
    }
    val fullListReg = (insns[zCallIdx + 1] as OneRegisterInstruction).registerA

    val returnIdx = insns.indexOfLast { it.opcode == Opcode.RETURN_OBJECT }
    if (returnIdx < 0) throw PatchException("Missing return-object in Ladpc;->A()")
    val retReg = (insns[returnIdx] as OneRegisterInstruction).registerA

    aMethod.addInstructions(
        returnIdx,
        """
            invoke-static {v$fullListReg}, $CUSTOM_QUICK_ACTIONS_CLASS->buildQuickBarCategories(Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v$retReg
            check-cast v$retReg, Labpy;
        """.trimIndent(),
    )
    quickActionsLogger.info("Hooked Ladpc.A() for 5-icon quick bar [EV, Gas, Food, Home, Work]")
}

private fun BytecodePatchContext.hookQuickIconClick() {
    val iqvClass = classDefByOrNull("Liqv;")
        ?: throw PatchException("Missing Liqv; quick-icon click handler")
    val onClick = mutableClassDefBy(iqvClass.type).methods.firstOrNull {
        it.name == "onClick" && it.parameterTypes == listOf("Landroid/view/View;")
    } ?: throw PatchException("Missing Liqv;->onClick(Landroid/view/View;)V")

    onClick.addInstructions(
        0,
        """
            iget-object v0, p0, Liqv;->a:Ljava/lang/String;
            invoke-static {v0, p1}, $CUSTOM_QUICK_ACTIONS_CLASS->handleQuickIconClick(Ljava/lang/String;Landroid/view/View;)Z
            move-result v0
            if-eqz v0, :morphe_normal_click
            return-void
            :morphe_normal_click
            nop
        """.trimIndent(),
    )
    quickActionsLogger.info("Hooked Liqv.onClick() for Home & Work quick-icon navigation")
}

private fun BytecodePatchContext.shortenAutoStartCountdown() {
    val targets = listOf(
        Triple("Llal;", "ofSeconds", true),  // PreNavRouteOverviewOverlayImpl (wide 10L)
        Triple("Lkdy;", "S", false),         // Legacy PlaceDetails footer (int 10)
        Triple("Lkte;", "S", false),         // Nextgen PlaceDetailsFooterViewModelImpl (int 10)
    )
    var patchedCount = 0
    for ((type, methodName, isWide) in targets) {
        val cls = classDefByOrNull(type)
            ?: throw PatchException("Missing auto-start countdown class $type")
        val clinit = mutableClassDefBy(cls.type).methods.firstOrNull { it.name == "<clinit>" }
            ?: throw PatchException("Missing <clinit> in $type")
        val insns = clinit.implementation?.instructions
            ?: throw PatchException("$type.<clinit> has no implementation")

        var patchedHere = false
        for (i in 0 until insns.size - 1) {
            val insn = insns[i]
            val next = insns[i + 1]
            if (insn is WideLiteralInstruction && insn.wideLiteral == 10L) {
                val nextRef = (next as? ReferenceInstruction)?.reference as? MethodReference
                if (nextRef != null && nextRef.name == methodName) {
                    val reg = (insn as OneRegisterInstruction).registerA
                    if (isWide) {
                        clinit.replaceInstruction(i, "const-wide/16 v$reg, 0x$AUTO_START_COUNTDOWN_SECONDS")
                    } else {
                        clinit.replaceInstruction(i, "const/16 v$reg, 0x$AUTO_START_COUNTDOWN_SECONDS")
                    }
                    patchedHere = true
                    patchedCount++
                    break
                }
            }
        }
        if (!patchedHere) {
            throw PatchException("Could not find 10s countdown constant in $type.<clinit>")
        }
    }
    quickActionsLogger.info("Shortened $patchedCount auto-start navigation countdown timers from 10s to ${AUTO_START_COUNTDOWN_SECONDS}s")
}
