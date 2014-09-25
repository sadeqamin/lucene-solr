package org.apache.solr.request;

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

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.lucene.index.AtomicReaderContext;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.MultiDocValues.MultiSortedDocValues;
import org.apache.lucene.index.MultiDocValues.MultiSortedSetDocValues;
import org.apache.lucene.index.MultiDocValues.OrdinalMap;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.DocIdSet;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.Filter;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.CharsRef;
import org.apache.lucene.util.LongValues;
import org.apache.lucene.util.UnicodeUtil;
import org.apache.solr.common.params.FacetParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.StrUtils;
import org.apache.solr.request.sparse.SparseCounterInt;
import org.apache.solr.request.sparse.SparseCounterPool;
import org.apache.solr.request.sparse.SparseKeys;
import org.apache.solr.request.sparse.ValueCounter;
import org.apache.solr.schema.FieldType;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.search.DocSet;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.util.LongPriorityQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Computes term facets for docvalues field (single or multivalued).
 * <p>
 * This is basically a specialized case of the code in SimpleFacets.
 * Instead of working on a top-level reader view (binary-search per docid),
 * it collects per-segment, but maps ordinals to global ordinal space using
 * MultiDocValues' OrdinalMap.
 * <p>
 * This means the ordinal map is created per-reopen: O(nterms), but this may
 * perform better than PerSegmentSingleValuedFaceting which has to merge O(nterms)
 * per query. Additionally it works for multi-valued fields.
 */
public class DocValuesFacets {
  public static Logger log = LoggerFactory.getLogger(DocValuesFacets.class);

  private DocValuesFacets() {}

