package org.arend.psi

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import org.arend.ArendLanguage
import org.arend.parser.ParserMixin.DOC_COMMENT
import org.arend.parser.ParserMixin.DOC_TEXT
import org.arend.psi.ArendElementTypes.*

class ArendTokenType(debugName: String) : IElementType(debugName, ArendLanguage.INSTANCE)

val AREND_KEYWORDS: TokenSet = TokenSet.create(
        OPEN_KW, IMPORT_KW, USING_KW, AS_KW, HIDING_KW, FUNC_KW, SFUNC_KW, LEMMA_KW, TYPE_KW, CONS_KW, AXIOM_KW,
        STRICT_KW, CLASSIFYING_KW, NO_CLASSIFYING_KW, FIELD_KW, PROPERTY_KW, DEFAULT_KW, OVERRIDE_KW,
        NON_ASSOC_KW, LEFT_ASSOC_KW, RIGHT_ASSOC_KW, INFIX_NON_KW, INFIX_LEFT_KW, INFIX_RIGHT_KW, ALIAS_KW,
        PROP_KW, WHERE_KW, WITH_KW, USE_KW, COWITH_KW, ELIM_KW, NEW_KW, PI_KW, SIGMA_KW, LAM_KW, HAVE_KW, HAVES_KW, LET_KW, LETS_KW,
        IN_KW, CASE_KW, SCASE_KW, DATA_KW, CLASS_KW, RECORD_KW, MODULE_KW, META_KW, EXTENDS_KW,
        RETURN_KW, COERCE_KW, INSTANCE_KW, TRUNCATED_KW,
        LP_KW, LH_KW, OO_KW, SUC_KW, MAX_KW, LEVEL_KW, LEVELS_KW, PLEVELS_KW, HLEVELS_KW,
        SET, UNIVERSE, TRUNCATED_UNIVERSE, THIS_KW,
        EVAL_KW, PEVAL_KW, BOX_KW, PRIVATE_KW, PROTECTED_KW
)

val AREND_COMMENTS: TokenSet = TokenSet.create(LINE_COMMENT, BLOCK_COMMENT, DOC_COMMENT, DOC_TEXT)

val AREND_NAMES: TokenSet = TokenSet.create(ID, INFIX, POSTFIX)

val AREND_WHITE_SPACES: TokenSet = TokenSet.create(TokenType.WHITE_SPACE)

val AREND_STRINGS: TokenSet = TokenSet.create(STRING)
