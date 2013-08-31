package com.facebook.presto.benchmark;

import com.facebook.presto.block.BlockIterable;
import com.facebook.presto.noperator.DriverOperator;
import com.facebook.presto.noperator.NewAlignmentOperator.NewAlignmentOperatorFactory;
import com.facebook.presto.noperator.NewFilterAndProjectOperator.NewFilterAndProjectOperatorFactory;
import com.facebook.presto.operator.FilterFunction;
import com.facebook.presto.operator.Operator;
import com.facebook.presto.serde.BlocksFileEncoding;
import com.facebook.presto.spi.RecordCursor;
import com.facebook.presto.tpch.TpchBlocksProvider;
import com.facebook.presto.tuple.TupleInfo.Type;
import com.facebook.presto.tuple.TupleReadable;

import static com.facebook.presto.operator.ProjectionFunctions.singleColumn;

public class NewPredicateFilterBenchmark
        extends AbstractOperatorBenchmark
{
    public NewPredicateFilterBenchmark(TpchBlocksProvider tpchBlocksProvider)
    {
        super(tpchBlocksProvider, "predicate_filter", 5, 50);
    }

    @Override
    protected Operator createBenchmarkedOperator()
    {
        BlockIterable blockIterable = getBlockIterable("orders", "totalprice", BlocksFileEncoding.RAW);
        NewAlignmentOperatorFactory alignmentOperator = new NewAlignmentOperatorFactory(blockIterable);
        NewFilterAndProjectOperatorFactory filterAndProjectOperator = new NewFilterAndProjectOperatorFactory(new DoubleFilter(50000.00), singleColumn(Type.DOUBLE, 0, 0));

        return new DriverOperator(alignmentOperator, filterAndProjectOperator);
    }

    public static class DoubleFilter implements FilterFunction {

        private final double minValue;

        public DoubleFilter(double minValue)
        {
            this.minValue = minValue;
        }

        @Override
        public boolean filter(TupleReadable... cursors)
        {
            return cursors[0].getDouble(0) >= minValue;
        }

        @Override
        public boolean filter(RecordCursor cursor)
        {
            return cursor.getDouble(0) >= minValue;
        }
    }

    public static void main(String[] args)
    {
        new NewPredicateFilterBenchmark(DEFAULT_TPCH_BLOCKS_PROVIDER).runBenchmark(
                new SimpleLineBenchmarkResultWriter(System.out)
        );
    }
}