  /**************** Sparse implementation start *******************/
  // This is a equivalent to {@link UnInvertedField#getCounts} with the extension that it also handles
  // termLists
  public static NamedList<Integer> getCounts(
      SolrIndexSearcher searcher, DocSet docs, String fieldName, int offset, int limit, int minCount, boolean missing,
      String sort, String prefix, String termList, SparseKeys sparseKeys, SparseCounterPool pool) throws IOException {
    if (!sparseKeys.sparse) { // Skip sparse part completely
      return termList == null ?
          getCounts(searcher, docs, fieldName, offset, limit, minCount, missing, sort, prefix) :
          SimpleFacets.fallbackGetListedTermCounts(searcher, null, fieldName, termList, docs);
    }
    long sparseTotalTime = System.nanoTime();

    SchemaField schemaField = searcher.getSchema().getField(fieldName);
    final int hitCount = docs.size();
    FieldType ft = schemaField.getType();
    NamedList<Integer> res = new NamedList<Integer>();

    final SortedSetDocValues si; // for term lookups only
    OrdinalMap ordinalMap = null; // for mapping per-segment ords to global ones
    if (schemaField.multiValued()) {
      si = searcher.getAtomicReader().getSortedSetDocValues(fieldName);
      if (si instanceof MultiSortedSetDocValues) {
        ordinalMap = ((MultiSortedSetDocValues)si).mapping;
      }
    } else {
      SortedDocValues single = searcher.getAtomicReader().getSortedDocValues(fieldName);
      si = single == null ? null : DocValues.singleton(single);
      if (single instanceof MultiSortedDocValues) {
        ordinalMap = ((MultiSortedDocValues)single).mapping;
      }
    }
    if (si == null) {
      return finalize(res, searcher, schemaField, docs, -1, missing);
    }
    if (si.getValueCount() >= Integer.MAX_VALUE) {
      throw new UnsupportedOperationException("Currently this faceting method is limited to " + Integer.MAX_VALUE + " unique terms");
    }

    final double expectedTerms = (1.0 * hitCount / searcher.maxDoc()) *
        (ordinalMap == null ? si.getValueCount() : ordinalMap.getSegmentOrdinalsCount());
    final double trackedTerms = sparseKeys.fraction * si.getValueCount();
    final boolean probablySparse = si.getValueCount() >= sparseKeys.minTags &&
        expectedTerms < trackedTerms * sparseKeys.cutOff;
    if (!probablySparse && sparseKeys.fallbackToBase) { // Fallback to standard
      pool.incSkipCount("minCount=" + minCount + ", hits=" + hitCount + "/" + searcher.maxDoc()
          + ", terms=" + (si == null ? "N/A" : si.getValueCount()) + ", ordCount="
          + (ordinalMap == null ? "N/A" : ordinalMap.getSegmentOrdinalsCount()));
      return termList == null ?
          getCounts(searcher, docs, fieldName, offset, limit, minCount, missing, sort, prefix) :
          SimpleFacets.fallbackGetListedTermCounts(searcher, pool, fieldName, termList, docs);
    }
    pool.incSparseCalls();

    final BytesRef prefixRef;
    if (prefix == null) {
      prefixRef = null;
    } else if (prefix.length()==0) {
      prefix = null;
      prefixRef = null;
    } else {
      prefixRef = new BytesRef(prefix);
    }

    int startTermIndex, endTermIndex;
    if (prefix!=null) {
      startTermIndex = (int) si.lookupTerm(prefixRef);
      if (startTermIndex<0) startTermIndex=-startTermIndex-1;
      prefixRef.append(UnicodeUtil.BIG_TERM);
      endTermIndex = (int) si.lookupTerm(prefixRef);
      assert endTermIndex < 0;
      endTermIndex = -endTermIndex-1;
    } else {
      startTermIndex=-1;
      endTermIndex=(int) si.getValueCount();
    }

    final int nTerms=endTermIndex-startTermIndex;
    int missingCount = -1;
    final CharsRef charsRef = new CharsRef(10);
    if (nTerms <= 0 || hitCount < minCount) {
      pool.incTotalTime(System.nanoTime() - sparseTotalTime);
      return finalize(res, searcher, schemaField, docs, missingCount, missing);
    }

    // Knowing the maximum value any counter can reach makes it possible to save memory by using a PackedInts structure instead of int[]
//    final long maxCountForAnyTag = ordinalMap == null ?
//        (sparseKeys.packed ? getMaxCountForAnyTag(si, searcher.maxDoc()) : Integer.MAX_VALUE) : // Counting max is only needed for packed
//        ordinalMap.getMaxOrdCount();

    long maxCountForAnyTag;
    try {
      maxCountForAnyTag = pool.getMaxCountForAny() == -1 ?
          calculateMaxCount(searcher, si, ordinalMap, schemaField) :
          pool.getMaxCountForAny();
    } catch (Exception e) {
      log.warn("Exception while calculating maxCountForAnyTag for field " + fieldName +
          ", using searcher.maxDoc=" + searcher.maxDoc(), e);
      maxCountForAnyTag = searcher.maxDoc();
    }

      // +1 as everything is shifted by 1 to use index 0 as special counter
    final ValueCounter counts = pool.acquire((int) si.getValueCount() + 1, maxCountForAnyTag, sparseKeys);
//      final int[] counts = new int[nTerms];

    // Calculate counts for all relevant terms if the counter structure is empty
    if (counts.getContentKey() == null) {
      if (!probablySparse) {
        counts.disableSparseTracking();
      }
      long sparseCollectTime = System.nanoTime();
      Filter filter = docs.getTopFilter();
      List<AtomicReaderContext> leaves = searcher.getTopReaderContext().leaves();
      for (int subIndex = 0; subIndex < leaves.size(); subIndex++) {
        AtomicReaderContext leaf = leaves.get(subIndex);
        DocIdSet dis = filter.getDocIdSet(leaf, null); // solr docsets already exclude any deleted docs
        DocIdSetIterator disi = null;
        if (dis != null) {
          disi = dis.iterator();
        }
        if (disi != null) {
          if (schemaField.multiValued()) {
            SortedSetDocValues sub = leaf.reader().getSortedSetDocValues(fieldName);
            if (sub == null) {
              sub = DocValues.emptySortedSet();
            }
            final SortedDocValues singleton = DocValues.unwrapSingleton(sub);
            if (singleton != null) {
              // some codecs may optimize SORTED_SET storage for single-valued fields
              accumSingle(counts, startTermIndex, singleton, disi, subIndex, ordinalMap);
            } else {
              accumMulti(counts, startTermIndex, sub, disi, subIndex, ordinalMap);
            }
          } else {
            SortedDocValues sub = leaf.reader().getSortedDocValues(fieldName);
            if (sub == null) {
              sub = DocValues.emptySorted();
            }
            accumSingle(counts, startTermIndex, sub, disi, subIndex, ordinalMap);
          }
        }
      }
      pool.incCollectTime(System.nanoTime() - sparseCollectTime);
    }

    if (termList != null) {
      try {
        return extractSpecificCounts(searcher, pool, si, fieldName, docs, counts, termList);
      } finally  {
        pool.release(counts, sparseKeys);
      }
    }

    if (startTermIndex == -1) {
      missingCount = (int) counts.get(0);
    }

    // IDEA: we could also maintain a count of "other"... everything that fell outside
    // of the top 'N'

    int off=offset;
    int lim=limit>=0 ? limit : Integer.MAX_VALUE;

    if (sort.equals(FacetParams.FACET_SORT_COUNT) || sort.equals(FacetParams.FACET_SORT_COUNT_LEGACY)) {
      int maxsize = limit>0 ? offset+limit : Integer.MAX_VALUE-1;
      maxsize = Math.min(maxsize, nTerms);
      LongPriorityQueue queue = new LongPriorityQueue(Math.min(maxsize,1000), maxsize, Long.MIN_VALUE);

//        int min=mincount-1;  // the smallest value in the top 'N' values

      long sparseExtractTime = System.nanoTime();
      try {
        if (counts.iterate(startTermIndex==-1?1:0, nTerms, minCount, false,
            new SparseCounterInt.TopCallback(minCount-1, queue))) {
          pool.incWithinCount();
        } else {
          pool.incExceededCount();
        }
      } catch (ArrayIndexOutOfBoundsException e) {
        throw new ArrayIndexOutOfBoundsException(String.format(
            "Logic error: Out of bounds with startTermIndex=%d, nTerms=%d, minCount=%d and counts=%s",
            startTermIndex, nTerms, minCount, counts));
      }
      pool.incExtractTime(System.nanoTime() - sparseExtractTime);

/*        for (int i=(startTermIndex==-1)?1:0; i<nTerms; i++) {
          int c = counts[i];
          if (c>min) {
            // NOTE: we use c>min rather than c>=min as an optimization because we are going in
            // index order, so we already know that the keys are ordered.  This can be very
            // important if a lot of the counts are repeated (like zero counts would be).

            // smaller term numbers sort higher, so subtract the term number instead
            long pair = (((long)c)<<32) + (Integer.MAX_VALUE - i);
            boolean displaced = queue.insert(pair);
            if (displaced) min=(int)(queue.top() >>> 32);
          }
        }*/

      // if we are deep paging, we don't have to order the highest "offset" counts.
      int collectCount = Math.max(0, queue.size() - off);
      assert collectCount <= lim;

      // the start and end indexes of our list "sorted" (starting with the highest value)
      int sortedIdxStart = queue.size() - (collectCount - 1);
      int sortedIdxEnd = queue.size() + 1;
      final long[] sorted = queue.sort(collectCount);

      for (int i=sortedIdxStart; i<sortedIdxEnd; i++) {
        long pair = sorted[i];
        int c = (int)(pair >>> 32);
        int tnum = Integer.MAX_VALUE - (int)pair;
        BytesRef br = si.lookupOrd(startTermIndex+tnum);
        ft.indexedToReadable(br, charsRef);
        res.add(charsRef.toString(), c);
      }

    } else {
      // add results in index order
      int i=(startTermIndex==-1)?1:0;
      if (minCount<=0) {
        // if mincount<=0, then we won't discard any terms and we know exactly
        // where to start.
        i+=off;
        off=0;
      }

      for (; i<nTerms; i++) {
        int c = (int) counts.get(i);
        if (c<minCount || --off>=0) continue;
        if (--lim<0) break;
        BytesRef br = si.lookupOrd(startTermIndex+i);
        ft.indexedToReadable(br, charsRef);
        res.add(charsRef.toString(), c);
      }
    }
    pool.release(counts, sparseKeys);

    pool.incTotalTime(System.nanoTime() - sparseTotalTime);
    return finalize(res, searcher, schemaField, docs, missingCount, missing);
  }

