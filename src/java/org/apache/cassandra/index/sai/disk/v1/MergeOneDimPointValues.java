/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.index.sai.disk.v1;

import java.io.IOException;
import java.util.List;

import com.google.common.annotations.VisibleForTesting;

import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.index.sai.disk.v1.kdtree.BKDReader;
import org.apache.cassandra.index.sai.disk.v1.kdtree.MutableOneDimPointValues;
import org.apache.cassandra.index.sai.utils.TypeUtil;
import org.apache.lucene.util.PriorityQueue;
import org.apache.lucene.util.bkd.BKDWriter;

/**
 * {@link MutableOneDimPointValues} that prevents buffered points from reordering, and always skips sorting phase in Lucene
 * It's the responsibility of the underlying implementation to ensure that all points are correctly sorted.
 * <p>
 * It allows to take advantage of an optimised 1-dim writer {@link BKDWriter}
 * (that is enabled only for {@link MutableOneDimPointValues}), and reduce number of times we sort point values.
 */
public class MergeOneDimPointValues extends MutableOneDimPointValues
{
    private final MergeQueue queue;

    public final int bytesPerDim;

    public long minRowID = Long.MAX_VALUE;
    public long maxRowID = Long.MIN_VALUE;
    public long numRows = 0;


    public MergeOneDimPointValues(List<BKDReader.IteratorState> iterators, AbstractType termComparator)
    {
        this(iterators, TypeUtil.fixedSizeOf(termComparator));
    }

    public MergeOneDimPointValues(List<BKDReader.IteratorState> iterators, int bytesPerDim)
    {
        queue = new MergeQueue(iterators.size());
        this.bytesPerDim = bytesPerDim;
        for (BKDReader.IteratorState iterator : iterators)
        {
            if (iterator.hasNext())
            {
                queue.add(iterator);
            }
            else
            {
                iterator.close();
            }
        }
    }

    public long getMinRowID()
    {
        return minRowID;
    }

    public long getMaxRowID()
    {
        return maxRowID;
    }

    public long getNumRows()
    {
        return numRows;
    }

    @Override
    @SuppressWarnings("resource")
    public void intersect(IntersectVisitor visitor) throws IOException
    {
        try
        {
            while (queue.size() != 0)
            {
                final BKDReader.IteratorState reader = queue.top();
                final long rowID = reader.next();

                minRowID = Math.min(minRowID, rowID);
                maxRowID = Math.max(maxRowID, rowID);
                numRows++;

                visitor.visit(rowID, reader.scratch);

                if (reader.hasNext())
                {
                    queue.updateTop();
                }
                else
                {
                    queue.pop().close();
                }
            }
        }
        finally
        {
            while (queue.size() != 0)
            {
                queue.pop().close();
            }
        }
    }

    @Override
    public int getBytesPerDimension()
    {
        return bytesPerDim;
    }

    private static class MergeQueue extends PriorityQueue<BKDReader.IteratorState>
    {
        public MergeQueue(int maxSize)
        {
            super(maxSize);
        }

        @Override
        public boolean lessThan(BKDReader.IteratorState a, BKDReader.IteratorState b)
        {
            assert a != b;

            int cmp = a.compareTo(b);

            if (cmp < 0)
            {
                return true;
            }
            else return false;
        }
    }
}
