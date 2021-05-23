/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.sql.planner.assertions;

import io.airlift.slice.Slice;
import io.prestosql.Session;
import io.prestosql.metadata.Metadata;
import io.prestosql.spi.function.FunctionMetadata;
import io.prestosql.spi.function.OperatorType;
import io.prestosql.spi.plan.Symbol;
import io.prestosql.spi.relation.CallExpression;
import io.prestosql.spi.relation.ConstantExpression;
import io.prestosql.spi.relation.InputReferenceExpression;
import io.prestosql.spi.relation.RowExpression;
import io.prestosql.spi.relation.SpecialForm;
import io.prestosql.spi.relation.VariableReferenceExpression;
import io.prestosql.spi.type.RowType;
import io.prestosql.sql.planner.LiteralInterpreter;
import io.prestosql.sql.relational.FunctionResolution;
import io.prestosql.sql.tree.ArithmeticBinaryExpression;
import io.prestosql.sql.tree.AstVisitor;
import io.prestosql.sql.tree.BetweenPredicate;
import io.prestosql.sql.tree.BooleanLiteral;
import io.prestosql.sql.tree.Cast;
import io.prestosql.sql.tree.CoalesceExpression;
import io.prestosql.sql.tree.ComparisonExpression;
import io.prestosql.sql.tree.DecimalLiteral;
import io.prestosql.sql.tree.DereferenceExpression;
import io.prestosql.sql.tree.DoubleLiteral;
import io.prestosql.sql.tree.FunctionCall;
import io.prestosql.sql.tree.GenericLiteral;
import io.prestosql.sql.tree.InListExpression;
import io.prestosql.sql.tree.InPredicate;
import io.prestosql.sql.tree.IsNotNullPredicate;
import io.prestosql.sql.tree.IsNullPredicate;
import io.prestosql.sql.tree.Literal;
import io.prestosql.sql.tree.LogicalBinaryExpression;
import io.prestosql.sql.tree.LongLiteral;
import io.prestosql.sql.tree.Node;
import io.prestosql.sql.tree.NotExpression;
import io.prestosql.sql.tree.NullLiteral;
import io.prestosql.sql.tree.SimpleCaseExpression;
import io.prestosql.sql.tree.StringLiteral;
import io.prestosql.sql.tree.SymbolReference;
import io.prestosql.sql.tree.TryExpression;
import io.prestosql.sql.tree.WhenClause;

import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkState;
import static io.prestosql.spi.function.OperatorType.ADD;
import static io.prestosql.spi.function.OperatorType.DIVIDE;
import static io.prestosql.spi.function.OperatorType.EQUAL;
import static io.prestosql.spi.function.OperatorType.GREATER_THAN;
import static io.prestosql.spi.function.OperatorType.GREATER_THAN_OR_EQUAL;
import static io.prestosql.spi.function.OperatorType.IS_DISTINCT_FROM;
import static io.prestosql.spi.function.OperatorType.LESS_THAN;
import static io.prestosql.spi.function.OperatorType.LESS_THAN_OR_EQUAL;
import static io.prestosql.spi.function.OperatorType.MODULUS;
import static io.prestosql.spi.function.OperatorType.MULTIPLY;
import static io.prestosql.spi.function.OperatorType.NOT_EQUAL;
import static io.prestosql.spi.function.OperatorType.SUBTRACT;
import static io.prestosql.spi.relation.SpecialForm.Form.COALESCE;
import static io.prestosql.spi.relation.SpecialForm.Form.DEREFERENCE;
import static io.prestosql.spi.relation.SpecialForm.Form.IS_NULL;
import static io.prestosql.spi.relation.SpecialForm.Form.SWITCH;
import static io.prestosql.spi.relation.SpecialForm.Form.WHEN;
import static io.prestosql.spi.type.StandardTypes.VARCHAR;
import static io.prestosql.sql.planner.RowExpressionInterpreter.rowExpressionInterpreter;
import static io.prestosql.sql.tree.LogicalBinaryExpression.Operator.AND;
import static io.prestosql.sql.tree.LogicalBinaryExpression.Operator.OR;
import static java.lang.Math.toIntExact;
import static java.lang.String.format;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;

