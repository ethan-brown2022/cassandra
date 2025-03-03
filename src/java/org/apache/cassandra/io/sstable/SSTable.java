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
package org.apache.cassandra.io.sstable;

import java.io.FileNotFoundException;
import java.io.IOError;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.db.BufferDecoratedKey;
import org.apache.cassandra.db.DecoratedKey;
import org.apache.cassandra.db.PartitionPosition;
import org.apache.cassandra.db.lifecycle.Tracker;
import org.apache.cassandra.dht.AbstractBounds;
import org.apache.cassandra.dht.IPartitioner;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.io.FSWriteError;
import org.apache.cassandra.io.sstable.format.PartitionIndexIterator;
import org.apache.cassandra.io.util.DiskOptimizationStrategy;
import org.apache.cassandra.io.util.File;
import org.apache.cassandra.io.util.FileOutputStreamPlus;
import org.apache.cassandra.io.util.FileUtils;
import org.apache.cassandra.schema.TableMetadata;
import org.apache.cassandra.schema.TableMetadataRef;
import org.apache.cassandra.utils.Pair;
import org.apache.cassandra.utils.memory.HeapAllocator;

import static org.apache.cassandra.io.util.File.WriteMode.APPEND;
import static org.apache.cassandra.service.ActiveRepairService.NO_PENDING_REPAIR;
import static org.apache.cassandra.service.ActiveRepairService.UNREPAIRED_SSTABLE;

/**
 * This class is built on top of the SequenceFile. It stores
 * data on disk in sorted fashion. However the sorting is upto
 * the application. This class expects keys to be handed to it
 * in sorted order.
 *
 * A separate index file is maintained as well, containing the
 * SSTable keys and the offset into the SSTable at which they are found.
 * Every 1/indexInterval key is read into memory when the SSTable is opened.
 *
 * Finally, a bloom filter file is also kept for the keys in each SSTable.
 */
public abstract class SSTable
{
    static final Logger logger = LoggerFactory.getLogger(SSTable.class);

    public static final int TOMBSTONE_HISTOGRAM_BIN_SIZE = 100;
    public static final int TOMBSTONE_HISTOGRAM_SPOOL_SIZE = 100000;
    public static final int TOMBSTONE_HISTOGRAM_TTL_ROUND_SECONDS = Integer.valueOf(System.getProperty("cassandra.streaminghistogram.roundseconds", "60"));

    public final Descriptor descriptor;
    public final Set<Component> components;
    public final boolean compression;

    public DecoratedKey first;
    public DecoratedKey last;

    protected final DiskOptimizationStrategy optimizationStrategy;
    protected final TableMetadataRef metadata;
    private static final int SAMPLES_CAP = 10000;
    private static final int BYTES_CAP = 10000000;

    protected SSTable(Descriptor descriptor, Set<Component> components, TableMetadataRef metadata, DiskOptimizationStrategy optimizationStrategy)
    {
        // In almost all cases, metadata shouldn't be null, but allowing null allows to create a mostly functional SSTable without
        // full schema definition. SSTableLoader use that ability
        assert descriptor != null;
        assert components != null;

        this.descriptor = descriptor;
        Set<Component> dataComponents = new HashSet<>(components);
        this.compression = dataComponents.contains(Component.COMPRESSION_INFO);
        this.components = new CopyOnWriteArraySet<>(dataComponents);
        this.metadata = metadata;
        this.optimizationStrategy = Objects.requireNonNull(optimizationStrategy);
    }

    @VisibleForTesting
    public Set<Component> getComponents()
    {
        return ImmutableSet.copyOf(components);
    }

    /**
     * We use a ReferenceQueue to manage deleting files that have been compacted
     * and for which no more SSTable references exist.  But this is not guaranteed
     * to run for each such file because of the semantics of the JVM gc.  So,
     * we write a marker to `compactedFilename` when a file is compacted;
     * if such a marker exists on startup, the file should be removed.
     *
     * This method will also remove SSTables that are marked as temporary.
     *
     * @return true if the file was deleted
     */
    public static boolean delete(Descriptor desc, Set<Component> components)
    {
        logger.info("Deleting sstable: {}", desc);
        // remove the DATA component first if it exists
        if (components.contains(Component.DATA))
            FileUtils.deleteWithConfirm(desc.fileFor(Component.DATA));
        for (Component component : components)
        {
            if (component.equals(Component.DATA) || component.equals(Component.SUMMARY))
                continue;

            FileUtils.deleteWithConfirm(desc.fileFor(component));
        }

        if (components.contains(Component.SUMMARY))
            FileUtils.delete(desc.fileFor(Component.SUMMARY));

        return true;
    }

