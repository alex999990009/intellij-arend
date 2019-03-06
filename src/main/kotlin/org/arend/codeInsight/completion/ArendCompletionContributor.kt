package org.arend.codeInsight.completion

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.completion.CompletionInitializationContext.DUMMY_IDENTIFIER_TRIMMED
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.ElementPattern
import com.intellij.patterns.PlatformPatterns
import com.intellij.patterns.PsiElementPattern
import com.intellij.patterns.StandardPatterns.*
import com.intellij.psi.*
import com.intellij.psi.TokenType.BAD_CHARACTER
import com.intellij.psi.tree.IElementType
import com.intellij.util.ProcessingContext
import com.intellij.util.containers.isNullOrEmpty
import org.arend.psi.*
import org.arend.psi.ArendElementTypes.*
import org.arend.psi.ext.impl.ArendGroup
import org.arend.search.ArendWordScanner
import org.arend.term.abs.Abstract
import java.util.*

class ArendCompletionContributor : CompletionContributor() {

    init {
        basic(PREC_CONTEXT, KeywordCompletionProvider(FIXITY_KWS))

        basic(afterLeaf(LEVEL_KW), ACPConditionalProvider(FIXITY_KWS, { parameters ->
            val p = parameters.prevElement?.parent
            p is ArendDefFunction && p.useKw != null
        }))
        basic(afterLeaf(FAT_ARROW), ConditionalProvider(FIXITY_KWS,
                {parameters -> withParentOrGrandParent(ArendClassFieldSyn::class.java).accepts(parameters.originalPosition)})) // fixity kws for class field synonym (2nd part)

        basic(AS_CONTEXT, ConditionalProvider(AS_KW_LIST, { parameters -> (parameters.position.parent.parent as ArendNsId).asKw == null }))

        basic(NS_CMD_CONTEXT, ConditionalProvider(HU_KW_LIST, { parameters -> parameters.originalPosition?.parent is ArendFile}))

        fun noUsing(cmd: ArendStatCmd): Boolean = cmd.nsUsing?.usingKw == null
        fun noHiding(cmd: ArendStatCmd): Boolean = cmd.hidingKw == null
        fun noUsingAndHiding(cmd: ArendStatCmd): Boolean = noUsing(cmd) && noHiding(cmd)

        basic(NS_CMD_CONTEXT, ConditionalProvider(USING_KW_LIST, { parameters -> noUsing(parameters.position.parent.parent as ArendStatCmd) }))

        basic(NS_CMD_CONTEXT, ConditionalProvider(HIDING_KW_LIST, { parameters -> noUsingAndHiding(parameters.position.parent.parent as ArendStatCmd) }))

        basic(withAncestors(PsiErrorElement::class.java, ArendNsUsing::class.java, ArendStatCmd::class.java),
                ConditionalProvider(HIDING_KW_LIST, { parameters -> noHiding(parameters.position.parent.parent.parent as ArendStatCmd) }))

        basic(STATEMENT_END_CONTEXT, JointOfStatementsProvider(STATEMENT_WT_KWS))

        basic(STATEMENT_END_CONTEXT, object : JointOfStatementsProvider(TRUNCATED_KW_LIST) {
            override fun insertHandler(keyword: String): InsertHandler<LookupElement> = InsertHandler { insertContext, _ ->
                val document = insertContext.document
                document.insertString(insertContext.tailOffset, " \\data ")
                insertContext.commitDocument()
                insertContext.editor.caretModel.moveToOffset(insertContext.tailOffset)
            }

            override fun lookupElement(keyword: String): LookupElementBuilder =
                    LookupElementBuilder.create(keyword).withPresentableText("\\truncated \\data")
        })

        basic(STATEMENT_END_CONTEXT, JointOfStatementsProvider(IMPORT_KW_LIST, { parameters ->
            val noWhere = { seq: Sequence<PsiElement> -> seq.filter { it is ArendWhere || it is ArendDefClass }.none() }

            parameters.leftStatement?.ancestors.let { if (it != null) noWhere(it) else true } &&
                    parameters.rightStatement?.ancestors.let { if (it != null) noWhere(it) else true } &&
                    (!parameters.leftBrace || parameters.ancestorsPE.isEmpty() || noWhere(parameters.ancestorsPE.asSequence())) &&
                    (!parameters.rightBrace || parameters.ancestorsNE.isEmpty() || noWhere(parameters.ancestorsNE.asSequence()))
        }))

        val classOrDataPositionMatcher = { position: PsiElement, insideWhere: Boolean, dataAllowed: Boolean ->
            var foundWhere = false
            var ancestor: PsiElement? = position
            var result2 = false
            while (ancestor != null) {
                if (ancestor is ArendWhere) foundWhere = true
                if ((dataAllowed && ancestor is ArendDefData) || ancestor is ArendDefClass) {
                    result2 = !(insideWhere xor foundWhere)
                    break
                } else if (ancestor is ArendDefinition && foundWhere) {
                    result2 = false
                    break
                }
                ancestor = ancestor.parent
            }
            result2
        }

        basic(STATEMENT_END_CONTEXT, JointOfStatementsProvider(CLASS_MEMBER_KWS,
                { parameters -> classOrDataPositionMatcher(parameters.completionParameters.position, false, false) }))

        basic(STATEMENT_END_CONTEXT, JointOfStatementsProvider(USE_KW_LIST,
                { parameters -> classOrDataPositionMatcher(parameters.completionParameters.position, true, true) }))
        basic(afterLeaf(USE_KW), KeywordCompletionProvider(COERCE_LEVEL_KWS))

        basic(and(DATA_CONTEXT, afterLeaf(TRUNCATED_KW)), KeywordCompletionProvider(DATA_KW_LIST))//data after \truncated keyword

        basic(WHERE_CONTEXT, JointOfStatementsProvider(WHERE_KW_LIST, { arendCompletionParameters: ArendCompletionParameters ->
            var anc = arendCompletionParameters.prevElement
            while (anc != null && anc !is ArendGroup && anc !is ArendClassStat) anc = anc.parent
            if (anc != null) {
                val da: ArendGroup? = anc as? ArendGroup
                (when {
                    da != null -> da.where == null
                    else -> false
                })
            } else false
        }, tailSpaceNeeded = true, noCrlfRequired = true, allowInsideBraces = false))

        val noExtendsCondition = { arendCompletionParameters: ArendCompletionParameters ->
            val condition = or(and(ofType(ID), withAncestors(ArendDefIdentifier::class.java, ArendDefClass::class.java)),
                    and(ofType(RPAREN), withAncestors(ArendFieldTele::class.java, ArendDefClass::class.java)))
            val dC = arendCompletionParameters.completionParameters.position.ancestors.filterIsInstance<ArendDefClass>().firstOrNull()
            if (dC != null) dC.extendsKw == null && (condition.accepts(arendCompletionParameters.prevElement)) else false
        }

        basic(and(withAncestors(PsiErrorElement::class.java, ArendDefClass::class.java), afterLeaf(ID)),
                ACPConditionalProvider(EXTENDS_KW_LIST, noExtendsCondition))
        basic(withAncestors(PsiErrorElement::class.java, ArendFieldTele::class.java, ArendDefClass::class.java),
                ACPConditionalProvider(EXTENDS_KW_LIST, { arendCompletionParameters ->
                    noExtendsCondition.invoke(arendCompletionParameters) &&
                            arendCompletionParameters.completionParameters.position.parent?.parent?.findNextSibling() !is ArendFieldTele
                }))

        basic(and(DATA_CONTEXT, afterLeaf(COLON)), KeywordCompletionProvider(DATA_UNIVERSE_KW))

        val bareSigmaOrPi = { expression: PsiElement ->
            var result: PsiElement? = expression

            val context = ofType(PI_KW, SIGMA_KW)

            var tele: ArendTypeTele? = null
            while (result != null) {
                if (result is ArendTypeTele) tele = result
                if (result is ArendExpr && result !is ArendUniverseAtom) break
                result = result.parent
            }

            if (context.accepts(expression)) true else
                if (tele?.text == null || tele.text.startsWith("(")) false else //Not Bare \Sigma or \Pi -- should display all expression keywords in completion
                    result is ArendSigmaExpr || result is ArendPiExpr
        }
        val allowedInReturn = { expression: PsiElement ->
            var result: PsiElement? = expression
            while (result != null) {
                if (result is ArendReturnExpr) break
                result = result?.parent
            }
            val resultC = result
            val resultParent = resultC?.parent
            when (resultC) {
                is ArendReturnExpr -> when {
                    resultC.levelKw != null -> false
                    resultParent is ArendDefInstance -> false
                    resultParent is ArendDefFunction -> !resultParent.isCowith
                    else -> true
                }
                else -> true
            }
        }

        val noExpressionKwsAfter = ofType(SET, PROP_KW, UNIVERSE, TRUNCATED_UNIVERSE, NEW_KW)
        val afterElimVar = and(ofType(ID), withAncestors(ArendRefIdentifier::class.java, ArendElim::class.java))

        val expressionFilter = { allowInBareSigmaOrPiExpressions: Boolean, allowInArgumentExpressionContext: Boolean ->
            { arendCompletionParameters: ArendCompletionParameters ->
                !FIELD_CONTEXT.accepts(arendCompletionParameters.prevElement) && //No keyword completion after field
                        !(RETURN_CONTEXT.accepts(arendCompletionParameters.completionParameters.position) && !allowedInReturn(arendCompletionParameters.completionParameters.position)) &&
                        !(ofType(RBRACE).accepts(arendCompletionParameters.prevElement) && withParent(ArendCaseExpr::class.java).accepts(arendCompletionParameters.prevElement)) && //No keyword completion after \with or } in case expr
                        !(ofType(LAM_KW, LET_KW, WITH_KW).accepts(arendCompletionParameters.prevElement)) && //No keyword completion after \lam or \let
                        !(noExpressionKwsAfter.accepts(arendCompletionParameters.prevElement)) && //No expression keyword completion after universe literals or \new keyword
                        !(or(LPH_CONTEXT, LPH_LEVEL_CONTEXT).accepts(arendCompletionParameters.completionParameters.position)) && //No expression keywords when completing levels in universes
                        !(afterElimVar.accepts(arendCompletionParameters.prevElement)) && //No expression keywords in \elim expression
                        (allowInBareSigmaOrPiExpressions || arendCompletionParameters.prevElement == null || !bareSigmaOrPi(arendCompletionParameters.prevElement)) &&  //Only universe expressions allowed inside Sigma or Pi expressions
                        (allowInArgumentExpressionContext || !ARGUMENT_EXPRESSION.accepts(arendCompletionParameters.completionParameters.position)) // New expressions & universe expressions are allowed in applications
            }
        }

        basic(EXPRESSION_CONTEXT, ACPConditionalProvider(DATA_UNIVERSE_KW, expressionFilter.invoke(true, true)))
        basic(or(TELE_CONTEXT, FIRST_TYPE_TELE_CONTEXT), KeywordCompletionProvider(DATA_UNIVERSE_KW))

        basic(EXPRESSION_CONTEXT, ACPConditionalProvider(BASIC_EXPRESSION_KW, expressionFilter.invoke(false, false)))
        basic(EXPRESSION_CONTEXT, ACPConditionalProvider(NEW_KW_LIST, expressionFilter.invoke(false, true)))

        val truncatedTypeInsertHandler = InsertHandler<LookupElement> { insertContext, _ ->
            val document = insertContext.document
            document.insertString(insertContext.tailOffset, " ") // add tail whitespace
            insertContext.commitDocument()
            insertContext.editor.caretModel.moveToOffset(insertContext.startOffset + 1)
            document.replaceString(insertContext.startOffset + 1, insertContext.startOffset + 2, "1") //replace letter n by 1 so that the keyword would be highlighted correctly
            insertContext.editor.selectionModel.setSelection(insertContext.startOffset + 1, insertContext.startOffset + 2)
        }

        val truncatedTypeCompletionProvider = object : KeywordCompletionProvider(FAKE_NTYPE_LIST) {
            override fun insertHandler(keyword: String): InsertHandler<LookupElement> = truncatedTypeInsertHandler
        }

        basic(and(DATA_CONTEXT, afterLeaf(COLON)), truncatedTypeCompletionProvider)
        basic(EXPRESSION_CONTEXT, object : ACPConditionalProvider(FAKE_NTYPE_LIST, expressionFilter.invoke(true, true)) {
            override fun insertHandler(keyword: String): InsertHandler<LookupElement> = truncatedTypeInsertHandler
        })
        basic(or(TELE_CONTEXT, FIRST_TYPE_TELE_CONTEXT), truncatedTypeCompletionProvider)

        fun isAfterNumber(element: PsiElement?): Boolean = element?.prevSibling?.text == "\\" && element.node?.elementType == NUMBER

        basic(DATA_OR_EXPRESSION_CONTEXT, object: ACPConditionalProvider(listOf("-Type"), { parameters -> isAfterNumber(parameters.prevElement) }) {
            override fun computePrefix(parameters: CompletionParameters, resultSet: CompletionResultSet): String = ""
        })

        basic(DATA_OR_EXPRESSION_CONTEXT, object: ConditionalProvider(listOf("Type"), { cP ->
            cP.originalPosition?.text?.matches(Regex("\\\\[0-9]+(-(T(y(pe?)?)?)?)?")) ?: false }) {
            override fun computePrefix(parameters: CompletionParameters, resultSet: CompletionResultSet): String =
                    super.computePrefix(parameters, resultSet).replace(Regex("\\\\[0-9]+-?"), "")
        })

        basic(LPH_CONTEXT, ConditionalProvider(LPH_KW_LIST, { cP ->
            val pp = cP.position.parent.parent
            when (pp) {
                is ArendSetUniverseAppExpr, is ArendTruncatedUniverseAppExpr ->
                    pp.children.filterIsInstance<ArendAtomLevelExpr>().isEmpty()
                else -> pp.children.filterIsInstance<ArendAtomLevelExpr>().size <= 1
            }
        }))

        basic(withParent(ArendLevelExpr::class.java), ConditionalProvider(LPH_KW_LIST, { cP ->
            when (cP.position.parent?.firstChild?.node?.elementType) {
                MAX_KW, SUC_KW -> true
                else -> false
            }
        }))

        basic(LPH_LEVEL_CONTEXT, KeywordCompletionProvider(LPH_LEVEL_KWS))

        fun pairingWordCondition(condition: (PsiElement?) -> Boolean, position: PsiElement): Boolean {
            var pos: PsiElement? = position
            var exprFound = false
            while (pos != null) {
                if (condition.invoke(pos)) {
                    exprFound = true
                    break
                }
                if (!(pos.nextSibling == null || pos.nextSibling is PsiErrorElement)) break
                pos = pos.parent
            }
            return exprFound
        }

        val pairingInCondition = { pos: PsiElement -> pairingWordCondition({ position: PsiElement? -> position is ArendLetExpr && position.inKw == null }, pos) }
        val pairingWithCondition = { pos: PsiElement -> pairingWordCondition({ position: PsiElement? -> position is ArendCaseExpr && position.withKw == null }, pos) }

        basic(and(EXPRESSION_CONTEXT, not(or(afterLeaf(IN_KW), afterLeaf(LET_KW)))),
                ConditionalProvider(IN_KW_LIST, {parameters -> pairingInCondition.invoke((parameters.position))}))

        val caseContext = and(CASE_CONTEXT, not(or(afterLeaf(WITH_KW), afterLeaf(CASE_KW), afterLeaf(COLON))))

        basic(caseContext, ConditionalProvider(WITH_KW_LIST, { parameters -> pairingWithCondition.invoke(parameters.position)}, false))

        val asCondition1 = { position: PsiElement? -> position is ArendCaseArg && position.asKw == null }
        val asCondition2 = { position: PsiElement? ->
            //Alternative condition needed to ensure that as is added before semicolon
            var p = position
            if (p != null && p.nextSibling is PsiWhiteSpace) p = p.nextSibling.nextSibling
            p != null && p.node.elementType == COLON
        }
        val returnCondition = { pos: PsiElement ->
            pairingWordCondition({ position: PsiElement? ->
                if (position is ArendCaseArg) {
                    val pp = position.parent as? ArendCaseExpr
                    pp != null && pp.caseArgList.lastOrNull() == position && pp.returnKw == null
                } else false
            }, pos)
        }

        val argEndCondition = { pos: PsiElement ->
            pairingWordCondition({ position: PsiElement? ->
                asCondition1.invoke(position) || (position != null && asCondition2.invoke(position) && asCondition1.invoke(position.parent))
            }, pos)
        }

        basic(caseContext, ConditionalProvider(AS_KW_LIST, { parameters -> argEndCondition.invoke(parameters.position)}))
        basic(caseContext, ConditionalProvider(RETURN_KW_LIST, { parameters -> returnCondition.invoke(parameters.position)}))

        val emptyTeleList = { l: List<Abstract.Parameter> ->
            l.isEmpty() || l.size == 1 && (l[0].type == null || (l[0].type as PsiElement).text == DUMMY_IDENTIFIER_TRIMMED) &&
                    (l[0].referableList.size == 0 || l[0].referableList[0] == null || (l[0].referableList[0] as PsiElement).text == DUMMY_IDENTIFIER_TRIMMED)
        }
        val elimOrCoWithCondition = { coWithMode: Boolean ->
            { cP: CompletionParameters ->
                var pos2: PsiElement? = cP.position
                var exprFound = false
                while (pos2 != null) {
                    if (pos2.nextSibling is PsiWhiteSpace) {
                        val body = pos2.findNextSibling()
                        if (body is ArendFunctionBody || body is ArendDataBody) pos2 = body.parent
                    }

                    if ((pos2 is ArendDefFunction)) {
                        val fBody = pos2.functionBody
                        exprFound = fBody == null || fBody.fatArrow == null && fBody.elim?.elimKw == null
                        exprFound = exprFound &&
                                if (!coWithMode) !emptyTeleList(pos2.nameTeleList)  // No point of writing elim keyword if there are no arguments
                                else {
                                    val returnExpr = pos2.returnExpr
                                    returnExpr != null && returnExpr.levelKw == null
                                } // No point of writing cowith keyword if there is no result type or there is already \level keyword in result type
                        exprFound = exprFound && (fBody == null || fBody.cowithKw == null && fBody.elim.let { it == null || it.elimKw == null && it.withKw == null })
                        break
                    }
                    if ((pos2 is ArendDefData) && !coWithMode) {
                        val dBody = pos2.dataBody
                        exprFound = dBody == null || (dBody.elim?.elimKw == null && dBody.constructorList.isNullOrEmpty() && dBody.constructorClauseList.isNullOrEmpty())
                        exprFound = exprFound && !emptyTeleList(pos2.typeTeleList)
                        break
                    }

                    if (pos2 is ArendConstructor && pos2.elim == null && !coWithMode) {
                        exprFound = !emptyTeleList(pos2.typeTeleList)
                        break
                    }

                    if (pos2 is ArendClause || pos2 is ArendCoClause) break

                    if (pos2?.nextSibling == null) pos2 = pos2?.parent else break
                }
                exprFound
            }
        }

        basic(ELIM_CONTEXT, ConditionalProvider(ELIM_KW_LIST, elimOrCoWithCondition.invoke(false)))
        basic(ELIM_CONTEXT, ConditionalProvider(WITH_KW_LIST, elimOrCoWithCondition.invoke(false), false))
        basic(ELIM_CONTEXT, ConditionalProvider(COWITH_KW_LIST, elimOrCoWithCondition.invoke(true),  false))

        val isLiteralApp = { argumentAppExpr: ArendArgumentAppExpr ->
            argumentAppExpr.longNameExpr != null ||
                    ((argumentAppExpr.children[0] as? ArendAtomFieldsAcc)?.atom?.literal?.longName != null)
        }

        val unifiedLevelCondition = { atomIndex: Int?, forbidLevelExprs: Boolean, threshold: Int ->
            { cP: CompletionParameters ->
                var anchor: PsiElement? = if (atomIndex != null) cP.position.ancestors.filterIsInstance<ArendAtomFieldsAcc>().elementAtOrNull(atomIndex) else null
                var argumentAppExpr: ArendArgumentAppExpr? =
                        (anchor?.parent as? ArendAtomArgument)?.parent as? ArendArgumentAppExpr
                                ?: anchor?.parent as? ArendArgumentAppExpr
                if (anchor == null) {
                    anchor = cP.position.parent
                    argumentAppExpr = anchor?.parent as? ArendArgumentAppExpr
                    if (argumentAppExpr == null) {
                        anchor = null
                    }
                }

                if (anchor == null) {
                    argumentAppExpr = cP.position.parent as? ArendArgumentAppExpr
                }

                if (argumentAppExpr != null && anchor != null && isLiteralApp(argumentAppExpr)) {
                    var counter = argumentAppExpr.longNameExpr?.atomOnlyLevelExprList?.size ?: 0
                    var forbidden = false
                    val levelsExpr = argumentAppExpr.longNameExpr?.levelsExpr
                    if (levelsExpr != null) {
                        counter += levelsExpr.atomLevelExprList.size
                        if (forbidLevelExprs) forbidden = true
                    }
                    for (ch in argumentAppExpr.children) {
                        if (ch == anchor || ch == anchor.parent) break
                        if (ch is ArendAtomArgument) forbidden = true
                    }
                    counter < threshold && !forbidden
                } else argumentAppExpr?.longNameExpr?.levelsExpr?.levelKw != null && isLiteralApp(argumentAppExpr)
            }
        }

        basic(ARGUMENT_EXPRESSION, ConditionalProvider(LPH_KW_LIST, unifiedLevelCondition.invoke(0, false, 2)))
        basic(ARGUMENT_EXPRESSION, ConditionalProvider(LEVEL_KW_LIST, unifiedLevelCondition.invoke(0, true, 1)))
        basic(ARGUMENT_EXPRESSION_IN_BRACKETS, ConditionalProvider(LPH_LEVEL_KWS, unifiedLevelCondition.invoke(1, false, 2)))

        basic(withAncestors(PsiErrorElement::class.java, ArendArgumentAppExpr::class.java),
                ConditionalProvider(LPH_LEVEL_KWS, unifiedLevelCondition.invoke(null, false, 2)))

        basic(withParent(ArendArgumentAppExpr::class.java), ConditionalProvider(LPH_LEVEL_KWS, { parameters ->
            val argumentAppExpr: ArendArgumentAppExpr? = parameters.position.parent as ArendArgumentAppExpr
            argumentAppExpr?.longNameExpr?.levelsExpr?.levelKw != null && isLiteralApp(argumentAppExpr)
        }))

        basic(CLASSIFYING_CONTEXT, ConditionalProvider(CLASSIFYING_KW_LIST, { parameters ->
            parameters.position.ancestors.filterIsInstance<ArendDefClass>().firstOrNull().let { defClass ->
                if (defClass != null) !defClass.fieldTeleList.any { fieldTele -> fieldTele.isClassifying } else false
            }
        }))

        basic(LEVEL_CONTEXT, ConditionalProvider(LEVEL_KW_LIST, { parameters ->
            allowedInReturn(parameters.position)
        }))

        //basic(ANY, LoggerCompletionProvider())
    }