  /*
   * Determines the maxOrdCount for any term in the given field by looping through all live docIDs and summing the
   * termOrds. This is needed for proper setup of {@link SparseCounterPacked}.
   * Note: This temporarily allocates an int[maxDoc]. Fortunately this happens before standard counter allocation
   * so this should not blow the heap.
   */
  private static long calculateMaxCount(SolrIndexSearcher searcher, SortedSetDocValues si,
                                        OrdinalMap globalMap, SchemaField schemaField) throws IOException {
    final int[] globOrdCount = new int[(int) (si.getValueCount()+1)];
    List<AtomicReaderContext> leaves = searcher.getTopReaderContext().leaves();
    for (int subIndex = 0; subIndex < leaves.size(); subIndex++) {
      AtomicReaderContext leaf = leaves.get(subIndex);
      Bits live = leaf.reader().getLiveDocs();
      if (schemaField.multiValued()) {
        SortedSetDocValues sub = leaf.reader().getSortedSetDocValues(schemaField.getName());
        if (sub == null) {
          sub = DocValues.emptySortedSet();
        }
        final SortedDocValues singleton = DocValues.unwrapSingleton(sub);
        if (singleton != null) {
          for (int docID = 0 ; docID < leaf.reader().maxDoc() ; docID++) {
            if (live == null || live.get(docID)) {
              int segmentOrd = singleton.getOrd(docID);
              if (segmentOrd >= 0) {
                long globalOrd = globalMap == null ? segmentOrd : globalMap.getGlobalOrd(subIndex, segmentOrd);
                if (globalOrd >= 0) {
                  // Not liking the cast here, but 2^31 is de facto limit for most structures
                  globOrdCount[((int) globalOrd)]++;
                }
              }
            }
          }
        } else {
          for (int docID = 0 ; docID < leaf.reader().maxDoc() ; docID++) {
            if (live == null || live.get(docID)) {
            sub.setDocument(docID);
            // strange do-while to collect the missing count (first ord is NO_MORE_ORDS)
              int ord = (int) sub.nextOrd();
              if (ord < 0) {
                continue;
              }

              do {
                if (globalMap != null) {
                  ord = (int) globalMap.getGlobalOrd(subIndex, ord);
                }
                if (ord >= 0) {
                  globOrdCount[ord]++;
                }
              } while ((ord = (int) sub.nextOrd()) >= 0);
            }
          }
        }
      } else {
        SortedDocValues sub = leaf.reader().getSortedDocValues(schemaField.getName());
        if (sub == null) {
          sub = DocValues.emptySorted();
        }
        for (int docID = 0 ; docID < leaf.reader().maxDoc() ; docID++) {
          if (live == null || live.get(docID)) {
            int segmentOrd = sub.getOrd(docID);
            if (segmentOrd >= 0) {
              long globalOrd = globalMap == null ? segmentOrd : globalMap.getGlobalOrd(subIndex, segmentOrd);
              if (globalOrd >= 0) {
                // Not liking the cast here, but 2^31 is de facto limit for most structures
                globOrdCount[((int) globalOrd)]++;
              }
            }
          }
        }
      }
    }

    int maxCount = -1;
    for (int count: globOrdCount) {
      if (count > maxCount) {
        maxCount = count;
      }
    }
    return maxCount;
  }