    public TableMetadata metadata()
    {
        return metadata.get();
    }

    public TableMetadataRef metadataRef()
    {
        return metadata;
    }

    public IPartitioner getPartitioner()
    {
        return metadata().partitioner;
    }

    public DecoratedKey decorateKey(ByteBuffer key)
    {
        return getPartitioner().decorateKey(key);
    }

    /**
     * If the given @param key occupies only part of a larger buffer, allocate a new buffer that is only
     * as large as necessary.
     */
    public static DecoratedKey getMinimalKey(DecoratedKey key)
    {
        return key.getKey().position() > 0 || key.getKey().hasRemaining() || !key.getKey().hasArray()
                                       ? new BufferDecoratedKey(key.getToken(), HeapAllocator.instance.clone(key.getKey()))
                                       : key;
    }

    public String getFilename()
    {
        return getDataFile().path();
    }

    public File getDataFile()
    {
        return descriptor.fileFor(Component.DATA);
    }

    public String getColumnFamilyName()
    {
        return descriptor.cfname;
    }

    public String getKeyspaceName()
    {
        return descriptor.ksname;
    }

    public SSTableId getId()
    {
        return descriptor.id;
    }

    public int getComponentSize()
    {
        return components.size();
    }

    /**
     * Parse a sstable filename into both a {@link Descriptor} and {@code Component} object.
     *
     * @param file the filename to parse.
     * @return a pair of the {@code Descriptor} and {@code Component} corresponding to {@code file} if it corresponds to
     * a valid and supported sstable filename, {@code null} otherwise. Note that components of an unknown type will be
     * returned as CUSTOM ones.
     */
    public static Pair<Descriptor, Component> tryComponentFromFilename(File file)
    {
        try
        {
            return Descriptor.fromFilenameWithComponent(file);
        }
        catch (Throwable e)
        {
            return null;
        }
    }

    /**
     * Parse a sstable filename into a {@link Descriptor} object.
     * <p>
     * Note that this method ignores the component part of the filename; if this is not what you want, use
     * {@link #tryComponentFromFilename} instead.
     *
     * @param file the filename to parse.
     * @return the {@code Descriptor} corresponding to {@code file} if it corresponds to a valid and supported sstable
     * filename, {@code null} otherwise.
     */
    public static Descriptor tryDescriptorFromFilename(File file)
    {
        try
        {
            return Descriptor.fromFilename(file);
        }
        catch (Throwable e)
        {
            return null;
        }
    }

    /**
     * Discovers existing components for the descriptor. Slow: only intended for use outside the critical path.
     */
    public static Set<Component> componentsFor(final Descriptor desc)
    {
        try
        {
            try
            {
                SSTableWatcher.instance.discoverComponents(desc);
                return readTOC(desc);
            }
            catch (FileNotFoundException | NoSuchFileException e)
            {
                Set<Component> components = discoverComponentsFor(desc);
                if (components.isEmpty())
                    return components; // sstable doesn't exist yet

                if (!components.contains(Component.TOC))
                    components.add(Component.TOC);
                appendTOC(desc, components);
                return components;
            }
        }
        catch (IOException e)
        {
            throw new IOError(e);
        }
    }

    public static Set<Component> discoverComponentsFor(Descriptor desc)
    {
        Set<Component.Type> knownTypes = Sets.difference(Component.TYPES, Collections.singleton(Component.Type.CUSTOM));
        Set<Component> components = Sets.newHashSetWithExpectedSize(knownTypes.size());
        for (Component.Type componentType : knownTypes)
        {
            Component component = new Component(componentType);
            if (desc.fileFor(component).exists())
                components.add(component);
        }
        return components;
    }

    /** @return An estimate of the number of keys contained in the given index file. */
    public static long estimateRowsFromIndex(PartitionIndexIterator iterator) throws IOException
    {
        // collect sizes for the first 10000 keys, or first 10 megabytes of data
        try
        {
            int keys = 0;
            while (!iterator.isExhausted() && iterator.indexPosition() < BYTES_CAP && keys < SAMPLES_CAP)
            {
                iterator.advance();
                keys++;
            }
            assert keys > 0 && iterator.indexPosition() > 0 && iterator.indexLength() > 0 : "Unexpected empty index file";
            return iterator.indexLength() / (iterator.indexPosition() / keys);
        }
        finally
        {
            iterator.reset();
        }
    }

    public long bytesOnDisk()
    {
        long bytes = 0;
        for (Component component : components)
        {
            bytes += descriptor.fileFor(component).length();
        }
        return bytes;
    }

