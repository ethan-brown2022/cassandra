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
package org.apache.cassandra.repair;

import java.util.UUID;
import java.util.Collections;
import java.util.Collection;

import com.google.common.annotations.VisibleForTesting;
import org.apache.cassandra.locator.RangesAtEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.dht.Range;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.locator.InetAddressAndPort;
import org.apache.cassandra.net.Message;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.repair.messages.SyncResponse;
import org.apache.cassandra.streaming.PreviewKind;
import org.apache.cassandra.streaming.StreamEvent;
import org.apache.cassandra.streaming.StreamEventHandler;
import org.apache.cassandra.streaming.StreamPlan;
import org.apache.cassandra.streaming.StreamResultFuture;
import org.apache.cassandra.streaming.StreamState;
import org.apache.cassandra.streaming.StreamOperation;

import static org.apache.cassandra.net.Verb.SYNC_RSP;

/**
 * StreamingRepairTask performs data streaming between two remote replicas, neither of which is repair coordinator.
 * Task will send {@link SyncResponse} message back to coordinator upon streaming completion.
 */
public class StreamingRepairTask implements StreamEventHandler
{
    private static final Logger logger = LoggerFactory.getLogger(StreamingRepairTask.class);

    private final RepairJobDesc desc;
    private final boolean asymmetric;
    private final InetAddressAndPort initiator;
    private final InetAddressAndPort src;
    private final InetAddressAndPort dst;
    private final Collection<Range<Token>> ranges;
    private final UUID pendingRepair;
    private final PreviewKind previewKind;

    public StreamingRepairTask(RepairJobDesc desc, InetAddressAndPort initiator, InetAddressAndPort src, InetAddressAndPort dst, Collection<Range<Token>> ranges,  UUID pendingRepair, PreviewKind previewKind, boolean asymmetric)
    {
        this.desc = desc;
        this.initiator = initiator;
        this.src = src;
        this.dst = dst;
        this.ranges = ranges;
        this.asymmetric = asymmetric;
        this.pendingRepair = pendingRepair;
        this.previewKind = previewKind;
    }

    public StreamResultFuture execute()
    {
        logger.info("[streaming task #{}] Performing {}streaming repair of {} ranges with {}", desc.sessionId, asymmetric ? "asymmetric " : "", ranges.size(), dst);
        return createStreamPlan(dst).execute();
    }

    @VisibleForTesting
    StreamPlan createStreamPlan(InetAddressAndPort dest)
    {
        StreamPlan sp = new StreamPlan(StreamOperation.REPAIR, 1, false, pendingRepair, previewKind)
               .listeners(this)
               .flushBeforeTransfer(pendingRepair == null) // sstables are isolated at the beginning of an incremental repair session, so flushing isn't neccessary
               // see comment on RangesAtEndpoint.toDummyList for why we synthesize replicas here
               .requestRanges(dest, desc.keyspace, RangesAtEndpoint.toDummyList(ranges),
                       RangesAtEndpoint.toDummyList(Collections.emptyList()), desc.columnFamily); // request ranges from the remote node
        if (!asymmetric)
            // see comment on RangesAtEndpoint.toDummyList for why we synthesize replicas here
            sp.transferRanges(dest, desc.keyspace, RangesAtEndpoint.toDummyList(ranges), desc.columnFamily); // send ranges to the remote node
        return sp;
    }

    public void handleStreamEvent(StreamEvent event)
    {
        // Nothing to do here, all we care about is the final success or failure and that's handled by
        // onSuccess and onFailure
    }

    /**
     * If we succeeded on both stream in and out, respond back to coordinator
     */
    public void onSuccess(StreamState state)
    {
        logger.info("[repair #{}] streaming task succeed, returning response to {}", desc.sessionId, initiator);
        MessagingService.instance().send(Message.out(SYNC_RSP, new SyncResponse(desc, src, dst, true, state.createSummaries())), initiator);
    }

    /**
     * If we failed on either stream in or out, respond fail to coordinator
     */
    public void onFailure(Throwable t)
    {
        MessagingService.instance().send(Message.out(SYNC_RSP, new SyncResponse(desc, src, dst, false, Collections.emptyList())), initiator);
    }
}