  private static NamedList<Integer> extractSpecificCounts(
      SolrIndexSearcher searcher, SparseCounterPool pool, SortedSetDocValues si, String field, DocSet docs,
      ValueCounter counts, String termList) throws IOException {
    pool.incTermsLookup(termList, true);
    FieldType ft = searcher.getSchema().getFieldType(field);
    List<String> terms = StrUtils.splitSmart(termList, ",", true);
    NamedList<Integer> res = new NamedList<>();
    for (String term : terms) {
      final long startTime = System.nanoTime();
      String internal = ft.toInternal(term);
      // TODO: Check if +1 is always the case (what about startTermIndex with prefix queries?)
      long index = 1+si.lookupTerm(new BytesRef(term));
      // TODO: Remove err-out after sufficiently testing
      int count;
      if (index < 0) { // This is OK. Asking for a non-existing term is normal in distributed faceting
        pool.incTermLookup(term, true, System.nanoTime()-startTime);
        count = 0;
      } else if(index >= counts.size()) {
        System.err.println("DocValuesFacet.extractSpecificCounts: ordinal for " + term + " in field " + field + " was "
            + index + " but the counts only go from 0 to ordinal " + counts.size() + ". Switching to searcher.numDocs");
        count = searcher.numDocs(new TermQuery(new Term(field, internal)), docs);
        pool.incTermLookup(term, false, System.nanoTime()-startTime);
      } else {
        // Why only int as count?
        count = (int) counts.get((int) index);
        pool.incTermLookup(term, true, System.nanoTime()-startTime);
      }
      res.add(term, count);
    }
    return res;
  }

