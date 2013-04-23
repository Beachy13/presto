package com.facebook.presto.sql.analyzer;

import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.sql.ExpressionFormatter;
import com.facebook.presto.sql.tree.AliasedExpression;
import com.facebook.presto.sql.tree.ArithmeticExpression;
import com.facebook.presto.sql.tree.AstVisitor;
import com.facebook.presto.sql.tree.ComparisonExpression;
import com.facebook.presto.sql.tree.CurrentTime;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.FunctionCall;
import com.facebook.presto.sql.tree.Literal;
import com.facebook.presto.sql.tree.LogicalBinaryExpression;
import com.facebook.presto.sql.tree.NegativeExpression;
import com.facebook.presto.sql.tree.NotExpression;
import com.facebook.presto.sql.tree.QualifiedName;
import com.facebook.presto.sql.tree.QualifiedNameReference;
import com.facebook.presto.sql.tree.SortItem;
import com.facebook.presto.sql.tree.Window;
import com.facebook.presto.sql.tree.WindowFrame;
import com.facebook.presto.util.IterableTransformer;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import java.util.List;

import static com.facebook.presto.sql.analyzer.FieldOrExpression.expressionGetter;
import static com.facebook.presto.sql.analyzer.FieldOrExpression.fieldGetter;
import static com.facebook.presto.sql.analyzer.SemanticErrorCode.*;
import static com.google.common.base.Predicates.equalTo;
import static com.google.common.base.Predicates.instanceOf;

/**
 * Checks whether an expression is constant with respect to the group
 */
public class AggregationAnalyzer
{
    private final List<Field> fields;
    private final List<Expression> expressions;
    private final Metadata metadata;

    private final Scope scope;

    public AggregationAnalyzer(List<FieldOrExpression> groupByExpressions, Metadata metadata, Scope scope)
    {
        Preconditions.checkNotNull(groupByExpressions, "groupByExpressions is null");
        Preconditions.checkNotNull(metadata, "metadata is null");
        Preconditions.checkNotNull(scope, "scope is null");

        this.scope = scope;
        this.metadata = metadata;

        this.expressions = IterableTransformer.on(groupByExpressions)
                .transform(expressionGetter())
                .select(isPresentPredicate())
                .transform(AggregationAnalyzer.<Expression>optionalGetter())
                .list();


        ImmutableList.Builder<Field> fields = ImmutableList.builder();

        fields.addAll(IterableTransformer.on(groupByExpressions)
                .transform(fieldGetter())
                .select(isPresentPredicate())
                .transform(AggregationAnalyzer.<Field>optionalGetter())
                .all());


        // For a query like "SELECT * FROM T GROUP BY a", groupByExpressions will contain "a",
        // and the '*' will be expanded to Field references. Therefore we translate all simple name expressions
        // in the group by clause to fields they reference so that the expansion from '*' can be matched against them
        for (Expression expression : Iterables.filter(expressions, instanceOf(QualifiedNameReference.class))) {
            QualifiedName name = ((QualifiedNameReference) expression).getName();

            Field field = Iterables.getOnlyElement(scope.resolve(name), null);
            if (field != null) {
                fields.add(field);
            }
        }

        this.fields = fields.build();
    }

    public boolean analyze(Field field)
    {
        return Iterables.any(fields, equalTo(field));
    }

    public void analyze(Expression expression)
    {
        if (!expression.accept(new Visitor(), null)) {
            throw new SemanticException(MUST_BE_AGGREGATE_OR_GROUP_BY, expression, "'%s' must be an aggregate expression or appear in GROUP BY clause", ExpressionFormatter.toString(expression));
        }
    }

