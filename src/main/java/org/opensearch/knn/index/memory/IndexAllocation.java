/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.knn.index.memory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.opensearch.common.concurrent.RefCountedReleasable;
import org.opensearch.knn.common.featureflags.KNNFeatureFlags;
import org.opensearch.knn.index.engine.KNNEngine;
import org.opensearch.knn.index.query.KNNWeight;
import org.opensearch.knn.jni.JNIService;

import lombok.Getter;

/**
 * Represents native indices loaded into memory. Because these indices are backed by files, they should be
 * freed when file is deleted.
 */
public class IndexAllocation implements NativeMemoryAllocation {

    private final ExecutorService executor;
    private final long memoryAddress;
    private final int sizeKb;
    private volatile boolean closed;
    @Getter
    private final KNNEngine knnEngine;
    @Getter
    private final String vectorFileName;
    @Getter
    private final String openSearchIndexName;
    private final ReadWriteLock readWriteLock;
    private final SharedIndexState sharedIndexState;
    @Getter
    private final boolean isBinaryIndex;
    private final RefCountedReleasable<IndexAllocation> refCounted;

    /**
     * Constructor
     *
     * @param executorService Executor service used to close the allocation
     * @param memoryAddress Pointer in memory to the index
     * @param sizeKb Size this index consumes in kilobytes
     * @param knnEngine KNNEngine associated with the index allocation
     * @param vectorFileName Vector file name. Ex: _0_165_my_field.faiss
     * @param openSearchIndexName Name of OpenSearch index this index is associated with
     */
    IndexAllocation(
        ExecutorService executorService,
        long memoryAddress,
        int sizeKb,
        KNNEngine knnEngine,
        String vectorFileName,
        String openSearchIndexName
    ) {
        this(executorService, memoryAddress, sizeKb, knnEngine, vectorFileName, openSearchIndexName, null, false);
    }

    /**
     * Constructor
     *
     * @param executorService Executor service used to close the allocation
     * @param memoryAddress Pointer in memory to the index
     * @param sizeKb Size this index consumes in kilobytes
     * @param knnEngine KNNEngine associated with the index allocation
     * @param vectorFileName Vector file name. Ex: _0_165_my_field.faiss
     * @param openSearchIndexName Name of OpenSearch index this index is associated with
     * @param sharedIndexState Shared index state. If not shared state present, pass null.
     */
    IndexAllocation(
        ExecutorService executorService,
        long memoryAddress,
        int sizeKb,
        KNNEngine knnEngine,
        String vectorFileName,
        String openSearchIndexName,
        SharedIndexState sharedIndexState,
        boolean isBinaryIndex
    ) {
        this.executor = executorService;
        this.closed = false;
        this.knnEngine = knnEngine;
        this.vectorFileName = vectorFileName;
        this.openSearchIndexName = openSearchIndexName;
        this.memoryAddress = memoryAddress;
        this.readWriteLock = new ReentrantReadWriteLock();
        this.sizeKb = sizeKb;
        this.sharedIndexState = sharedIndexState;
        this.isBinaryIndex = isBinaryIndex;
        this.refCounted = new RefCountedReleasable<>("IndexAllocation-Reference", this, this::closeInternal);
    }

    protected void closeInternal() {
        Runnable onClose = () -> {
            writeLock();
            try {
                cleanup();
            } finally {
                writeUnlock();
            }
        };

        // The close operation needs to be blocking to prevent overflow
        // This blocks any entry until the close has completed, preventing creation before close scenarios
        if (KNNFeatureFlags.isForceEvictCacheEnabled()) {
            onClose.run();
        } else {
            executor.execute(onClose);
        }
    }

    @Override
    public void close() {
        if (!closed && refCounted.refCount() > 0) {
            refCounted.close();
        }
    }

    private void cleanup() {
        if (this.closed) {
            return;
        }

        this.closed = true;

        // memoryAddress is sometimes initialized to 0. If this is ever the case, freeing will surely fail.
        if (memoryAddress != 0) {
            JNIService.free(memoryAddress, knnEngine, isBinaryIndex);
        }

        if (sharedIndexState != null) {
            SharedIndexStateManager.getInstance().release(sharedIndexState);
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public long getMemoryAddress() {
        return memoryAddress;
    }

    /**
     * The read lock will be obtained in the
     * {@link KNNWeight#scorer(LeafReaderContext context) scorer} when a native index needs
     * to be queried.
     */
    @Override
    public void readLock() {
        readWriteLock.readLock().lock();
    }

    /**
     * The write lock will be obtained in the
     * {@link NativeMemoryCacheManager NativeMemoryManager's} onRemoval function when the Index Allocation is
     * evicted from the cache. This prevents memory from being deallocated when it is being actively searched.
     */
    @Override
    public void writeLock() {
        readWriteLock.writeLock().lock();
    }

    @Override
    public void readUnlock() {
        readWriteLock.readLock().unlock();
    }

    @Override
    public void writeUnlock() {
        readWriteLock.writeLock().unlock();
    }

    @Override
    public int getSizeInKB() {
        return sizeKb;
    }

    @Override
    public void incRef() {
        refCounted.incRef();
    }

    @Override
    public boolean decRef() {
        return refCounted.decRef();
    }
}