/**
 * RowExpression visitor which verifies if given expression (actual) is matching other RowExpression given as context (expected).
 */
final class RowExpressionVerifier
        extends AstVisitor<Boolean, RowExpression>
{
    // either use variable or input reference for symbol mapping
    private final SymbolAliases symbolAliases;
    private final Metadata metadata;
    private final Session session;
    private final List<Symbol> symbols;
    private final FunctionResolution functionResolution;

    RowExpressionVerifier(SymbolAliases symbolAliases, Metadata metadata, Session session, List<Symbol> symbols)
    {
        this.symbolAliases = requireNonNull(symbolAliases, "symbolLayout is null");
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.session = requireNonNull(session, "session is null");
        this.symbols = symbols;
        this.functionResolution = new FunctionResolution(metadata.getFunctionAndTypeManager());
    }

    @Override
    protected Boolean visitNode(Node node, RowExpression context)
    {
        throw new IllegalStateException(format("Node %s is not supported", node));
    }

    @Override
    protected Boolean visitTryExpression(TryExpression expected, RowExpression actual)
    {
        if (!(actual instanceof CallExpression) || !functionResolution.isTryFunction(((CallExpression) actual).getFunctionHandle())) {
            return false;
        }

        return process(expected.getInnerExpression(), ((CallExpression) actual).getArguments().get(0));
    }

    @Override
    protected Boolean visitCast(Cast expected, RowExpression actual)
    {
        // TODO: clean up cast path
        if (actual instanceof ConstantExpression && expected.getExpression() instanceof Literal && expected.getType().equals(actual.getType().toString())) {
            Literal literal = (Literal) expected.getExpression();
            if (literal instanceof StringLiteral) {
                Object value = LiteralInterpreter.evaluate((ConstantExpression) actual);
                String actualString = value instanceof Slice ? ((Slice) value).toStringUtf8() : String.valueOf(value);
                return ((StringLiteral) literal).getValue().equals(actualString);
            }
            return getValueFromLiteral(literal).equals(String.valueOf(LiteralInterpreter.evaluate((ConstantExpression) actual)));
        }
        if (actual instanceof VariableReferenceExpression && expected.getExpression() instanceof SymbolReference && expected.getType().equals(actual.getType().toString())) {
            return visitSymbolReference((SymbolReference) expected.getExpression(), actual);
        }
        if (!(actual instanceof CallExpression) || !functionResolution.isCastFunction(((CallExpression) actual).getFunctionHandle())) {
            return false;
        }

        if (!expected.getType().equalsIgnoreCase(actual.getType().toString()) &&
                !(expected.getType().toLowerCase(ENGLISH).equals(VARCHAR) && actual.getType().getTypeSignature().getBase().equals(VARCHAR))) {
            return false;
        }

        return process(expected.getExpression(), ((CallExpression) actual).getArguments().get(0));
    }

    @Override
    protected Boolean visitIsNullPredicate(IsNullPredicate expected, RowExpression actual)
    {
        if (!(actual instanceof SpecialForm) || !((SpecialForm) actual).getForm().equals(IS_NULL)) {
            return false;
        }

        return process(expected.getValue(), ((SpecialForm) actual).getArguments().get(0));
    }

    @Override
    protected Boolean visitIsNotNullPredicate(IsNotNullPredicate expected, RowExpression actual)
    {
        if (!(actual instanceof CallExpression) || !functionResolution.isNotFunction(((CallExpression) actual).getFunctionHandle())) {
            return false;
        }

        RowExpression argument = ((CallExpression) actual).getArguments().get(0);

        if (!(argument instanceof SpecialForm) || !((SpecialForm) argument).getForm().equals(IS_NULL)) {
            return false;
        }

        return process(expected.getValue(), ((SpecialForm) argument).getArguments().get(0));
    }

    @Override
    protected Boolean visitInPredicate(InPredicate expected, RowExpression actual)
    {
        if (actual instanceof SpecialForm && ((SpecialForm) actual).getForm().equals(SpecialForm.Form.IN)) {
            List<RowExpression> arguments = ((SpecialForm) actual).getArguments();
            if (expected.getValueList() instanceof InListExpression) {
                return process(expected.getValue(), arguments.get(0)) && process(((InListExpression) expected.getValueList()).getValues(), arguments.subList(1, arguments.size()));
            }
            else {
                /*
                 * If the actual value is a value list, but the expected is e.g. a SymbolReference,
                 * we need to unpack the value from the list so that when we hit visitSymbolReference, the
                 * actual.toString() call returns something that the symbolAliases expectedly contains.
                 * For example, InListExpression.toString returns "(onlyitem)" rather than "onlyitem".
                 *
                 * This is required because expected passes through the analyzer, planner, and possibly optimizers,
                 * one of which sometimes takes the liberty of unpacking the InListExpression.
                 *
                 * Since the actual value doesn't go through all of that, we have to deal with the case
                 * of the expected value being unpacked, but the actual value being an InListExpression.
                 */
                checkState(arguments.size() == 2, "Multiple expressions in actual value list %s, but expected value is not a list", arguments.subList(1, arguments.size()), expected.getValue());
                return process(expected.getValue(), arguments.get(0)) && process(expected.getValueList(), arguments.get(1));
            }
        }
        return false;
    }

    @Override
    protected Boolean visitComparisonExpression(ComparisonExpression expected, RowExpression actual)
    {
        if (actual instanceof CallExpression) {
            FunctionMetadata functionMetadata = metadata.getFunctionAndTypeManager().getFunctionMetadata(((CallExpression) actual).getFunctionHandle());
            if (!functionMetadata.getOperatorType().isPresent() || !functionMetadata.getOperatorType().get().isComparisonOperator()) {
                return false;
            }
            OperatorType actualOperatorType = functionMetadata.getOperatorType().get();
            OperatorType expectedOperatorType = getOperatorType(expected.getOperator());
            if (expectedOperatorType.equals(actualOperatorType)) {
                if (actualOperatorType == EQUAL) {
                    return (process(expected.getLeft(), ((CallExpression) actual).getArguments().get(0)) && process(expected.getRight(), ((CallExpression) actual).getArguments().get(1)))
                            || (process(expected.getLeft(), ((CallExpression) actual).getArguments().get(1)) && process(expected.getRight(), ((CallExpression) actual).getArguments().get(0)));
                }
                // TODO support other comparison operators
                return process(expected.getLeft(), ((CallExpression) actual).getArguments().get(0)) && process(expected.getRight(), ((CallExpression) actual).getArguments().get(1));
            }
        }
        return false;
    }

    private static OperatorType getOperatorType(ComparisonExpression.Operator operator)
    {
        OperatorType operatorType;
        switch (operator) {
            case EQUAL:
                operatorType = EQUAL;
                break;
            case NOT_EQUAL:
                operatorType = NOT_EQUAL;
                break;
            case LESS_THAN:
                operatorType = LESS_THAN;
                break;
            case LESS_THAN_OR_EQUAL:
                operatorType = LESS_THAN_OR_EQUAL;
                break;
            case GREATER_THAN:
                operatorType = GREATER_THAN;
                break;
            case GREATER_THAN_OR_EQUAL:
                operatorType = GREATER_THAN_OR_EQUAL;
                break;
            case IS_DISTINCT_FROM:
                operatorType = IS_DISTINCT_FROM;
                break;
            default:
                throw new IllegalStateException("Unsupported comparison operator type: " + operator);
        }
        return operatorType;
    }

    @Override
    protected Boolean visitArithmeticBinary(ArithmeticBinaryExpression expected, RowExpression actual)
    {
        if (actual instanceof CallExpression) {
            FunctionMetadata functionMetadata = metadata.getFunctionAndTypeManager().getFunctionMetadata(((CallExpression) actual).getFunctionHandle());
            if (!functionMetadata.getOperatorType().isPresent() || !functionMetadata.getOperatorType().get().isArithmeticOperator()) {
                return false;
            }
            OperatorType actualOperatorType = functionMetadata.getOperatorType().get();
            OperatorType expectedOperatorType = getOperatorType(expected.getOperator());
            if (expectedOperatorType.equals(actualOperatorType)) {
                return process(expected.getLeft(), ((CallExpression) actual).getArguments().get(0)) && process(expected.getRight(), ((CallExpression) actual).getArguments().get(1));
            }
        }
        return false;
    }

    private static OperatorType getOperatorType(ArithmeticBinaryExpression.Operator operator)
    {
        OperatorType operatorType;
        switch (operator) {
            case ADD:
                operatorType = ADD;
                break;
            case SUBTRACT:
                operatorType = SUBTRACT;
                break;
            case MULTIPLY:
                operatorType = MULTIPLY;
                break;
            case DIVIDE:
                operatorType = DIVIDE;
                break;
            case MODULUS:
                operatorType = MODULUS;
                break;
            default:
                throw new IllegalStateException("Unknown arithmetic operator: " + operator);
        }
        return operatorType;
    }

    @Override
    protected Boolean visitGenericLiteral(GenericLiteral expected, RowExpression actual)
    {
        return compareLiteral(expected, actual);
    }

    @Override
    protected Boolean visitLongLiteral(LongLiteral expected, RowExpression actual)
    {
        return compareLiteral(expected, actual);
    }

    @Override
    protected Boolean visitDoubleLiteral(DoubleLiteral expected, RowExpression actual)
    {
        return compareLiteral(expected, actual);
    }

    @Override
    protected Boolean visitDecimalLiteral(DecimalLiteral expected, RowExpression actual)
    {
        return compareLiteral(expected, actual);
    }

    @Override
    protected Boolean visitBooleanLiteral(BooleanLiteral expected, RowExpression actual)
    {
        return compareLiteral(expected, actual);
    }

    @Override
    protected Boolean visitDereferenceExpression(DereferenceExpression expected, RowExpression actual)
    {
        if (!(actual instanceof SpecialForm) || !(((SpecialForm) actual).getForm().equals(DEREFERENCE))) {
            return false;
        }
        SpecialForm actualDereference = (SpecialForm) actual;
        if (actualDereference.getArguments().size() == 2 &&
                actualDereference.getArguments().get(0).getType() instanceof RowType &&
                actualDereference.getArguments().get(1) instanceof ConstantExpression) {
            RowType rowType = (RowType) actualDereference.getArguments().get(0).getType();
            Object value = LiteralInterpreter.evaluate((ConstantExpression) actualDereference.getArguments().get(1));
            checkState(value instanceof Long);
            long index = (Long) value;
            checkState(index >= 0 && index < rowType.getFields().size());
            RowType.Field field = rowType.getFields().get(toIntExact(index));
            checkState(field.getName().isPresent());
            return expected.getField().getValue().equals(field.getName().get()) && process(expected.getBase(), actualDereference.getArguments().get(0));
        }
        return false;
    }

    private static String getValueFromLiteral(Node expression)
    {
        if (expression instanceof LongLiteral) {
            return String.valueOf(((LongLiteral) expression).getValue());
        }
        else if (expression instanceof BooleanLiteral) {
            return String.valueOf(((BooleanLiteral) expression).getValue());
        }
        else if (expression instanceof DoubleLiteral) {
            return String.valueOf(((DoubleLiteral) expression).getValue());
        }
        else if (expression instanceof DecimalLiteral) {
            return String.valueOf(((DecimalLiteral) expression).getValue());
        }
        else if (expression instanceof GenericLiteral) {
            return ((GenericLiteral) expression).getValue();
        }
        else if (expression instanceof NullLiteral) {
            return "null";
        }
        else {
            throw new IllegalArgumentException("Unsupported literal expression type: " + expression.getClass().getName());
        }
    }

    private Boolean compareLiteral(Node expected, RowExpression actual)
    {
        if (actual instanceof CallExpression && functionResolution.isCastFunction(((CallExpression) actual).getFunctionHandle())) {
            if (((CallExpression) actual).getArguments().get(0) instanceof ConstantExpression) {
                return getValueFromLiteral(expected).equals(String.valueOf(LiteralInterpreter.evaluate((ConstantExpression) (((CallExpression) actual).getArguments().get(0)))));
            }
            return getValueFromLiteral(expected).equals(String.valueOf(rowExpressionInterpreter(actual, metadata, session.toConnectorSession()).evaluate()));
        }
        if (actual instanceof ConstantExpression) {
            return getValueFromLiteral(expected).equals(String.valueOf(LiteralInterpreter.evaluate((ConstantExpression) actual)));
        }
        return false;
    }

    @Override
    protected Boolean visitStringLiteral(StringLiteral expected, RowExpression actual)
    {
        if (actual instanceof CallExpression && functionResolution.isCastFunction(((CallExpression) actual).getFunctionHandle())) {
            Object value = rowExpressionInterpreter(actual, metadata, session.toConnectorSession()).evaluate();
            if (value instanceof Slice) {
                return expected.getValue().equals(((Slice) value).toStringUtf8());
            }
        }
        if (actual instanceof ConstantExpression && actual.getType().getJavaType() == Slice.class) {
            String actualString = (String) LiteralInterpreter.evaluate((ConstantExpression) actual);
            return expected.getValue().equals(actualString);
        }
        return false;
    }

    @Override
    protected Boolean visitLogicalBinaryExpression(LogicalBinaryExpression expected, RowExpression actual)
    {
        if (actual instanceof SpecialForm) {
            SpecialForm actualLogicalBinary = (SpecialForm) actual;
            if ((expected.getOperator() == OR && actualLogicalBinary.getForm() == SpecialForm.Form.OR) ||
                    (expected.getOperator() == AND && actualLogicalBinary.getForm() == SpecialForm.Form.AND)) {
                return process(expected.getLeft(), actualLogicalBinary.getArguments().get(0)) &&
                        process(expected.getRight(), actualLogicalBinary.getArguments().get(1));
            }
        }
        return false;
    }

    @Override
    protected Boolean visitBetweenPredicate(BetweenPredicate expected, RowExpression actual)
    {
        if (actual instanceof CallExpression && functionResolution.isBetweenFunction(((CallExpression) actual).getFunctionHandle())) {
            return process(expected.getValue(), ((CallExpression) actual).getArguments().get(0)) &&
                    process(expected.getMin(), ((CallExpression) actual).getArguments().get(1)) &&
                    process(expected.getMax(), ((CallExpression) actual).getArguments().get(2));
        }

        return false;
    }

    @Override
    protected Boolean visitNotExpression(NotExpression expected, RowExpression actual)
    {
        if (!(actual instanceof CallExpression) || !functionResolution.isNotFunction(((CallExpression) actual).getFunctionHandle())) {
            return false;
        }
        return process(expected.getValue(), ((CallExpression) actual).getArguments().get(0));
    }

    @Override
    protected Boolean visitSymbolReference(SymbolReference expected, RowExpression actual)
    {
        if (actual instanceof VariableReferenceExpression) {
            return symbolAliases.get((expected).getName()).getName().equals(((VariableReferenceExpression) actual).getName());
        }
        else if (actual instanceof InputReferenceExpression && ((InputReferenceExpression) actual).getField() < symbols.size()) {
            return symbolAliases.get((expected).getName()).getName().equals(symbols.get(((InputReferenceExpression) actual).getField()).getName());
        }
        return false;
    }

    @Override
    protected Boolean visitCoalesceExpression(CoalesceExpression expected, RowExpression actual)
    {
        if (!(actual instanceof SpecialForm) || !(((SpecialForm) actual).getForm().equals(COALESCE))) {
            return false;
        }

        SpecialForm actualCoalesce = (SpecialForm) actual;
        if (expected.getOperands().size() == actualCoalesce.getArguments().size()) {
            boolean verified = true;
            for (int i = 0; i < expected.getOperands().size(); i++) {
                verified &= process(expected.getOperands().get(i), actualCoalesce.getArguments().get(i));
            }
            return verified;
        }
        return false;
    }

    @Override
    protected Boolean visitSimpleCaseExpression(SimpleCaseExpression expected, RowExpression actual)
    {
        if (!(actual instanceof SpecialForm && ((SpecialForm) actual).getForm().equals(SWITCH))) {
            return false;
        }
        SpecialForm actualCase = (SpecialForm) actual;
        if (!process(expected.getOperand(), actualCase.getArguments().get(0))) {
            return false;
        }

        List<RowExpression> whenClauses;
        Optional<RowExpression> elseValue;
        RowExpression last = actualCase.getArguments().get(actualCase.getArguments().size() - 1);
        if (last instanceof SpecialForm && ((SpecialForm) last).getForm().equals(WHEN)) {
            whenClauses = actualCase.getArguments().subList(1, actualCase.getArguments().size());
            elseValue = Optional.empty();
        }
        else {
            whenClauses = actualCase.getArguments().subList(1, actualCase.getArguments().size() - 1);
            elseValue = Optional.of(last);
        }

        if (!process(expected.getWhenClauses(), whenClauses)) {
            return false;
        }

        return process(expected.getDefaultValue(), elseValue);
    }

    @Override
    protected Boolean visitWhenClause(WhenClause expected, RowExpression actual)
    {
        if (!(actual instanceof SpecialForm && ((SpecialForm) actual).getForm().equals(WHEN))) {
            return false;
        }
        SpecialForm actualWhenClause = (SpecialForm) actual;

        return process(expected.getOperand(), ((SpecialForm) actual).getArguments().get(0)) &&
                process(expected.getResult(), actualWhenClause.getArguments().get(1));
    }

    @Override
    protected Boolean visitFunctionCall(FunctionCall expected, RowExpression actual)
    {
        if (!(actual instanceof CallExpression)) {
            return false;
        }
        CallExpression actualFunction = (CallExpression) actual;

        if (!expected.getName().getSuffix().equalsIgnoreCase(metadata.getFunctionAndTypeManager().getFunctionMetadata(actualFunction.getFunctionHandle()).getName().getObjectName())) {
            return false;
        }

        return process(expected.getArguments(), actualFunction.getArguments());
    }

    @Override
    protected Boolean visitNullLiteral(NullLiteral node, RowExpression actual)
    {
        return actual instanceof ConstantExpression && ((ConstantExpression) actual).getValue() == null;
    }

    private <T extends Node> boolean process(List<T> expecteds, List<RowExpression> actuals)
    {
        if (expecteds.size() != actuals.size()) {
            return false;
        }
        for (int i = 0; i < expecteds.size(); i++) {
            if (!process(expecteds.get(i), actuals.get(i))) {
                return false;
            }
        }
        return true;
    }

    private <T extends Node> boolean process(Optional<T> expected, Optional<RowExpression> actual)
    {
        if (expected.isPresent() != actual.isPresent()) {
            return false;
        }
        if (expected.isPresent()) {
            return process(expected.get(), actual.get());
        }
        return true;
    }
}