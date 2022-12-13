package org.arend.refactoring.changeSignature

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.command.executeCommand
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiCodeFragment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.descendantsOfType
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.changeSignature.ChangeSignatureDialogBase
import com.intellij.refactoring.ui.CodeFragmentTableCellEditorBase
import com.intellij.refactoring.ui.ComboBoxVisibilityPanel
import com.intellij.refactoring.ui.StringTableCellEditor
import com.intellij.ui.EditorTextField
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.TableView
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.Consumer
import org.arend.ArendFileType
import org.arend.naming.scope.ListScope
import org.arend.naming.scope.Scope
import org.arend.psi.ext.ArendDefFunction
import org.arend.psi.ext.ArendReferenceElement
import org.arend.psi.listener.ArendPsiChangeService
import org.arend.resolving.ArendResolveCache
import java.awt.Component
import java.util.Collections.singletonList
import javax.swing.DefaultListSelectionModel
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.ChangeEvent
import javax.swing.event.TableModelEvent
import javax.swing.table.TableCellEditor

class ArendChangeSignatureDialog(project: Project, val descriptor: ArendChangeSignatureDescriptor) :
    ChangeSignatureDialogBase<ArendParameterInfo, PsiElement, String, ArendChangeSignatureDescriptor, ArendChangeSignatureDialogParameterTableModelItem, ArendParameterTableModel>(project, descriptor, false, descriptor.method.context),
    ArendExpressionFragmentResolveListener {
    private var parametersPanel: JPanel? = null
    private lateinit var parameterToUsages: MutableMap<ArendChangeSignatureDialogParameterTableModelItem, MutableMap<ArendExpressionCodeFragment, MutableSet<TextRange>>>
    private lateinit var parameterToDependencies: MutableMap<ArendExpressionCodeFragment, MutableSet<ArendChangeSignatureDialogParameterTableModelItem>>

    private fun clearParameter(fragment: ArendExpressionCodeFragment) {
        for (usageEntry in parameterToUsages) usageEntry.value.remove(fragment)
        parameterToDependencies.remove(fragment)
    }

    override fun expressionFragmentResolved(codeFragment: ArendExpressionCodeFragment) {
        val resolveCache = project.service<ArendResolveCache>()
        val referableToItem = HashMap<ArendChangeSignatureDialogParameter, ArendChangeSignatureDialogParameterTableModelItem>()
        for (item in myParametersTable.items) referableToItem[item.associatedReferable] = item

        clearParameter(codeFragment)

        val newDependencies = HashSet<ArendChangeSignatureDialogParameterTableModelItem>()
        parameterToDependencies[codeFragment] = newDependencies
        val refs = codeFragment.descendantsOfType<ArendReferenceElement>().toList()

        for (ref in refs) {
            val target = resolveCache.getCached(ref)
            val item = referableToItem[target]
            if (item != null) {
                newDependencies.add(item)
                var p = parameterToUsages[item]
                if (p == null) {p = HashMap(); parameterToUsages[item] = p }
                var s = p[codeFragment]
                if (s == null) {s = HashSet(); p[codeFragment] = s}
                s.add(ref.textRange)
            }
        }

    }

    override fun updatePropagateButtons() {
        super.updatePropagateButtons()
        updateToolbarButtons()
    }

    override fun customizeParametersTable(table: TableView<ArendChangeSignatureDialogParameterTableModelItem>?) {
        super.customizeParametersTable(table)
    }

    override fun getFileType() = ArendFileType

    override fun createParametersInfoModel(descriptor: ArendChangeSignatureDescriptor) =
        ArendParameterTableModel( descriptor, this, {item: ArendChangeSignatureDialogParameterTableModelItem -> getParametersScope(item)}, myDefaultValueContext)

    override fun createRefactoringProcessor(): BaseRefactoringProcessor =
        ArendChangeSignatureProcessor(project, evaluateChangeInfo(myParametersTableModel))

    override fun createReturnTypeCodeFragment(): PsiCodeFragment {
        val referable = myMethod.method
        val returnExpression = when (referable) {
            is ArendDefFunction -> referable.returnExpr?.text ?: ""
            else -> ""
        }
        return ArendExpressionCodeFragment(myProject, returnExpression, getParametersScope(null), referable, this)
    }

    override fun createCallerChooser(title: String?, treeToReuse: Tree?, callback: Consumer<in MutableSet<PsiElement>>?) = null

    // TODO: add information about errors
    override fun validateAndCommitData(): String? = null

    private fun evaluateChangeInfo(parametersModel: ArendParameterTableModel): ArendChangeInfo {
        return ArendChangeInfo(parametersModel.items.map {  it.parameter }.toMutableList(), myReturnTypeCodeFragment?.text, myMethod.method)
    }

    override fun calculateSignature(): String =
        evaluateChangeInfo(myParametersTableModel).signature()

    override fun createVisibilityControl() = object : ComboBoxVisibilityPanel<String>("", arrayOf()) {}

    override fun createParametersPanel(hasTabsInDialog: Boolean): JPanel {
        myParametersTable = object : TableView<ArendChangeSignatureDialogParameterTableModelItem?>(myParametersTableModel) {
            override fun removeEditor() {
                clearEditorListeners()
                super.removeEditor()
            }

            override fun editingStopped(e: ChangeEvent) {
                invokeTypeHighlighting(editingRow)
                removeEditor()
                ApplicationManager.getApplication().invokeLater {
                    highlightDependentFields(editingRow + 1)
                    updateToolbarButtons()
                    updateUI()
                }
            }

            private fun clearEditorListeners() {
                val editor = getCellEditor()
                if (editor is StringTableCellEditor) {
                    editor.clearListeners()
                } else if (editor is CodeFragmentTableCellEditorBase) {
                    editor.clearListeners()
                }
            }

            override fun prepareEditor(editor: TableCellEditor, row: Int, column: Int): Component {
                val listener: DocumentListener = object : DocumentListener {
                    override fun documentChanged(e: DocumentEvent) {
                        /*  TODO: There is a problem that this document listener is added multiple times to the list of document listeners
                        *   TODO: (this happens in EditorTextField.installDocumentListener)
                        *   TODO: This leads to updateSignature() invoked multiple times upon a single keystroke
                        * */
                        val ed = myParametersTable.cellEditor
                        val editorValue = ed.cellEditorValue
                        if (column == 0) {
                            myParametersTableModel.setValueAtWithoutUpdate(editorValue, row, column)
                        }
                        if (column == 1) {
                            val updatedText = e.document.text
                            val updatedFragment = (myParametersTableModel.items[row].typeCodeFragment as ArendExpressionCodeFragment).updatedFragment(updatedText)
                            myParametersTableModel.setValueAtWithoutUpdate(updatedFragment, row, column)
                        }
                        updateSignature()
                    }
                }

                if (editor is StringTableCellEditor) {
                    editor.addDocumentListener(listener)
                } else if (editor is CodeFragmentTableCellEditorBase) {
                    editor.addDocumentListener(listener)
                }
                return super.prepareEditor(editor, row, column)
            }
        }

        myParametersTable.setShowGrid(false)
        myParametersTable.cellSelectionEnabled = true
        myParametersTable.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        myParametersTable.selectionModel.setSelectionInterval(0, 0)
        myParametersTable.surrendersFocusOnKeystroke = true

        parametersPanel = ToolbarDecorator.createDecorator(tableComponent).createPanel()
        myPropagateParamChangesButton.isEnabled = false
        myPropagateParamChangesButton.isVisible = false
        myParametersTableModel.addTableModelListener(mySignatureUpdater)
        customizeParametersTable(myParametersTable)

        parameterToUsages = HashMap(); parameterToDependencies = HashMap()
        for (i in 0 until myParametersTable.items.size) invokeTypeHighlighting(i)

        val selectionModel = (this.myParametersTable.selectionModel as DefaultListSelectionModel)
        val oldSelectionListeners = selectionModel.listSelectionListeners
        val oldModelListeners = this.myParametersTableModel.tableModelListeners

        for (l in oldSelectionListeners) selectionModel.removeListSelectionListener(l)
        for (l in oldModelListeners) this.myParametersTableModel.removeTableModelListener(l)

        this.myParametersTable.selectionModel.addListSelectionListener { ev ->
            for (l in oldSelectionListeners) l.valueChanged(ev)
            updateToolbarButtons()
        }

        this.myParametersTableModel.addTableModelListener { ev ->
            for (l in oldModelListeners) l.tableChanged(ev)
            if (ev.type == TableModelEvent.UPDATE && ev.lastRow - ev.firstRow == 1 || ev.type == TableModelEvent.DELETE) { //Row swap
                highlightDependentFields(ev.lastRow)
            }
            updateToolbarButtons()
        }
        return parametersPanel!! //safe
    }

    fun refactorParameterNames(item: ArendChangeSignatureDialogParameterTableModelItem, newName: String) {
        val docManager = PsiDocumentManager.getInstance(project)
        val dataToWrite = HashMap<Document, Pair<Int, String>>()

         runReadAction {
             val usages = parameterToUsages[item]
             if (usages != null) {
                 val usagesAmendments = HashMap<ArendExpressionCodeFragment, MutableSet<TextRange>>()

                 for (entry in usages) {
                     val codeFragment = entry.key
                     val itemToModify = myParametersTable.items.firstOrNull { it.typeCodeFragment == codeFragment }
                     val itemIndex = itemToModify?.let { myParametersTable.items.indexOf(it) } ?: -1
                     val changes = entry.value.sortedBy { it.startOffset }
                     val updatedChanges = HashSet<TextRange>()
                     val textFile = docManager.getDocument(codeFragment)
                     if (textFile != null) {
                         var text = textFile.text
                         var delta = 0
                         for (change in changes) {
                             text = text.replaceRange(IntRange(change.startOffset + delta, change.endOffset - 1 + delta), newName)
                             val epsilon = newName.length - change.length
                             updatedChanges.add(TextRange(change.startOffset + delta, change.endOffset + delta + epsilon))
                             delta += epsilon
                         }
                         dataToWrite[textFile] = Pair(itemIndex, text)
                         usagesAmendments[codeFragment] = updatedChanges
                     }
                 }

                 for (amendment in usagesAmendments)
                     usages[amendment.key] = amendment.value
             }
        }

        ApplicationManager.getApplication().invokeAndWait({
            executeCommand {
                runWriteAction {
                    for (e in dataToWrite) {
                        val textFile = e.key
                        val (itemIndex, text) = e.value
                        textFile.replaceString(0, textFile.text.length, text)
                        docManager.commitDocument(textFile)
                        val updatedPsi = docManager.getPsiFile(textFile)
                        if (itemIndex != -1 )
                            myParametersTableModel.setValueAtWithoutUpdate(updatedPsi, itemIndex, 1)
                    }
                }
            }

            myParametersTable.updateUI()
            myReturnTypeField.updateUI()
            updateSignature()
        }, ModalityState.defaultModalityState())
    }

    fun getParameterTableItems() = myParametersTable.items

    private fun getParametersScope(item: ArendChangeSignatureDialogParameterTableModelItem?): () -> Scope = { ->
        val items = this.myParametersTableModel.items
        val limit = items.indexOfFirst { it == item }.let { if (it == -1) items.size else it }
        val params = items.take(limit).map { it.associatedReferable }
        ListScope(params)
    }

    private fun getTypeTextField(index: Int) = (this.myParametersTable.getCellEditor(index, 1) as? CodeFragmentTableCellEditorBase?)?.getTableCellEditorComponent(myParametersTable, myParametersTableModel.items[index].typeCodeFragment, false, 0, 0) as? EditorTextField

    private fun invokeTypeHighlighting(index: Int) {
        val fragment = if (index == -1) this.myReturnTypeCodeFragment else myParametersTableModel.items[index].typeCodeFragment
        val editorTextField = if (index == -1) this.myReturnTypeField else getTypeTextField(index)
        val codeAnalyzer = DaemonCodeAnalyzer.getInstance(project) as? DaemonCodeAnalyzerImpl
        val document = fragment?.let{ PsiDocumentManager.getInstance(project).getDocument(it) }
        if (fragment is ArendExpressionCodeFragment && codeAnalyzer != null && editorTextField != null && document != null) {
            editorTextField.addNotify()
            codeAnalyzer.restart(fragment)
            val textEditor = editorTextField.editor?.let{ TextEditorProvider.getInstance().getTextEditor(it) }
            if (textEditor != null) codeAnalyzer.runPasses(fragment, document, singletonList(textEditor), IntArray(0), true, null)
        }
    }

    private fun updateToolbarButtons() {
        val parametersPanel = parametersPanel ?: return
        val downButton = ToolbarDecorator.findDownButton(parametersPanel) ?: return
        val upButton = ToolbarDecorator.findUpButton(parametersPanel) ?: return

        val selectedIndices = this.myParametersTable.selectionModel.selectedIndices
        if (selectedIndices.size == 1) {
            val selectedIndex = selectedIndices.first()
            val currentItem = this.myParametersTableModel.items[selectedIndex]

            val dependencyChecker = { pI: ArendChangeSignatureDialogParameterTableModelItem, cI: ArendChangeSignatureDialogParameterTableModelItem ->
                !(parameterToDependencies[cI.typeCodeFragment]?.contains(pI) ?: false)
            }
            if (selectedIndex > 0) {
                val prevItem = this.myParametersTableModel.items[selectedIndex - 1]
                upButton.isEnabled = dependencyChecker.invoke(prevItem, currentItem)
            }
            if (selectedIndex < this.myParametersTableModel.items.size - 1) {
                val nextItem = this.myParametersTableModel.items[selectedIndex + 1]
                downButton.isEnabled = dependencyChecker.invoke(currentItem, nextItem)
            }
        }
    }

    private fun highlightDependentFields(index: Int) {
        project.service<ArendPsiChangeService>().modificationTracker.incModificationCount()
        for (i in index until myParametersTable.items.size) invokeTypeHighlighting(i)
        invokeTypeHighlighting(-1)
    }
}