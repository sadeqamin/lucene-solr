/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.request.sparse;

import org.apache.lucene.util.packed.PackedInts;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Maintains a pool SparseCounters, taking care of allocation, used counter clearing and re-use.
 * </p><p>
 * The pool and/or the content of the pool is bound to the index. When the index is updated and a new facet
 * request is issued, the pool will be cleared.
 * </p><p>
 * Constantly allocating large counter structures taxes the garbage collector of the JVM, so it is faster to
 * clear & re-use structures instead. This is especially true for sparse structures, where the clearing time
 * is proportional to the number of previously updated counters.
 * </p><p>
 * The pool has a fixed maximum limit. If there are no cleaned counters available when requested, a new one
 * will be created. If the pool has reached maximum size and a counter is released, the counter is de-referenced,
 * removing it from the heap. It is advisable to set the pool size to a bit more than the number of concurrent
 * faceted searches, to avoid new allocations.
 * </p><p>
 * Setting the pool size very high has the adverse effect of a constant heap overhead, dictated by the maximum
 * number of concurrent facet requests encountered since last index (re)open.
 * </p><p>
 * The pool consists of a mix of empty (ready for any use) and filled (cached for re-use) counters. If the amount
 * of empty counters gets below the stated threshold, filled counters are cleaned and inserted af empty.
 * </p><p>
 * Default behaviour for the pool is to perform counter clearing in 1 background thread, as this makes it possible
 * to provide a facet result to the caller faster. This can be controlled with {@link #setCleaningThreads(int)}.
 * </p><p>
 * This class is thread safe and with no heavy synchronized parts.
 */
@SuppressWarnings("NullableProblems")
public class SparseCounterPool {
  // TODO: Consider moving cleaning threads to a thread pool shared between all SparseCounterPools
  // Number of threads for background cleaning. If this is 0, cleans will be performed directly in {@link #release}.
  public static final int DEFAULT_CLEANING_THREADS = 1;

  private int max;
  private int minEmpty;
  // ValueCounters with contentKey == null are inserted at the end of the list,
  // ValueCounters with contentKeys defined are inserted at the start.
  // If a ValueCounter's contentKey is null, it is assumed to be clean.
  private final List<ValueCounter> pool;
  private String structureKey = null;

  // Pool stats
  private long reuses = 0;
  private long allocations = 0;
  private long packedAllocations = 0;
  private long lastMaxCountForAny = 0;
  private long clears = 0;
  private long frees = 0;

  // Counter stats
  long sparseCalls = 0;
  long skipCount = 0;
  String lastSkipReason = "no skips";
  long sparseAllocateTime = 0;
  long sparseCollectTime = 0;
  long sparseExtractTime = 0;
  long sparseClearTime = 0;
  long sparseTotalTime = 0;
  long disables = 0;
  long withinCutoffCount = 0;
  long exceededCutoffCount = 0;

  String lastTermsLookup = "N/A";
  long termsCountsLookups = 0;
  long termsFallbackLookups = 0;
  String lastTermLookup = "N/A";
  long termCountsLookups = 0;
  long termFallbackLookups = 0;

  long cacheHits = 0;
  long cacheNoCleared = 0;

  long termTotalCountTime = 0;
  long termTotalFallbackTime = 0;

  protected final ThreadPoolExecutor cleaner =
      (ThreadPoolExecutor) Executors.newFixedThreadPool(DEFAULT_CLEANING_THREADS);
  {
    cleaner.setThreadFactory(new ThreadFactory() {
      @Override
      public Thread newThread(Runnable r) {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("SparsePoolCleaner");
        return t;
      }
    });
    Runtime.getRuntime().addShutdownHook(new Thread() { // Play nice with shutdown
      @Override
      public void run() {
        cleaner.shutdown();
      }
    });
  }


  // Used for synchnization of statistics counters
  private final Object statSync = new Object();

  public SparseCounterPool(int maxPoolSize) {
    this.max = maxPoolSize;
    pool =  new LinkedList<>();
  }

  /**
   * Delivers a counter ready for updates.
   * </p><p>
   * Note: This assumes that the maximum count for any counter it Integer.MAX_VALUE (2^31).
   * If the maximum value is know, it is highly recommended to use {@link #acquire(int, long, SparseKeys)} instead
   * as that makes it possible to deliver optimized counters.
   * @param counts     the number of entries in the counter.
   * @param sparseKeys generic setup for the Sparse system.
   * @return a counter ready for updates.
   */
  public ValueCounter acquire(int counts, SparseKeys sparseKeys) {
    return acquire(counts, Integer.MAX_VALUE, sparseKeys);
  }

  /**
   * Delivers a counter ready for updates. The type of counter will be chosen based on counts, maxCountForAny and
   * the general Sparse setup from sparseKeys. This is the recommended way to get sparse counters.
   * @param counts     the number of entries in the counter.
   * @param maxCountForAny the maximum value that any individual counter can reach.
   * @param sparseKeys generic setup for the Sparse system.
   * @return a counter ready for updates.
   */
  public ValueCounter acquire(int counts, long maxCountForAny, SparseKeys sparseKeys) {
    final long allocateTime = System.nanoTime();
    if (maxCountForAny == 0 && sparseKeys.packed) {
      // We have an empty facet. To avoid problems with the packed structure, we set the maxCountForAny to 1
      maxCountForAny = 1;
//      throw new IllegalStateException("Attempted to request sparse counter with maxCountForAny=" + maxCountForAny);
    }
    try {
      String structureKey = createStructureKey(counts, maxCountForAny, sparseKeys);
      ValueCounter vc = null;
      synchronized (this) {
        if (this.structureKey != null && this.structureKey.equals(structureKey) && !pool.isEmpty()) {
          reuses++;
          //  TODO: If sparseKeys.cackeToken == null, we should take the last element directly
          for (int i = 0 ; i < pool.size() ; i++) {
            vc = pool.get(i);
            if (vc.getContentKey() != null && vc.getContentKey().equals(sparseKeys.cacheToken)) {
              cacheHits++;
              return pool.remove(i);
            } else if (vc.getContentKey() == null) {
              return pool.remove(i);
            }
          }
          // Unable to locate a matching vc or a clean vc. Just take the last one and clean it
          cacheNoCleared++;
          vc = pool.get(pool.size()-1);
        }
      }
      if (vc != null) {
        vc.clear();
        return vc;
      }
      return getCounter(counts, maxCountForAny, sparseKeys);
    } finally {
      incAllocateTime(System.nanoTime() - allocateTime);
    }
  }

  private String createStructureKey(int counts, long maxCountForAny, SparseKeys sparseKeys) {
    return usePacked(counts, maxCountForAny, sparseKeys) ?
        SparseCounterPacked.getID(
            counts, maxCountForAny, sparseKeys.minTags, sparseKeys.fraction, sparseKeys.maxCountsTracked) :
        SparseCounterInt.getID(counts, maxCountForAny, sparseKeys.minTags, sparseKeys.fraction);
  }

  private boolean usePacked(int counts, long maxCountForAny, SparseKeys sparseKeys) {
    return (sparseKeys.packed && PackedInts.bitsRequired(maxCountForAny) <= sparseKeys.packedLimit) ||
            maxCountForAny > Integer.MAX_VALUE;
  }

  private ValueCounter getCounter(int counts, long maxCountForAny, SparseKeys sparseKeys) {
    synchronized (this) {
      allocations++;
      lastMaxCountForAny = maxCountForAny;
    }
    if (usePacked(counts, maxCountForAny, sparseKeys)) {
      synchronized (this) {
        packedAllocations++;
      }
      return new SparseCounterPacked(
          counts, maxCountForAny, sparseKeys.minTags, sparseKeys.fraction, sparseKeys.maxCountsTracked);
    }
    return new SparseCounterInt(
        counts, maxCountForAny, sparseKeys.minTags, sparseKeys.fraction, sparseKeys.maxCountsTracked);
  }


  /**
   * Release a counter after use. Depending on the sparseKeys, the counter might be cached as-is or cleared before being added to the pool.
   * </p><p>
   * If {@link #getCleaningThreads} is >= 1, cleaning will be performed in the background and the method
   * will return immediately. If there are 0 cleaning threads and a cleaning is needed,it will be done up front and the method
   * will return after it has finished. Cleaning time is proportional to the number of updated counters.
   * @param counter a used counter.
   *  @param sparseKeys the facet keys associated with the counter.
   */
  public void release(ValueCounter counter, SparseKeys sparseKeys) {
    // Initial fast sanity check and potential pool cleaning
    synchronized (this) {
      if (counter.explicitlyDisabled()) {
        disables++;
      }
      if (structureKey != null && !counter.getStructureKey().equals(structureKey)) {
        // Setup changed, clear all!
        pool.clear();
        structureKey = null;
      }
      if (pool.size() + cleaner.getQueue().size() >= max) {
        // TODO: If sparseKey.reuseLikely then remove last (is any) and insert in beginning
        // Pool full and/or too many counters in clean queue; just release the counter to GC
        frees++;
        return;
      }
      clears++; // We now know a clear will be performed
    }

    if (cleaner.getCorePoolSize() == 0) {
      // No background cleaning, so we must do it up front
      long clearTime = System.nanoTime();
      counter.clear();
      incClearTime(System.nanoTime() - clearTime);
      releaseCleared(counter);
    } else {
      // Send the cleaning to the background thread(s)
      cleaner.execute(new FutureTask<ValueCounter>( new BackgroundClear(counter)));
    }
  }

  /**
   * Called by the background cleaner when a counter has been processed.
   * @param counter a fully cleaned counter, ready for use.
   */
  private void releaseCleared(ValueCounter counter) {
    synchronized (this) {
      if (structureKey != null && !counter.getStructureKey().equals(structureKey) || pool.size() >= max) {
        // Setup changed or pool full. Skip insert!
        frees++;
        return;
      }
      pool.add(counter);
      structureKey = counter.getStructureKey();
    }
  }

  /**
   * @return the maximum amount of counters in this pool.
   */
  public int getMax() {
    return max;
  }

  /**
   * @param max the maximum amount of counters in this pool.
   */
  public void setMax(int max) {
    synchronized (this) {
      if (this.max == max) {
        return;
      }
      while (pool.size() > max) {
        pool.remove(pool.size()-1);
      }
      this.max = max;
    }
  }

  /**
   * 0 cleaning threads means that cleaning will be performed upon {@link #release}.
   * @return the amount of Threads used for cleaning counters in the background.
   */
  public int getCleaningThreads() {
    return cleaner.getCorePoolSize();
  }

  /**
   * 0 cleaning threads means that cleaning will be performed upon {@link #release}.
   * @param threads the amount of threads used for cleaning counters in the background.
   */
  public void setCleaningThreads(int threads) {
    synchronized (cleaner) {
      cleaner.setCorePoolSize(threads);
    }
  }

  /**
   * Clears both the pool and any accumulated statistics.
   */
  public void clear() {
    synchronized (this) {
      pool.clear();
      // TODO: Find a clean way to remove non-started tasks from the cleaner
      structureKey = "discardResultsFromBackgroundClears";
      reuses = 0;
      allocations = 0;
      packedAllocations = 0;
      lastMaxCountForAny = 0;
      clears = 0;
      frees = 0;

      sparseCalls = 0;
      skipCount = 0;
      sparseAllocateTime = 0;
      sparseCollectTime = 0;
      sparseExtractTime = 0;
      sparseClearTime = 0;
      sparseTotalTime = 0;
      withinCutoffCount = 0;
      disables = 0;
      exceededCutoffCount = 0;
      lastSkipReason = "no skips";

      lastTermsLookup = "N/A";
      termsCountsLookups = 0;
      termsFallbackLookups = 0;
      lastTermLookup = "N/A";
      termCountsLookups = 0;
      termFallbackLookups = 0;

      cacheHits = 0;
      cacheNoCleared = 0;

      termTotalCountTime = 0;
      termTotalFallbackTime = 0;
    }
  }

  public void incSparseCalls() {
    synchronized (statSync) {
      sparseCalls++;
    }
  }
  public void incSkipCount(String reason) {
    synchronized (statSync) {
      skipCount++;
    }
    lastSkipReason = reason;
  }
  public void incWithinCount() {
    synchronized (statSync) {
      withinCutoffCount++;
    }
  }
  public void incExceededCount() {
    synchronized (statSync) {
      exceededCutoffCount++;
    }
  }
  private void incAllocateTime(long delta) {
    synchronized (statSync) {
      sparseAllocateTime += delta;
    }
  }
  // Nanoseconds
  public void incCollectTime(long delta) {
    synchronized (statSync) {
      sparseCollectTime += delta;
    }
  }
  // Nanoseconds
  public void incExtractTime(long delta) {
    synchronized (statSync) {
      sparseExtractTime += delta;
    }
  }
  private void incClearTime(long delta) {
    synchronized (statSync) {
      sparseClearTime += delta;
    }
  }
  // Nanoseconds
  public void incTotalTime(long delta) {
    synchronized (statSync) {
      sparseTotalTime += delta;
    }
  }
  public void incTermsLookup(String terms, boolean countsStructure) {
    lastTermsLookup = terms;
    synchronized (statSync) {
      if (countsStructure) {
        termsCountsLookups++;
      } else {
        termsFallbackLookups++;
      }
    }
  }
  // Nanoseconds
  public void incTermLookup(String term, boolean countsStructure, long time) {
    lastTermLookup = term;
    synchronized (statSync) {
      if (countsStructure) {
        termCountsLookups++;
        termTotalCountTime += time;
      } else {
        termFallbackLookups++;
        termTotalFallbackTime += time;
      }
    }
  }

  /**
   * @return timing and count statistics for this pool.
   */
  @Override
  public String toString() {
    if (sparseCalls == 0) {
      return "No sparse faceting performed yet";
    }
    final int M = 1000000;
    synchronized (this) { // We need to synchronize this to get accurate counts for the pool
      final int pendingCleans = cleaner.getQueue().size() + cleaner.getActiveCount();
      final int poolSize = pool.size();
      final int cleanerCoreSize = cleaner.getCorePoolSize();
      return String.format(
          "sparse statistics: calls=%d, fallbacks=%d (last: %s), collect=%dms avg, extract=%dms avg, " +
              "total=%dms avg, disables=%d,  withinCutoff=%d, exceededCutoff=%d, SCPool(cached=%d/%d, reuses=%d, " +
              "allocations=%d (%dms avg, %d packed), clears=%d (%dms avg," +
              " %s%s), " +
              "frees=%d, lastMaxCountForAny=%d), terms(count=%d, fallback=%d, last#=%d), " +
              "term(count=%d (%.1fms avg), fallback=%d (%.1fms avg), last=%s)",
          sparseCalls, skipCount, lastSkipReason, sparseCollectTime/sparseCalls/M, sparseExtractTime/sparseCalls/M,
          sparseTotalTime/sparseCalls/M, disables, withinCutoffCount, exceededCutoffCount, poolSize, max, reuses,
          allocations, sparseAllocateTime/sparseCalls/M, packedAllocations, clears, sparseClearTime /sparseCalls/M,
          cleanerCoreSize > 0 ? "background" : "at release",
          pendingCleans > 0 ? (" (" + pendingCleans + " running)") : "",
          frees, lastMaxCountForAny, termsCountsLookups, termsFallbackLookups, lastTermsLookup.split(",").length,
          termCountsLookups, termCountsLookups == 0 ? 0 : termTotalCountTime * 1.0 / M / termCountsLookups,
          termFallbackLookups, termFallbackLookups == 0 ? 0 : termTotalFallbackTime * 1.0 / M / termFallbackLookups,
          lastTermLookup);
    }
  }

  /**
   * Clears the given counter and calls {@link #releaseCleared(ValueCounter)} when done.
      */
  private class BackgroundClear implements Callable<ValueCounter> {
    private final ValueCounter counter;

    private BackgroundClear(ValueCounter counter) {
      this.counter = counter;
    }

    @Override
    public ValueCounter call() throws Exception {
      final long startTime = System.nanoTime();
      counter.clear();
      incClearTime(System.nanoTime() - startTime);
      releaseCleared(counter);
      return counter;
    }
  }
}