    private fun basic(pattern: ElementPattern<PsiElement>, provider: CompletionProvider<CompletionParameters>) {
        extend(CompletionType.BASIC, pattern, provider)
    }

    companion object {
        const val KEYWORD_PRIORITY = 0.0

        private fun afterLeaf(et: IElementType) = PlatformPatterns.psiElement().afterLeaf(PlatformPatterns.psiElement(et))
        private fun ofType(vararg types: IElementType) = or(*types.map { PlatformPatterns.psiElement(it) }.toTypedArray())
        private fun <T : PsiElement> ofTypeK(vararg types: Class<T>) = or(*types.map { PlatformPatterns.psiElement(it) }.toTypedArray())
        private fun <T : PsiElement> withParent(et: Class<T>) = PlatformPatterns.psiElement().withParent(PlatformPatterns.psiElement(et))
        private fun <T : PsiElement> withGrandParent(et: Class<T>) = PlatformPatterns.psiElement().withSuperParent(2, PlatformPatterns.psiElement(et))
        private fun <T : PsiElement> withParentOrGrandParent(et: Class<T>) = or(withParent(et), withGrandParent(et))
        private fun <T : PsiElement> withGrandParents(vararg et: Class<out T>) = or(*et.map { withGrandParent(it) }.toTypedArray())
        private fun <T : PsiElement> withGreatGrandParents(vararg et: Class<out T>) = or(*et.map { PlatformPatterns.psiElement().withSuperParent(3, it) }.toTypedArray())
        private fun <T : PsiElement> withParents(vararg et: Class<out T>) = or(*et.map { withParent(it) }.toTypedArray())
        private fun <T : PsiElement> withAncestors(vararg et: Class<out T>): ElementPattern<PsiElement> = and(*et.mapIndexed { i, it -> PlatformPatterns.psiElement().withSuperParent(i + 1, PlatformPatterns.psiElement(it)) }.toTypedArray())

        val ANY: PsiElementPattern.Capture<PsiElement> = PlatformPatterns.psiElement()
        val PREC_CONTEXT = or(afterLeaf(FUNCTION_KW), afterLeaf(LEMMA_KW), afterLeaf(COERCE_KW), afterLeaf(DATA_KW), afterLeaf(CLASS_KW), afterLeaf(RECORD_KW), and(afterLeaf(AS_KW), withGrandParent(ArendNsId::class.java)),
                and(afterLeaf(PIPE), withGrandParents(ArendConstructor::class.java, ArendDataBody::class.java)), //simple data type constructor
                and(afterLeaf(FAT_ARROW), withGrandParents(ArendConstructor::class.java, ArendConstructorClause::class.java)), //data type constructors with patterns
                and(afterLeaf(PIPE), withGrandParents(ArendClassField::class.java, ArendClassStat::class.java)), //class field
                and(afterLeaf(FAT_ARROW), withGrandParent(ArendClassFieldSyn::class.java))) //class field synonym

        val AS_CONTEXT = and(withGrandParent(ArendNsId::class.java), withParents(ArendRefIdentifier::class.java, PsiErrorElement::class.java))
        val NS_CMD_CONTEXT = withAncestors(PsiErrorElement::class.java, ArendStatCmd::class.java)
        val STATEMENT_END_CONTEXT = or(withParents(PsiErrorElement::class.java, ArendRefIdentifier::class.java),
                withAncestors(ArendDefIdentifier::class.java, ArendFieldDefIdentifier::class.java)) //Needed for correct completion inside empty classes
        private val INSIDE_RETURN_EXPR_CONTEXT = or(
                withAncestors(ArendRefIdentifier::class.java, ArendLongName::class.java, ArendLiteral::class.java, ArendAtom::class.java,
                        ArendAtomFieldsAcc::class.java, ArendReturnExpr::class.java),
                withAncestors(PsiErrorElement::class.java, ArendAtomFieldsAcc::class.java, ArendReturnExpr::class.java))
        val WHERE_CONTEXT = and(
                or(STATEMENT_END_CONTEXT,
                        withAncestors(ArendDefIdentifier::class.java, ArendIdentifierOrUnknown::class.java, ArendNameTele::class.java)),
                not(PREC_CONTEXT),
                not(INSIDE_RETURN_EXPR_CONTEXT),
                not(or(afterLeaf(COLON), afterLeaf(TRUNCATED_KW), afterLeaf(FAT_ARROW),
                        afterLeaf(WITH_KW), afterLeaf(ARROW), afterLeaf(IN_KW), afterLeaf(INSTANCE_KW), afterLeaf(EXTENDS_KW), afterLeaf(DOT), afterLeaf(NEW_KW),
                        afterLeaf(CASE_KW), afterLeaf(LET_KW), afterLeaf(WHERE_KW), afterLeaf(USE_KW), afterLeaf(PIPE), afterLeaf(LEVEL_KW))),
                not(withAncestors(PsiErrorElement::class.java, ArendDefInstance::class.java)), // don't allow \where in incomplete instance expressions
                not(withAncestors(ArendDefIdentifier::class.java, ArendIdentifierOrUnknown::class.java, ArendNameTele::class.java, ArendDefInstance::class.java)))
        val DATA_CONTEXT = withAncestors(PsiErrorElement::class.java, ArendDefData::class.java, ArendStatement::class.java)
        val RETURN_CONTEXT =
                or(withAncestors(ArendRefIdentifier::class.java, ArendLongName::class.java, ArendLiteral::class.java, ArendAtom::class.java, ArendAtomFieldsAcc::class.java, ArendReturnExpr::class.java),
                        withAncestors(ArendRefIdentifier::class.java, ArendLongName::class.java, ArendLiteral::class.java, ArendAtom::class.java, ArendAtomFieldsAcc::class.java, ArendArgumentAppExpr::class.java, ArendNewExpr::class.java, ArendReturnExpr::class.java))

        val EXPRESSION_CONTEXT = and(or(
                withAncestors(ArendRefIdentifier::class.java, ArendLongName::class.java, ArendLiteral::class.java, ArendAtom::class.java),
                withParentOrGrandParent(ArendFunctionBody::class.java),
                and(withParentOrGrandParent(ArendExpr::class.java), not(INSIDE_RETURN_EXPR_CONTEXT)),
                withAncestors(PsiErrorElement::class.java, ArendClause::class.java),
                withAncestors(PsiErrorElement::class.java, ArendTupleExpr::class.java),
                and(afterLeaf(LPAREN), withAncestors(PsiErrorElement::class.java, ArendReturnExpr::class.java)),
                and(afterLeaf(COLON), withAncestors(PsiErrorElement::class.java, ArendDefFunction::class.java)),
                and(afterLeaf(COLON), withParent(ArendDefClass::class.java)),
                or(withParent(ArendClassStat::class.java), withAncestors(PsiErrorElement::class.java, ArendClassStat::class.java)),
                withAncestors(PsiErrorElement::class.java, ArendInstanceBody::class.java, ArendDefInstance::class.java),
                and(ofType(INVALID_KW), afterLeaf(COLON), withParent(ArendNameTele::class.java)),
                and(not(afterLeaf(LPAREN)), not(afterLeaf(ID)), withAncestors(PsiErrorElement::class.java, ArendFieldTele::class.java))),
                not(or(afterLeaf(PIPE), afterLeaf(COWITH_KW)))) // no expression keywords after pipe
        val CASE_CONTEXT = or(EXPRESSION_CONTEXT, withAncestors(PsiErrorElement::class.java, ArendCaseArg::class.java, ArendCaseExpr::class.java))
        val FIELD_CONTEXT = withAncestors(ArendFieldAcc::class.java, ArendAtomFieldsAcc::class.java)
        val TELE_CONTEXT =
                or(and(withAncestors(PsiErrorElement::class.java, ArendTypeTele::class.java),
                        withGreatGrandParents(ArendClassField::class.java, ArendConstructor::class.java, ArendDefData::class.java, ArendPiExpr::class.java, ArendSigmaExpr::class.java)),
                        withAncestors(ArendRefIdentifier::class.java, ArendLongName::class.java, ArendLiteral::class.java, ArendTypeTele::class.java))
        val FIRST_TYPE_TELE_CONTEXT = and(afterLeaf(ID), withParent(PsiErrorElement::class.java),
                withGrandParents(ArendDefData::class.java, ArendClassField::class.java, ArendConstructor::class.java))

        val DATA_OR_EXPRESSION_CONTEXT = or(DATA_CONTEXT, EXPRESSION_CONTEXT, TELE_CONTEXT, FIRST_TYPE_TELE_CONTEXT)
        val ARGUMENT_EXPRESSION = or(withAncestors(ArendRefIdentifier::class.java, ArendLongName::class.java, ArendLiteral::class.java, ArendAtom::class.java, ArendAtomFieldsAcc::class.java, ArendAtomArgument::class.java),
                withAncestors(PsiErrorElement::class.java, ArendAtomFieldsAcc::class.java, ArendArgumentAppExpr::class.java))
        val LPH_CONTEXT = and(withParent(PsiErrorElement::class.java), withGrandParents(ArendSetUniverseAppExpr::class.java, ArendUniverseAppExpr::class.java, ArendTruncatedUniverseAppExpr::class.java))
        val LPH_LEVEL_CONTEXT = and(withAncestors(PsiErrorElement::class.java, ArendAtomLevelExpr::class.java))
        val ELIM_CONTEXT = and(not(or(afterLeaf(DATA_KW), afterLeaf(FUNCTION_KW), afterLeaf(LEMMA_KW), afterLeaf(COERCE_KW), afterLeaf(TRUNCATED_KW), afterLeaf(COLON))),
                or(EXPRESSION_CONTEXT, TELE_CONTEXT,
                        withAncestors(ArendDefIdentifier::class.java, ArendIdentifierOrUnknown::class.java, ArendNameTele::class.java, ArendDefFunction::class.java),
                        withAncestors(PsiErrorElement::class.java, ArendNameTele::class.java, ArendDefFunction::class.java),
                        withAncestors(PsiErrorElement::class.java, ArendDefData::class.java)))
        val ARGUMENT_EXPRESSION_IN_BRACKETS =
                withAncestors(ArendRefIdentifier::class.java, ArendLongName::class.java, ArendLiteral::class.java, ArendAtom::class.java,
                        ArendAtomFieldsAcc::class.java, ArendArgumentAppExpr::class.java, ArendNewExpr::class.java, ArendTupleExpr::class.java, ArendTuple::class.java,
                        ArendAtom::class.java, ArendAtomFieldsAcc::class.java, ArendAtomArgument::class.java, ArendArgumentAppExpr::class.java)
        val GOAL_IN_COPATTERN = ArendCompletionContributor.withAncestors(ArendLiteral::class.java, ArendAtom::class.java, ArendAtomFieldsAcc::class.java,
                ArendArgumentAppExpr::class.java, ArendNewExpr::class.java, ArendCoClause::class.java)
        val CLASSIFYING_CONTEXT = and(afterLeaf(LPAREN),
                or(withAncestors(ArendDefIdentifier::class.java, ArendFieldDefIdentifier::class.java, ArendFieldTele::class.java, ArendDefClass::class.java),
                        withAncestors(PsiErrorElement::class.java, ArendFieldTele::class.java, ArendDefClass::class.java)))

        private val LEVEL_CONTEXT_0 = withAncestors(ArendRefIdentifier::class.java, ArendLongName::class.java, ArendLiteral::class.java, ArendAtom::class.java,
                ArendAtomFieldsAcc::class.java, ArendArgumentAppExpr::class.java, ArendNewExpr::class.java, ArendReturnExpr::class.java)
        val LEVEL_CONTEXT = or(and(afterLeaf(COLON), or(LEVEL_CONTEXT_0, withAncestors(PsiErrorElement::class.java, ArendDefFunction::class.java), withAncestors(ArendClassStat::class.java, ArendDefClass::class.java), withParent(ArendDefClass::class.java))),
                and(afterLeaf(RETURN_KW), or(LEVEL_CONTEXT_0, withAncestors(PsiErrorElement::class.java, ArendCaseExpr::class.java))))

        // Contribution to LookupElementBuilder
        fun LookupElementBuilder.withPriority(priority: Double): LookupElement = PrioritizedLookupElement.withPriority(this, priority)
    }

