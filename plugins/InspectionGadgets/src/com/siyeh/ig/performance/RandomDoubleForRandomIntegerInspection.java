package com.siyeh.ig.performance;

import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.tree.IElementType;
import com.intellij.util.IncorrectOperationException;
import com.siyeh.ig.BaseInspectionVisitor;
import com.siyeh.ig.ExpressionInspection;
import com.siyeh.ig.GroupNames;
import com.siyeh.ig.InspectionGadgetsFix;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class RandomDoubleForRandomIntegerInspection extends ExpressionInspection {
    private final RandomDoubleForRandomIntegerFix fix = new RandomDoubleForRandomIntegerFix();

    public String getID(){
        return "UsingRandomNextDoubleForRandomInteger";
    }

    public String getDisplayName() {
        return "Using Random.nextDouble() to get random integer";
    }

    public String getGroupDisplayName() {
        return GroupNames.PERFORMANCE_GROUP_NAME;
    }

    public String buildErrorString(PsiElement location) {
        return "Using Random.#ref to create random integer #loc";
    }

    public InspectionGadgetsFix buildFix(PsiElement location) {
        return fix;
    }

    private static class RandomDoubleForRandomIntegerFix extends InspectionGadgetsFix {
        public String getName() {
            return "replace with .nextInt()";
        }

        public void doFix(Project project, ProblemDescriptor descriptor)
                                                                         throws IncorrectOperationException{

            final PsiIdentifier name = (PsiIdentifier) descriptor.getPsiElement();
            final PsiReferenceExpression expression = (PsiReferenceExpression) name.getParent();
            final PsiExpression call = (PsiExpression) expression.getParent();
            final PsiExpression qualifier = expression.getQualifierExpression();
            final String qualifierText = qualifier.getText();
            final PsiBinaryExpression multiplication = (PsiBinaryExpression) getContainingExpression(call);
            final PsiExpression cast = getContainingExpression(multiplication);
            final PsiExpression multiplierExpression;
            assert multiplication != null;
            if(multiplication.getLOperand().equals(call))
            {
                multiplierExpression = multiplication.getROperand();
            }
            else
            {
                multiplierExpression = multiplication.getLOperand();
            }
            assert multiplierExpression != null;
            final String multiplierText = multiplierExpression.getText();
            replaceExpression(cast, qualifierText + ".nextInt((int) " + multiplierText + ')');
        }
    }

    public BaseInspectionVisitor buildVisitor() {
        return new StringEqualsEmptyStringVisitor();
    }

    private static class StringEqualsEmptyStringVisitor extends BaseInspectionVisitor {

        public void visitMethodCallExpression(@NotNull PsiMethodCallExpression call) {
            super.visitMethodCallExpression(call);
            final PsiReferenceExpression methodExpression = call.getMethodExpression();
            final String methodName = methodExpression.getReferenceName();
            if (!"nextDouble".equals(methodName)) {
                return;
            }
            final PsiMethod method = call.resolveMethod();
            if(method == null)
            {
                return;
            }
            final PsiClass containingClass = method.getContainingClass();
            if(containingClass == null)
            {
                return;
            }
            final String className = containingClass.getQualifiedName();
            if(!"java.util.Random".equals(className))
            {
               return;
            }
            final PsiExpression possibleMultiplierExpression
                    = getContainingExpression(call);

            if(!isMultiplier(possibleMultiplierExpression))
            {
                return;
            }
            final PsiExpression possibleIntCastExpression
                    = getContainingExpression(possibleMultiplierExpression);

            if(!isIntCast(possibleIntCastExpression))
            {
                return;
            }
            registerMethodCallError(call);
        }

        private boolean isMultiplier(PsiExpression expression){
            if(expression == null)
            {
                return false;
            }
            if(!(expression instanceof PsiBinaryExpression)){
                return false;
            }
            final PsiBinaryExpression binaryExpression =
                    (PsiBinaryExpression) expression;
            final PsiJavaToken sign = binaryExpression.getOperationSign();
            final IElementType tokenType = sign.getTokenType();
            return JavaTokenType.ASTERISK.equals(tokenType);
        }
        private boolean isIntCast(PsiExpression expression){
            if(expression == null)
            {
                return false;
            }
            if(!(expression instanceof PsiTypeCastExpression)){
                return false;
            }
            final PsiTypeCastExpression castExpression =
                    (PsiTypeCastExpression) expression;
            final PsiType type = castExpression.getType();

            return PsiType.INT.equals(type);
        }
    }

        private static @Nullable PsiExpression getContainingExpression(PsiExpression exp){
            PsiElement ancestor = exp.getParent();
            while(true)
            {
                if(ancestor == null)
                {
                    return null;
                }

                if(!(ancestor instanceof PsiExpression)){
                    return null;
                }
                if(!(ancestor instanceof PsiParenthesizedExpression)){
                    return (PsiExpression) ancestor;
                }
                ancestor = ancestor.getParent();
            }


    }

}
