package com.siyeh.ig.psiutils;

import com.intellij.psi.*;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class BoolUtils {
    private BoolUtils() {
        super();
    }

    public static boolean isNegation(@NotNull PsiExpression exp) {
        if (!(exp instanceof PsiPrefixExpression)) {
            return false;
        }
        final PsiPrefixExpression prefixExp = (PsiPrefixExpression) exp;
        final PsiJavaToken sign = prefixExp.getOperationSign();
        final IElementType tokenType = sign.getTokenType();
        return JavaTokenType.EXCL.equals(tokenType);
    }

    private static PsiExpression getNegated(@NotNull PsiExpression exp) {
        final PsiPrefixExpression prefixExp = (PsiPrefixExpression) exp;
        final PsiExpression operand = prefixExp.getOperand();
        return ParenthesesUtils.stripParentheses(operand);
    }

    public static String getNegatedExpressionText(@NotNull PsiExpression condition){
        if(condition instanceof PsiParenthesizedExpression)
        {
            final PsiExpression contentExpression = ((PsiParenthesizedExpression) condition).getExpression();
            return '(' +getNegatedExpressionText(contentExpression) + ')';
        }else if(BoolUtils.isNegation(condition)){
            final PsiExpression negated = getNegated(condition);
            return negated.getText();
        } else if(ComparisonUtils.isComparison(condition)){
            final PsiBinaryExpression binaryExpression = (PsiBinaryExpression) condition;
            final PsiJavaToken sign = binaryExpression.getOperationSign();
            final String operator = sign.getText();
            final String negatedComparison = ComparisonUtils.getNegatedComparison(operator);
            final PsiExpression lhs = binaryExpression.getLOperand();
            final PsiExpression rhs = binaryExpression.getROperand();
            assert rhs != null;
            return lhs.getText() + negatedComparison + rhs.getText();
        } else if(ParenthesesUtils.getPrecendence(condition) >
                ParenthesesUtils.PREFIX_PRECEDENCE){
            return "!(" + condition.getText() + ')';
        } else{
            return '!' + condition.getText();
        }

    }

    public static boolean isTrue(@Nullable PsiExpression test) {
        if (test == null) {
            return false;
        }
        final String text = test.getText();
        return "true".equals(text);
    }
}