    class LoggerCompletionProvider : CompletionProvider<CompletionParameters>() {
        override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
            val text = parameters.position.containingFile.text

            val mn = Math.max(0, parameters.position.node.startOffset - 15)
            val mx = Math.min(text.length, parameters.position.node.startOffset + parameters.position.node.textLength + 15)
            System.out.println("")
            System.out.println("surround text: ${text.substring(mn, mx).replace("\n", "\\n")}")
            System.out.println("kind: " + parameters.position.javaClass + " text: " + parameters.position.text)
            var i = 0
            var pp: PsiElement? = parameters.position
            while (i < 13 && pp != null) {
                System.out.format("kind.parent(%2d): %-40s text: %-50s\n", i, pp.javaClass.simpleName, pp.text)
                pp = pp.parent
                i++
            }
            System.out.println("originalPosition.parent: " + parameters.originalPosition?.parent?.javaClass)
            System.out.println("originalPosition.grandparent: " + parameters.originalPosition?.parent?.parent?.javaClass)
            val jointData = ArendCompletionParameters(parameters)
            System.out.println("prevElement: ${jointData.prevElement} text: ${jointData.prevElement?.text}")
            System.out.println("prevElement.parent: ${jointData.prevElement?.parent?.javaClass}")
            System.out.println("prevElement.grandparent: ${jointData.prevElement?.parent?.parent?.javaClass}")
            System.out.println("nextElement: ${jointData.nextElement} text: ${jointData.nextElement?.text}")
            System.out.println("nextElement.parent: ${jointData.nextElement?.parent?.javaClass}")
            if (parameters.position.parent is PsiErrorElement) System.out.println("errorDescription: " + (parameters.position.parent as PsiErrorElement).errorDescription)
            System.out.println("")
            System.out.flush()
        }
    }

    private open class KeywordCompletionProvider(private val keywords: List<String>, private val tailSpaceNeeded: Boolean = true) : CompletionProvider<CompletionParameters>() {

        open fun insertHandler(keyword: String): InsertHandler<LookupElement> = InsertHandler { insertContext, _ ->
            val document = insertContext.document
            if (tailSpaceNeeded) document.insertString(insertContext.tailOffset, " ") // add tail whitespace
            insertContext.commitDocument()
            insertContext.editor.caretModel.moveToOffset(insertContext.tailOffset)
        }

        open fun lookupElement(keyword: String): LookupElementBuilder = LookupElementBuilder.create(keyword)

        open fun computePrefix(parameters: CompletionParameters, resultSet: CompletionResultSet): String {
            var prefix = resultSet.prefixMatcher.prefix
            val lastInvalidIndex = prefix.mapIndexedNotNull { i, c -> if (!ArendWordScanner.isArendIdentifierPart(c)) i else null }.lastOrNull()
            if (lastInvalidIndex != null) prefix = prefix.substring(lastInvalidIndex + 1, prefix.length)
            val pos = parameters.offset - prefix.length - 1
            if (pos >= 0 && pos < parameters.originalFile.textLength)
                prefix = (if (parameters.originalFile.text[pos] == '\\') "\\" else "") + prefix
            return prefix
        }

        override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, resultSet: CompletionResultSet) {
            if (ofTypeK(PsiComment::class.java).accepts(parameters.position) || // Prevents showing kw completions in comments
                    afterLeaf(DOT).accepts(parameters.position))                // Prevents showing kw completions after dot expression
                return

            val prefix = computePrefix(parameters, resultSet)

            val prefixMatcher = object : PlainPrefixMatcher(prefix) {
                override fun prefixMatches(name: String): Boolean = isStartMatch(name)
            }

            for (keyword in keywords)
                resultSet.withPrefixMatcher(prefixMatcher).addElement(lookupElement(keyword).withInsertHandler(insertHandler(keyword)).withPriority(KEYWORD_PRIORITY))
        }
    }

    private open class ConditionalProvider(keywords: List<String>, val condition: (CompletionParameters) -> Boolean, tailSpaceNeeded: Boolean = true):
            KeywordCompletionProvider(keywords, tailSpaceNeeded) {
        override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, resultSet: CompletionResultSet) {
            if (condition.invoke(parameters)) {
                super.addCompletions(parameters, context, resultSet)
            }
        }
    }

    private open class ACPConditionalProvider(keywords: List<String>, condition: (ArendCompletionParameters) -> Boolean, tailSpaceNeeded: Boolean = true)
        : ConditionalProvider(keywords, { parameters ->  condition.invoke(ArendCompletionParameters(parameters)) }, tailSpaceNeeded)

    private open class JointOfStatementsProvider(keywords: List<String>, additionalCondition: (ArendCompletionParameters) -> Boolean = { true }, tailSpaceNeeded: Boolean = true, noCrlfRequired: Boolean = false, allowInsideBraces: Boolean = true) :
            ACPConditionalProvider(keywords, { parameters ->
                val leftSideOk = (parameters.leftStatement == null || parameters.leftBrace && allowInsideBraces) && !parameters.isBeforeClassFields
                val rightSideOk = (parameters.rightStatement == null || parameters.rightBrace && !parameters.leftBrace)
                val correctStatements = leftSideOk || rightSideOk || parameters.betweenStatementsOk

                (parameters.delimiterBeforeCaret || noCrlfRequired) && additionalCondition.invoke(parameters) && correctStatements
            }, tailSpaceNeeded)

    private class ArendCompletionParameters(val completionParameters: CompletionParameters) {
        val prevElement: PsiElement?
        val delimiterBeforeCaret: Boolean
        val nextElement: PsiElement?
        val ancestorsNE: List<PsiElement>
        val ancestorsPE: List<PsiElement>
        val leftBrace: Boolean
        val rightBrace: Boolean
        val leftStatement: PsiElement?
        val rightStatement: PsiElement?
        val isBeforeClassFields: Boolean
        val betweenStatementsOk: Boolean

        init {
            val caretOffset = completionParameters.offset
            val file = completionParameters.originalFile

            var ofs = 0
            var next: PsiElement?
            var prev: PsiElement?
            var delimiter = false
            var skippedFirstErrorExpr: PsiElement? = null
            do {
                val pos = caretOffset + (ofs++)
                next = if (pos > file.textLength) null else file.findElementAt(pos)
            } while (next is PsiWhiteSpace || next is PsiComment)
            ofs = -1

            do {
                val pos = caretOffset + (ofs--)
                prev = if (pos < 0) null else file.findElementAt(pos)
                delimiter = delimiter || (prev is PsiWhiteSpace && textBeforeCaret(prev, caretOffset).contains('\n')) || (pos <= 0)
                var skipFirstErrorExpr = (prev?.node?.elementType == BAD_CHARACTER || (prev?.node?.elementType == INVALID_KW &&
                        prev?.parent is PsiErrorElement && prev.text.startsWith("\\")))
                if (skipFirstErrorExpr && skippedFirstErrorExpr != null && skippedFirstErrorExpr != prev) skipFirstErrorExpr = false else skippedFirstErrorExpr = prev
            } while (prev is PsiWhiteSpace || prev is PsiComment || skipFirstErrorExpr)

            delimiterBeforeCaret = delimiter
            nextElement = next
            prevElement = prev

            val statementCondition = { psi: PsiElement ->
                if (psi is ArendStatement) {
                    val p = psi.parent
                    !(p is ArendWhere && p.lbrace == null)
                } else psi is ArendClassStat
            }

            ancestorsNE = ancestorsUntil(statementCondition, next)
            ancestorsPE = ancestorsUntil(statementCondition, prev)

            leftBrace = prev?.node?.elementType == LBRACE && parentIsStatementHolder(prev)
            rightBrace = nextElement?.node?.elementType == RBRACE && parentIsStatementHolder(nextElement)
            leftStatement = ancestorsPE.lastOrNull()
            rightStatement = ancestorsNE.lastOrNull()
            isBeforeClassFields = rightStatement is ArendClassStat && rightStatement.definition == null
            betweenStatementsOk = leftStatement != null && rightStatement != null && !isBeforeClassFields && ancestorsNE.intersect(ancestorsPE).isEmpty()
        }

        companion object {
            fun textBeforeCaret(whiteSpace: PsiWhiteSpace, caretOffset: Int): String = when {
                whiteSpace.textRange.contains(caretOffset) -> whiteSpace.text.substring(0, caretOffset - whiteSpace.textRange.startOffset)
                caretOffset < whiteSpace.textRange.startOffset -> ""
                else -> whiteSpace.text
            }

            fun parentIsStatementHolder(p: PsiElement?) = when (p?.parent) {
                is ArendWhere -> true
                is ArendDefClass -> (p.parent as ArendDefClass).fatArrow == null
                else -> false
            }

            fun ancestorsUntil(condition: (PsiElement) -> Boolean, element: PsiElement?): List<PsiElement> {
                val ancestors = ArrayList<PsiElement>()
                var elem: PsiElement? = element
                if (elem != null) ancestors.add(elem)
                while (elem != null && !condition.invoke(elem)) {
                    elem = elem.parent
                    if (elem != null) ancestors.add(elem)
                }
                return ancestors
            }
        }
    }
}
