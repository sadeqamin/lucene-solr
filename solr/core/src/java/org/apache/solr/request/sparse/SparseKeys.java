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

import org.apache.solr.common.params.SolrParams;

public class SparseKeys {
  /**
   * If true, sparse facet counting is enabled.
   */
  public static final String SPARSE = "facet.sparse";
  public static boolean SPARSE_DEFAULT = false;

  /**
   * If true, sparse facet term lookup is enabled (if SPARSE == true). Term lookup is used by the second phase in
   * distributed faceting and is normally performed like facet.method=enum. With this option enabled, faceting calls
   * that qualifies as sparse will use the sparse implementation for resolving counts for terms.
   * </p><p>
   * Highly experimental as of 20140821. Enable with care!
   */
  public static final String TERMLOOKUP = "facet.sparse.termlookup";
  public static boolean TERMLOOKUP_DEFAULT = false;

  /**
   * The minimum number of tags in a sparse counter. If there are less tags than this, sparse will be disabled for
   * that part.
   */
  public static final String MINTAGS = "facet.sparse.mintags";
  public static int MINTAGS_DEFAULT = 10*1000;

  /**
   * Only valid with distributed sparse faceting (facet.sparse == true and multiple shards). This parameter defines the maximum value used
   * for minCount when issuing the first phase calls to the shards.
   * </p><p>
   * If minCount == 0, the shards spend a bit of extra time resolving facet values that might not be needed.
   * However, returning 0-count values might avoid a second call to the shard. This is always true if all shards has the same
   * values in the facet field and if the number of unique values is below {@code initialLimit*1.5+10}.
   * The chances of second-call avoidance falls when the number of unique values rises.
   * Consequently using minCount == 0 should be avoided for medium- to high-cardinality fields.
   * Rule of thumb: Don't use minCount == 0 with more than 100 unique values in the field.
   * </p><p>
   * Sane values are 0 or 1. Default is 1.
   */
  public static final String MAXMINCOUNT = "facet.sparse.maxmincount";
  public static final int MAXMINCOUNT_DEFAULT = 1;

  /**
   * If true, the secondary refinement phase of distributed faceting will be skipped.
   * This speeds up distributed faceting, but removes the guaranteed correct facet term counts.
   */
  public static final String SKIPREFINEMENTS = "facet.sparse.skiprefinements";
  public static final boolean SKIPREFINEMENTS_DEFAULT = false;

  /**
   * If specified (not -1), this is the maximum number any facet term counter will reach for a single shard.
   * Facet terms with a count exceeding this will still be returned and for distributed search, the total
   * count might exceed this.
   * </p><p>
   * This parameter is a performance enhancer. Setting this to a low (relative to the maximum count for any given term)
   * value makes facet counting slightly faster for standard sparse faceting. With sparse and packed faceting, this
   * value influences the amount of memory allocated for the counter structures, resulting in better performance as well
   * as lower memory overhead per facet call. Upon creation of the packed value, the actual max term count is checked
   * for the facet in order to avoid over-allocation. Setting the maxtracked is thus always a safe operation, from a
   * pure performance viewpoint.
   * </p><p>
   * Recommended values are 2^n-1, with 2^8-1 (255) and 2^16-1 (65535) being fastest.
   * </p><p>
   * Important: Setting this value means that the facet term counts might be too low and that the top-X facet terms
   * has a chance of not being the correct ones. Only enable this if the consequences are understood.
   */
  public static final String MAXTRACKED = "facet.sparse.maxtracked";
  public static int MAXTRACKED_DEFAULT = -1;

  /**
   * The size of the sparse tracker, relative to the total amount of unique tags in the facet.
   */
  public static final String FRACTION = "facet.sparse.fraction";
  public static double FRACTION_DEFAULT = 0.08; // 8%

  /**
   * If the <em>estimated number</em> (based on hitcount) of unique tags in the search result exceeds this fraction
   * of the sparse tracker, do not perform sparse tracking. The estimate is based on the assumption that references
   * from documents to tags are distributed randomly.
   */
  public static final String CUTOFF = "facet.sparse.cutoff";
  public static double CUTOFF_DEFAULT = 0.90; // 90%

  /**
   * If true and the {@link #PACKED_BITLIMIT} holds, use {@link SparseCounterPacked} for counting.
   * If false, all sparse counters will be {@link SparseCounterInt}.
   */
  public static final String PACKED = "facet.sparse.packed";
  public static boolean DEFAULT_PACKED = true;

