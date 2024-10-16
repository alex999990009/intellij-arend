package org.arend.intention

import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import org.arend.psi.*
import org.arend.psi.ext.*
import org.arend.psi.ext.ArendCompositeElement
import org.arend.psi.ext.PsiLocatedReferable
import org.arend.refactoring.*
import org.arend.refactoring.changeSignature.ArendChangeInfo
import org.arend.refactoring.changeSignature.ArendChangeInfo.Companion.getParameterInfo
import org.arend.refactoring.changeSignature.ArendChangeSignatureUsageProcessor
import org.arend.refactoring.changeSignature.ArendParameterInfo
import org.arend.util.*
import java.util.Collections.singletonList


class ChangeArgumentExplicitnessIntention : SelfTargetingIntention<ArendCompositeElement>(ArendCompositeElement::class.java, ArendBundle.message("arend.coClause.changeArgumentExplicitness")) {
    override fun isApplicableTo(element: ArendCompositeElement, caretOffset: Int, editor: Editor): Boolean {
        if (DumbService.isDumb(element.project)) return false

        return when (element) {
            is ArendNameTele, is ArendFieldTele, is ArendTypeTele ->
                element.parent?.let{ it is ArendDefFunction || it is ArendDefInstance || it is ArendDefClass || it is ArendDefData || it is ArendClassField || it is ArendConstructor } ?: false
            else -> false
        }
    }

    override fun applyTo(element: ArendCompositeElement, project: Project, editor: Editor) {
        val elementOnCaret = element.containingFile.findElementAt(editor.caretModel.offset)
        val switchedArgIndexInTele = getSwitchedArgIndex(element, elementOnCaret)
        val def = element.ancestor() as? PsiLocatedReferable ?: return

        val parameterInfo : List<ArendParameterInfo> = getParameterInfo(def)
        val referables = when (element) {
            is ArendNameTele -> element.identifierOrUnknownList.mapNotNull { it.defIdentifier }.toList()
            is ArendTypeTele -> element.typedExpr?.identifierOrUnknownList?.mapNotNull { it.defIdentifier }?.toList().let {
                if (it.isNullOrEmpty()) singletonList(element) else it
            }
            is ArendFieldTele -> element.referableList
            else -> throw IllegalStateException()
        }

        if (switchedArgIndexInTele == null || switchedArgIndexInTele == -1) {
            for (p in parameterInfo) if (referables.contains(p.correspondingReferable)) p.switchExplicit()
        } else {
            for (p in parameterInfo) if (p.correspondingReferable == referables[switchedArgIndexInTele]) p.switchExplicit()
        }

        val changeInfo = ArendChangeInfo(parameterInfo, null, def.name!!, def)
        val processor = ArendChangeSignatureUsageProcessor()
        processor.findUsages(changeInfo)
        processor.processPrimaryMethod(changeInfo)
    }

    override fun startInWriteAction(): Boolean = false

    private fun getSwitchedArgIndex(tele: ArendCompositeElement, switchedArg: PsiElement?): Int? {
        switchedArg ?: return -1
        if (tele is ArendTypeTele && getTele(tele) == null) return 0
        val argsText = getTele(tele)?.map { it.text }
        return if (argsText?.size == 1) 0 else argsText?.indexOf(switchedArg.text)
    }
}