    @Override
    public String toString()
    {
        return getClass().getSimpleName() + "(" +
               "path='" + getFilename() + '\'' +
               ')';
    }

    /**
     * Reads the list of components from the TOC component.
     * @return set of components found in the TOC
     */
    @VisibleForTesting
    public static Set<Component> readTOC(Descriptor descriptor) throws IOException
    {
        return readTOC(descriptor, true);
    }

    /**
     * Reads the list of components from the TOC component.
     * @param skipMissing, skip adding the component to the returned set if the corresponding file is missing.
     * @return set of components found in the TOC
     */
    protected static Set<Component> readTOC(Descriptor descriptor, boolean skipMissing) throws IOException
    {
        File tocFile = descriptor.fileFor(Component.TOC);
        List<String> componentNames = Files.readAllLines(tocFile.toPath());
        Set<Component> components = Sets.newHashSetWithExpectedSize(componentNames.size());
        for (String componentName : componentNames)
        {
            Component component = new Component(Component.Type.fromRepresentation(componentName), componentName);
            if (skipMissing && !descriptor.fileFor(component).exists())
                logger.error("Missing component: {}", descriptor.fileFor(component));
            else
                components.add(component);
        }
        return components;
    }

    /**
     * Rewrite TOC components by deleting existing TOC file and append new components
     */
    private static void rewriteTOC(Descriptor descriptor, Collection<Component> components)
    {
        File tocFile = descriptor.fileFor(Component.TOC);
        if (!tocFile.tryDelete())
            logger.error("Failed to delete TOC component for " + descriptor);
        appendTOC(descriptor, components);
    }

    /**
     * Appends new component names to the TOC component.
     */
    @SuppressWarnings("resource")
    protected static void appendTOC(Descriptor descriptor, Collection<Component> components)
    {
        File tocFile = descriptor.fileFor(Component.TOC);
        FileOutputStreamPlus fos = null;
        try (PrintWriter w = new PrintWriter((fos = tocFile.newOutputStream(APPEND))))
        {
            for (Component component : components)
                w.println(component.name);
            w.flush();
            fos.sync();
        }
        catch (IOException e)
        {
            throw new FSWriteError(e, tocFile);
        }
    }

    /**
     * Registers new custom components. Used by custom compaction strategies.
     * Adding a component for the second time is a no-op.
     * Don't remove this - this method is a part of the public API, intended for use by custom compaction strategies.
     * @param newComponents collection of components to be added
     */
    public synchronized void addComponents(Collection<Component> newComponents)
    {
        registerComponents(newComponents, null);
    }

    /**
     * Registers new custom components into sstable and update size tracking
     * @param newComponents collection of components to be added
     * @param tracker used to update on-disk size metrics
     */
    public synchronized void registerComponents(Collection<Component> newComponents, Tracker tracker)
    {
        Collection<Component> componentsToAdd = new HashSet<>(Collections2.filter(newComponents, x -> !components.contains(x)));
        appendTOC(descriptor, componentsToAdd);
        components.addAll(componentsToAdd);

        if (tracker == null)
            return;

        for (Component component : componentsToAdd)
        {
            File file = descriptor.fileFor(component);
            if (file.exists())
                tracker.updateSizeTracking(file.length());
        }
    }

    /**
     * Unregisters custom components from sstable and update size tracking
     * @param removeComponents collection of components to be remove
     * @param tracker used to update on-disk size metrics
     */
    public synchronized void unregisterComponents(Collection<Component> removeComponents, Tracker tracker)
    {
        Collection<Component> componentsToRemove = new HashSet<>(Collections2.filter(removeComponents, components::contains));
        components.removeAll(componentsToRemove);
        rewriteTOC(descriptor, components);

        for (Component component : componentsToRemove)
        {
            File file = descriptor.fileFor(component);
            if (file.exists())
                tracker.updateSizeTracking(-file.length());
        }
    }

    public AbstractBounds<Token> getBounds()
    {
        return AbstractBounds.bounds(first.getToken(), true, last.getToken(), true);
    }

    public static void validateRepairedMetadata(long repairedAt, UUID pendingRepair, boolean isTransient)
    {
        Preconditions.checkArgument((pendingRepair == NO_PENDING_REPAIR) || (repairedAt == UNREPAIRED_SSTABLE),
                                    "pendingRepair cannot be set on a repaired sstable");
        Preconditions.checkArgument(!isTransient || (pendingRepair != NO_PENDING_REPAIR),
                                    "isTransient can only be true for sstables pending repair");

    }

    public PartitionPosition getFirst()
    {
        return first;
    }

    public PartitionPosition getLast()
    {
        return last;
    }
}