    private class Visitor
            extends AstVisitor<Boolean, Void>
    {
        private Predicate<Expression> isConstantPredicate()
        {
            return new Predicate<Expression>()
            {
                @Override
                public boolean apply(Expression input)
                {
                    return input.accept(Visitor.this, null);
                }
            };
        }

        @Override
        protected Boolean visitExpression(Expression node, Void context)
        {
            throw new UnsupportedOperationException("aggregation analysis not yet implemented for: " + getClass().getName());
        }

        @Override
        protected Boolean visitCurrentTime(CurrentTime node, Void context)
        {
            return true;
        }

        @Override
        protected Boolean visitArithmeticExpression(ArithmeticExpression node, Void context)
        {
            return isInGroupBy(node) || Iterables.all(ImmutableList.of(node.getLeft(), node.getRight()), isConstantPredicate());
        }

        @Override
        protected Boolean visitComparisonExpression(ComparisonExpression node, Void context)
        {
            return isInGroupBy(node) || Iterables.all(ImmutableList.of(node.getLeft(), node.getRight()), isConstantPredicate());
        }

        @Override
        protected Boolean visitLiteral(Literal node, Void context)
        {
            return true;
        }

        @Override
        protected Boolean visitFunctionCall(FunctionCall node, Void context)
        {
            if (!node.getWindow().isPresent() && metadata.isAggregationFunction(node.getName())) {
                AggregateExtractor aggregateExtractor = new AggregateExtractor(metadata);
                WindowFunctionExtractor windowExtractor = new WindowFunctionExtractor();

                for (Expression argument : node.getArguments()) {
                    aggregateExtractor.process(argument, null);
                    windowExtractor.process(argument, null);
                }

                if (!aggregateExtractor.getAggregates().isEmpty()) {
                    throw new SemanticException(NESTED_AGGREGATION,
                            node,
                            "Cannot nest aggregations inside aggregation '%s': %s",
                            node.getName(),
                            Iterables.transform(aggregateExtractor.getAggregates(), ExpressionFormatter.expressionFormatterFunction()));
                }

                if (!windowExtractor.getWindowFunctions().isEmpty()) {
                    throw new SemanticException(NESTED_WINDOW,
                            node,
                            "Cannot nest window functions inside aggregation '%s': %s",
                            node.getName(),
                            Iterables.transform(windowExtractor.getWindowFunctions(), ExpressionFormatter.expressionFormatterFunction()));
                }

                return true;
            }

            if (node.getWindow().isPresent() && !node.getWindow().get().accept(this, null)) {
                return false;
            }

            return isInGroupBy(node)
                    || Iterables.all(node.getArguments(), isConstantPredicate());
        }

        @Override
        public Boolean visitWindow(Window node, Void context)
        {
            for (Expression expression : node.getPartitionBy()) {
                if (!expression.accept(this, context)) {
                    throw new SemanticException(MUST_BE_AGGREGATE_OR_GROUP_BY,
                            expression,
                            "PARTITION BY expression '%s' must be an aggregate expression or appear in GROUP BY clause",
                            ExpressionFormatter.toString(expression));
                }
            }

            for (SortItem sortItem : node.getOrderBy()) {
                Expression expression = sortItem.getSortKey();
                if (!expression.accept(this, context)) {
                    throw new SemanticException(MUST_BE_AGGREGATE_OR_GROUP_BY,
                            expression,
                            "ORDER BY expression '%s' must be an aggregate expression or appear in GROUP BY clause",
                            ExpressionFormatter.toString(expression));
                }
            }

            if (node.getFrame().isPresent()) {
                node.getFrame().get().accept(this, null);
            }

            return true;
        }

        @Override
        public Boolean visitWindowFrame(WindowFrame node, Void context)
        {
            Optional<Expression> start = node.getStart().getValue();
            if (start.isPresent()) {
                if (!start.get().accept(this, context)) {
                    throw new SemanticException(MUST_BE_AGGREGATE_OR_GROUP_BY, start.get(), "Window frame start must be an aggregate expression or appear in GROUP BY clause");
                }
            }
            if (node.getEnd().isPresent()) {
                Optional<Expression> end = node.getEnd().get().getValue();
                if (!end.get().accept(this, context)) {
                    throw new SemanticException(MUST_BE_AGGREGATE_OR_GROUP_BY, start.get(), "Window frame end must be an aggregate expression or appear in GROUP BY clause");
                }
            }

            return true;
        }

        @Override
        protected Boolean visitAliasedExpression(AliasedExpression node, Void context)
        {
            return node.getExpression().accept(this, context);
        }

        @Override
        protected Boolean visitQualifiedNameReference(QualifiedNameReference node, Void context)
        {
            Field field = Iterables.getOnlyElement(scope.resolve(node.getName()), null);
            return field != null && fields.contains(field);
        }

        @Override
        protected Boolean visitNegativeExpression(NegativeExpression node, Void context)
        {
            return isInGroupBy(node) || node.getValue().accept(this, null);
        }

        @Override
        protected Boolean visitNotExpression(NotExpression node, Void context)
        {
            return isInGroupBy(node) || node.getValue().accept(this, null);
        }

        @Override
        protected Boolean visitLogicalBinaryExpression(LogicalBinaryExpression node, Void context)
        {
            return isInGroupBy(node) || Iterables.all(ImmutableList.of(node.getLeft(), node.getRight()), isConstantPredicate());
        }

        private boolean isInGroupBy(Expression expression)
        {
            return Iterables.any(expressions, Predicates.<Expression>equalTo(expression));
        }
    }

    public static Predicate<Optional<?>> isPresentPredicate()
    {
        return new Predicate<Optional<?>>()
        {
            @Override
            public boolean apply(Optional<?> input)
            {
                return input.isPresent();
            }
        };
    }

    public static <T> Function<Optional<T>, T> optionalGetter()
    {
        return new Function<Optional<T>, T>()
        {
            @Override
            public T apply(Optional<T> input)
            {
                return input.get();
            }
        };
    }
}