  // TODO: Find a better solution for caching as this map will grow with each index update
  // TODO: Do not use maxDoc as key! Couple this to the underlying reader
  private static final Map<Integer, Long> maxCounts = new HashMap<Integer, Long>();
  // This is expensive so we remember the result
  private static long getMaxCountForAnyTag(SortedSetDocValues si, int maxDoc) {
    synchronized (maxCounts) {
      if (maxCounts.containsKey(maxDoc)) {
        return maxCounts.get(maxDoc);
      }
    }
    // TODO: Find a way to avoid race condition where the max is calculated more than once
    // TODO: We assume int as max to same space. We should switch to long if maxDoc*valueCount > Integer.MAX_VALUE
    final int[] counters = new int[(int) si.getValueCount()];
    long ord;
    for (int d = 0 ; d < maxDoc ; d++) {
      si.setDocument(d);
      while ((ord = si.nextOrd()) != SortedSetDocValues.NO_MORE_ORDS) {
        counters[((int) ord)]++;
      }
    }
    long max = 0;
    for (int i = 0 ; i < counters.length ; i++) {
      if (counters[i] > max) {
        max = counters[i];
      }
    }
    synchronized (maxCounts) {
      maxCounts.put(maxDoc, max);
    }
    return max;
  }

  static void accumSingle(ValueCounter counts, int startTermIndex, SortedDocValues si, DocIdSetIterator disi, int subIndex, OrdinalMap map) throws IOException {
    int doc;
    while ((doc = disi.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
      int term = si.getOrd(doc);
      if (map != null && term >= 0) {
        term = (int) map.getGlobalOrd(subIndex, term);
      }
      int arrIdx = term-startTermIndex;
      if (arrIdx>=0 && arrIdx<counts.size()) {
        counts.inc(arrIdx);
      }
    }
  }
  static void accumMulti(ValueCounter counts, int startTermIndex, SortedSetDocValues si, DocIdSetIterator disi, int subIndex, OrdinalMap map) throws IOException {
    int doc;
    while ((doc = disi.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
      si.setDocument(doc);
      // strange do-while to collect the missing count (first ord is NO_MORE_ORDS)
      int term = (int) si.nextOrd();
      if (term < 0) {
        if (startTermIndex == -1) {
          counts.inc(0); // missing count
        }
        continue;
      }

      do {
        if (map != null) {
          term = (int) map.getGlobalOrd(subIndex, term);
        }
        int arrIdx = term-startTermIndex;
        if (arrIdx>=0 && arrIdx<counts.size()) counts.inc(arrIdx);
      } while ((term = (int) si.nextOrd()) >= 0);
    }
  }

  /**************** Sparse implementation end *******************/


  public static NamedList<Integer> getCounts(SolrIndexSearcher searcher, DocSet docs, String fieldName, int offset, int limit, int mincount, boolean missing, String sort, String prefix) throws IOException {
    SchemaField schemaField = searcher.getSchema().getField(fieldName);
    FieldType ft = schemaField.getType();
    NamedList<Integer> res = new NamedList<>();

    final SortedSetDocValues si; // for term lookups only
    OrdinalMap ordinalMap = null; // for mapping per-segment ords to global ones
    if (schemaField.multiValued()) {
      si = searcher.getAtomicReader().getSortedSetDocValues(fieldName);
      if (si instanceof MultiSortedSetDocValues) {
        ordinalMap = ((MultiSortedSetDocValues)si).mapping;
      }
    } else {
      SortedDocValues single = searcher.getAtomicReader().getSortedDocValues(fieldName);
      si = single == null ? null : DocValues.singleton(single);
      if (single instanceof MultiSortedDocValues) {
        ordinalMap = ((MultiSortedDocValues)single).mapping;
      }
    }
    if (si == null) {
      return finalize(res, searcher, schemaField, docs, -1, missing);
    }
    if (si.getValueCount() >= Integer.MAX_VALUE) {
      throw new UnsupportedOperationException("Currently this faceting method is limited to " + Integer.MAX_VALUE + " unique terms");
    }

    final BytesRef prefixRef;
    if (prefix == null) {
      prefixRef = null;
    } else if (prefix.length()==0) {
      prefix = null;
      prefixRef = null;
    } else {
      prefixRef = new BytesRef(prefix);
    }

    int startTermIndex, endTermIndex;
    if (prefix!=null) {
      startTermIndex = (int) si.lookupTerm(prefixRef);
      if (startTermIndex<0) startTermIndex=-startTermIndex-1;
      prefixRef.append(UnicodeUtil.BIG_TERM);
      endTermIndex = (int) si.lookupTerm(prefixRef);
      assert endTermIndex < 0;
      endTermIndex = -endTermIndex-1;
    } else {
      startTermIndex=-1;
      endTermIndex=(int) si.getValueCount();
    }

    final int nTerms=endTermIndex-startTermIndex;
    int missingCount = -1;
    final CharsRef charsRef = new CharsRef(10);
    if (nTerms>0 && docs.size() >= mincount) {

      // count collection array only needs to be as big as the number of terms we are
      // going to collect counts for.
      final int[] counts = new int[nTerms];

      Filter filter = docs.getTopFilter();
      List<AtomicReaderContext> leaves = searcher.getTopReaderContext().leaves();
      for (int subIndex = 0; subIndex < leaves.size(); subIndex++) {
        AtomicReaderContext leaf = leaves.get(subIndex);
        DocIdSet dis = filter.getDocIdSet(leaf, null); // solr docsets already exclude any deleted docs
        DocIdSetIterator disi = null;
        if (dis != null) {
          disi = dis.iterator();
        }
        if (disi != null) {
          if (schemaField.multiValued()) {
            SortedSetDocValues sub = leaf.reader().getSortedSetDocValues(fieldName);
            if (sub == null) {
              sub = DocValues.emptySortedSet();
            }
            final SortedDocValues singleton = DocValues.unwrapSingleton(sub);
            if (singleton != null) {
              // some codecs may optimize SORTED_SET storage for single-valued fields
              accumSingle(counts, startTermIndex, singleton, disi, subIndex, ordinalMap);
            } else {
              accumMulti(counts, startTermIndex, sub, disi, subIndex, ordinalMap);
            }
          } else {
            SortedDocValues sub = leaf.reader().getSortedDocValues(fieldName);
            if (sub == null) {
              sub = DocValues.emptySorted();
            }
            accumSingle(counts, startTermIndex, sub, disi, subIndex, ordinalMap);
          }
        }
      }

      if (startTermIndex == -1) {
        missingCount = counts[0];
      }

      // IDEA: we could also maintain a count of "other"... everything that fell outside
      // of the top 'N'

      int off=offset;
      int lim=limit>=0 ? limit : Integer.MAX_VALUE;

      if (sort.equals(FacetParams.FACET_SORT_COUNT) || sort.equals(FacetParams.FACET_SORT_COUNT_LEGACY)) {
        int maxsize = limit>0 ? offset+limit : Integer.MAX_VALUE-1;
        maxsize = Math.min(maxsize, nTerms);
        LongPriorityQueue queue = new LongPriorityQueue(Math.min(maxsize,1000), maxsize, Long.MIN_VALUE);

        int min=mincount-1;  // the smallest value in the top 'N' values
        for (int i=(startTermIndex==-1)?1:0; i<nTerms; i++) {
          int c = counts[i];
          if (c>min) {
            // NOTE: we use c>min rather than c>=min as an optimization because we are going in
            // index order, so we already know that the keys are ordered.  This can be very
            // important if a lot of the counts are repeated (like zero counts would be).

            // smaller term numbers sort higher, so subtract the term number instead
            long pair = (((long)c)<<32) + (Integer.MAX_VALUE - i);
            boolean displaced = queue.insert(pair);
            if (displaced) min=(int)(queue.top() >>> 32);
          }
        }

        // if we are deep paging, we don't have to order the highest "offset" counts.
        int collectCount = Math.max(0, queue.size() - off);
        assert collectCount <= lim;

        // the start and end indexes of our list "sorted" (starting with the highest value)
        int sortedIdxStart = queue.size() - (collectCount - 1);
        int sortedIdxEnd = queue.size() + 1;
        final long[] sorted = queue.sort(collectCount);

        for (int i=sortedIdxStart; i<sortedIdxEnd; i++) {
          long pair = sorted[i];
          int c = (int)(pair >>> 32);
          int tnum = Integer.MAX_VALUE - (int)pair;
          final BytesRef term = si.lookupOrd(startTermIndex+tnum);
          ft.indexedToReadable(term, charsRef);
          res.add(charsRef.toString(), c);
        }

      } else {
        // add results in index order
        int i=(startTermIndex==-1)?1:0;
        if (mincount<=0) {
          // if mincount<=0, then we won't discard any terms and we know exactly
          // where to start.
          i+=off;
          off=0;
        }

        for (; i<nTerms; i++) {
          int c = counts[i];
          if (c<mincount || --off>=0) continue;
          if (--lim<0) break;
          final BytesRef term = si.lookupOrd(startTermIndex+i);
          ft.indexedToReadable(term, charsRef);
          res.add(charsRef.toString(), c);
        }
      }
    }

    return finalize(res, searcher, schemaField, docs, missingCount, missing);
  }

  /** finalizes result: computes missing count if applicable */
  static NamedList<Integer> finalize(NamedList<Integer> res, SolrIndexSearcher searcher, SchemaField schemaField, DocSet docs, int missingCount, boolean missing) throws IOException {
    if (missing) {
      if (missingCount < 0) {
        missingCount = SimpleFacets.getFieldMissingCount(searcher,docs,schemaField.getName());
      }
      res.add(null, missingCount);
    }

    return res;
  }

  /** accumulates per-segment single-valued facet counts */
  static void accumSingle(int counts[], int startTermIndex, SortedDocValues si, DocIdSetIterator disi, int subIndex, OrdinalMap map) throws IOException {
    if (startTermIndex == -1 && (map == null || si.getValueCount() < disi.cost()*10)) {
      // no prefixing, not too many unique values wrt matching docs (lucene/facets heuristic): 
      //   collect separately per-segment, then map to global ords
      accumSingleSeg(counts, si, disi, subIndex, map);
    } else {
      // otherwise: do collect+map on the fly
      accumSingleGeneric(counts, startTermIndex, si, disi, subIndex, map);
    }
  }

  /** accumulates per-segment single-valued facet counts, mapping to global ordinal space on-the-fly */
  static void accumSingleGeneric(int counts[], int startTermIndex, SortedDocValues si, DocIdSetIterator disi, int subIndex, OrdinalMap map) throws IOException {
    final LongValues ordmap = map == null ? null : map.getGlobalOrds(subIndex);
    int doc;
    while ((doc = disi.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
      int term = si.getOrd(doc);
      if (map != null && term >= 0) {
        term = (int) ordmap.get(term);
      }
      int arrIdx = term-startTermIndex;
      if (arrIdx>=0 && arrIdx<counts.length) counts[arrIdx]++;
    }
  }

  /** "typical" single-valued faceting: not too many unique values, no prefixing. maps to global ordinals as a separate step */
  static void accumSingleSeg(int counts[], SortedDocValues si, DocIdSetIterator disi, int subIndex, OrdinalMap map) throws IOException {
    // First count in seg-ord space:
    final int segCounts[];
    if (map == null) {
      segCounts = counts;
    } else {
      segCounts = new int[1+si.getValueCount()];
    }

    int doc;
    while ((doc = disi.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
      segCounts[1+si.getOrd(doc)]++;
    }

    // migrate to global ords (if necessary)
    if (map != null) {
      migrateGlobal(counts, segCounts, subIndex, map);
    }
  }

  /** accumulates per-segment multi-valued facet counts */
  static void accumMulti(int counts[], int startTermIndex, SortedSetDocValues si, DocIdSetIterator disi, int subIndex, OrdinalMap map) throws IOException {
    if (startTermIndex == -1 && (map == null || si.getValueCount() < disi.cost()*10)) {
      // no prefixing, not too many unique values wrt matching docs (lucene/facets heuristic): 
      //   collect separately per-segment, then map to global ords
      accumMultiSeg(counts, si, disi, subIndex, map);
    } else {
      // otherwise: do collect+map on the fly
      accumMultiGeneric(counts, startTermIndex, si, disi, subIndex, map);
    }
  }

  /** accumulates per-segment multi-valued facet counts, mapping to global ordinal space on-the-fly */
  static void accumMultiGeneric(int counts[], int startTermIndex, SortedSetDocValues si, DocIdSetIterator disi, int subIndex, OrdinalMap map) throws IOException {
    final LongValues ordMap = map == null ? null : map.getGlobalOrds(subIndex);
    int doc;
    while ((doc = disi.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
      si.setDocument(doc);
      // strange do-while to collect the missing count (first ord is NO_MORE_ORDS)
      int term = (int) si.nextOrd();
      if (term < 0) {
        if (startTermIndex == -1) {
          counts[0]++; // missing count
        }
        continue;
      }

      do {
        if (map != null) {
          term = (int) ordMap.get(term);
        }
        int arrIdx = term-startTermIndex;
        if (arrIdx>=0 && arrIdx<counts.length) counts[arrIdx]++;
      } while ((term = (int) si.nextOrd()) >= 0);
    }
  }

  /** "typical" multi-valued faceting: not too many unique values, no prefixing. maps to global ordinals as a separate step */
  static void accumMultiSeg(int counts[], SortedSetDocValues si, DocIdSetIterator disi, int subIndex, OrdinalMap map) throws IOException {
    // First count in seg-ord space:
    final int segCounts[];
    if (map == null) {
      segCounts = counts;
    } else {
      segCounts = new int[1+(int)si.getValueCount()];
    }

    int doc;
    while ((doc = disi.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
      si.setDocument(doc);
      int term = (int) si.nextOrd();
      if (term < 0) {
        counts[0]++; // missing
      } else {
        do {
          segCounts[1+term]++;
        } while ((term = (int)si.nextOrd()) >= 0);
      }
    }

    // migrate to global ords (if necessary)
    if (map != null) {
      migrateGlobal(counts, segCounts, subIndex, map);
    }
  }

  /** folds counts in segment ordinal space (segCounts) into global ordinal space (counts) */
  static void migrateGlobal(int counts[], int segCounts[], int subIndex, OrdinalMap map) {
    // missing count
    counts[0] += segCounts[0];

    // migrate actual ordinals
    for (int ord = 1; ord < segCounts.length; ord++) {
      int count = segCounts[ord];
      if (count != 0) {
        counts[1+(int) map.getGlobalOrd(subIndex, ord - 1)] += count;
      }
    }
  }
}