  /**
   * If {@link #PACKED} is true, counters where the maximum value of any counter is <= 2^PACKED_BITLIMIT will be
   * represented with a {@link SparseCounterPacked}.
   */
  public static final String PACKED_BITLIMIT = "facet.sparse.packed.bitlimit";
  public static int DEFAULT_PACKED_BITLIMIT = 24;
  /**
   * Setting this parameter to true will add a special tag with statistics. Only for patch testing!
   * Note: The statistics are delayed when performing distributed faceting. They show the state from the previous call.
   */
  public static final String STATS = "facet.sparse.stats";
  /**
   * Setting this to true resets collected statistics.
   */
  public static final String STATS_RESET = "facet.sparse.stats.reset";

  /**
   * The maximum amount of pools to hold in the {@link org.apache.solr.request.sparse.SparseCounterPoolController}.
   * Each pool is associated with an unique field in the index.
   * Optional. Default is unlimited.
   */
  public static final String POOL_MAX_COUNT = "facet.sparse.pools.max";
  public static int POOL_MAX_COUNT_DEFAULT = Integer.MAX_VALUE;

  /**
   * Maximum number of counters to store for re-use for each field.
   * Optional. Default is 2. Setting this to 0 disables re-use.
   */
  public static final String POOL_SIZE = "facet.sparse.pool.size";
  public static int POOL_SIZE_DEFAULT = 2;

  /**
   * The ideal minimum of empty counters in the pool. If the content drops below this limit, the pool might clear
   * existing filled counters or allocate new ones.
   * </p><p>
   * Optional. Default is 1.
   */
  public static final String POOL_MIN_EMPTY = "facet.sparse.pool.minempty";
  public static int POOL_MIN_EMPTY_DEFAULT = 1;

  /**
   * If true, queries that activates non-sparseness will be redirected to the standard Solr facet implementations.
   * If false, non-sparseness will be handled inside the sparse framework, which includes cached counters.
   * </p><p>
   * Setting this to false is likely to result in lower performance.
   */
  public static final String FALLBACK_BASE = "facet.sparse.fallbacktobase";
  public static boolean FALLBACK_BASE_DEFAULT = true;

  /**
   * If defined, the current request is part of distributed faceting. The cachetoken uniquely defines the bitset used for filling the counters
   * and can be used as key when caching the counts.
   */
  public static final String CACHE_TOKEN = "facet.sparse.cachetoken";

  final public String field;
  
  final public boolean sparse;
  final public boolean termLookup;
  final public boolean fallbackToBase;
  final public int minTags;
  final public double fraction;
  final public double cutOff;
  final public long maxCountsTracked;

  final public boolean packed;
  final public long packedLimit;

  final public int poolSize;
  final public int poolMaxCount;

  final public boolean skipRefinement;

  /**
   * If this is non-null, the token unambigiously designates the params defining the counts for the facet.
   * This is used directly with {@link ValueCounter#getStructureKey()} for caching.
   */
  final public String cacheToken;

  final public boolean showStats;
  final public boolean resetStats;

  public SparseKeys(String field, SolrParams params) {
    this.field = field;
    
    sparse = params.getFieldBool(field, SPARSE, SPARSE_DEFAULT);
    termLookup = params.getFieldBool(field, TERMLOOKUP, TERMLOOKUP_DEFAULT);
    fallbackToBase = params.getFieldBool(field, FALLBACK_BASE, FALLBACK_BASE_DEFAULT);
    minTags = params.getFieldInt(field, MINTAGS, MINTAGS_DEFAULT);
    fraction = params.getFieldDouble(field, FRACTION, FRACTION_DEFAULT);
    cutOff = params.getFieldDouble(field, CUTOFF, CUTOFF_DEFAULT);

    maxCountsTracked = Long.parseLong(params.getFieldParam(field, MAXTRACKED, Long.toString(MAXTRACKED_DEFAULT)));

    packed = params.getFieldBool(field, PACKED, DEFAULT_PACKED);
    packedLimit = params.getFieldInt(field, PACKED_BITLIMIT, DEFAULT_PACKED_BITLIMIT);

    poolSize = params.getFieldInt(field, POOL_SIZE, POOL_SIZE_DEFAULT);
    poolMaxCount = params.getInt(POOL_MAX_COUNT, POOL_MAX_COUNT_DEFAULT);

    skipRefinement = params.getBool(SKIPREFINEMENTS, SKIPREFINEMENTS_DEFAULT);

    cacheToken = params.get(CACHE_TOKEN, null);

    showStats = params.getFieldBool(field, STATS, false);
    resetStats = params.getFieldBool(field, STATS_RESET, false);
  }
  
}
