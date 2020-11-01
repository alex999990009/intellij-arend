package org.arend.toolWindow.debugExpr

import com.intellij.openapi.Disposable
import com.intellij.openapi.wm.ToolWindow
import com.intellij.psi.PsiElement
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBList
import org.arend.core.context.binding.Binding
import org.arend.core.expr.Expression
import org.arend.ext.ArendExtension
import org.arend.ext.error.ErrorReporter
import org.arend.term.concrete.Concrete
import org.arend.typechecking.result.TypecheckingResult
import org.arend.typechecking.visitor.CheckTypeVisitor

class CheckTypeDebugger(
    errorReporter: ErrorReporter,
    extension: ArendExtension?,
    private val element: PsiElement,
    val toolWindow: ToolWindow,
) : CheckTypeVisitor(errorReporter, null, extension), Disposable {
    private var isResuming = true
    override fun checkExpr(expr: Concrete.Expression, expectedType: Expression?): TypecheckingResult {
        if (expr.data == element) {
            isResuming = false
        }
        if (!isResuming) {
            fillLocalVariables(expr)
            while (!isResuming) {
                Thread.onSpinWait()
            }
        }
        return super.checkExpr(expr, expectedType)
    }

    val splitter = JBSplitter(false, 0.25f)
    private val passList = JBList<Concrete.Expression>()
    private val varList = JBList<Binding>()

    init {
        splitter.firstComponent = passList
        splitter.secondComponent = varList
    }

    private fun fillLocalVariables(expr: Concrete.Expression) {
    }

    override fun dispose() {
        splitter.dispose()
    }
}
