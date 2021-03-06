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
package com.facebook.presto.operator.aggregation;

import io.airlift.slice.Slice;

import static com.google.common.base.Preconditions.checkArgument;
import static io.airlift.slice.SizeOf.SIZE_OF_DOUBLE;
import static io.airlift.slice.SizeOf.SIZE_OF_LONG;

/**
 * Generate the variance for a given set of values. This implements the
 * <a href="http://en.wikipedia.org/wiki/Algorithms_for_calculating_variance#Online_algorithm">online algorithm</a>.
 */
public class OnlineVarianceCalculator
{
    private static final int COUNT_OFFSET = 0;
    private static final int MEAN_OFFSET = SIZE_OF_LONG;
    private static final int M2_OFFSET = SIZE_OF_LONG + SIZE_OF_DOUBLE;

    private long count;
    private double m2;
    private double mean;

    public void add(double value)
    {
        count++;
        double delta = value - mean;
        mean += delta / count;
        m2 += delta * (value - mean);
    }

    public void merge(OnlineVarianceCalculator other)
    {
        merge(other.getCount(), other.getMean(), other.getM2());
    }

    public void merge(long count, double mean, double m2)
    {
        checkArgument(count >= 0, "count is negative");
        if (count == 0) {
            return;
        }
        long newCount = count + this.count;
        double newMean = ((count * mean) + (this.count * this.mean)) / (double) newCount;
        double delta = mean - this.mean;
        this.m2 += m2 + delta * delta * count * this.count / (double) newCount;
        this.count = newCount;
        this.mean = newMean;
    }

    public int sizeOf()
    {
        return SIZE_OF_LONG + 2 * SIZE_OF_DOUBLE;
    }

    public long getCount()
    {
        return count;
    }

    public double getM2()
    {
        return m2;
    }

    public double getMean()
    {
        return mean;
    }

    public double getSampleVariance()
    {
        return m2 / (double) (count - 1);
    }

    public double getPopulationVariance()
    {
        return m2 / (double) count;
    }

    public void reinitialize(long count, double mean, double m2)
    {
        this.count = count;
        this.mean = mean;
        this.m2 = m2;
    }

    public void serializeTo(Slice slice, int offset)
    {
        slice.setLong(offset + COUNT_OFFSET, count);
        slice.setDouble(offset + MEAN_OFFSET, mean);
        slice.setDouble(offset + M2_OFFSET, m2);
    }

    public void deserializeFrom(Slice slice, int offset)
    {
        count = slice.getLong(offset + COUNT_OFFSET);
        mean = slice.getDouble(offset + MEAN_OFFSET);
        m2 = slice.getDouble(offset + M2_OFFSET);
    }
}
