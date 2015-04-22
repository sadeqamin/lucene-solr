package org.apache.solr.request.sparse;

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

import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.lucene.util.LuceneTestCase;
import org.apache.lucene.util.packed.NPlaneMutable;
import org.apache.lucene.util.packed.PackedInts;
import org.apache.lucene.util.packed.PackedOpportunistic;
import org.apache.solr.SolrTestCaseJ4;
import org.junit.BeforeClass;


@LuceneTestCase.SuppressCodecs({"Lucene3x", "Lucene40", "Lucene41", "Lucene42", "Appending"})
public class ValueCounterTest extends SolrTestCaseJ4 {

  @BeforeClass
  public static void beforeClass() throws Exception {
  }

  public void testNPlaneThreaded() {
    testNPlane(8);
  }

  public void testNPlaneNonThreaded() {
    testNPlane(1);
  }

  public void testPackedOpportunisticReflectionThreaded() {
    testPackedOpportunisticReflection(8);
  }

  public void testPackedOpportunisticReflectionNonThreaded() {
    testPackedOpportunisticReflection(1);
  }

  private void testNPlane(int threads) {
    final int SIZE = 1000;
    final int MAX = 1000;
    final int MAX_UPDATES = SIZE*MAX;

    final PackedInts.Reader maxima = createMaxima(SIZE, MAX);
    SparseCounterThreaded counterA = new SparseCounterThreaded(
        SparseKeys.COUNTER_IMPL.nplane, new NPlaneMutable(maxima),
        MAX, 0, 1.0, Integer.MAX_VALUE);
    SparseCounterThreaded counterB = new SparseCounterThreaded(
        SparseKeys.COUNTER_IMPL.packed, PackedOpportunistic.create(SIZE, PackedInts.bitsRequired(MAX)),
        MAX, 0, 1.0, Integer.MAX_VALUE);

    assertVCEquals(counterA, counterB, maxima, MAX_UPDATES, threads);
  }

  private void testPackedOpportunisticReflection(int threads) {
    final int SIZE = 1000;
    final int MAX = 1000;
    final int MAX_UPDATES = SIZE*MAX;

    final PackedInts.Reader maxima = createMaxima(SIZE, MAX);
    SparseCounterThreaded counterA = new SparseCounterThreaded(
        SparseKeys.COUNTER_IMPL.packed, PackedOpportunistic.create(SIZE, PackedInts.bitsRequired(MAX)),
        MAX, 0, 1.0, Integer.MAX_VALUE);
    SparseCounterThreaded counterB = new SparseCounterThreaded(
        SparseKeys.COUNTER_IMPL.packed, PackedOpportunistic.create(SIZE, PackedInts.bitsRequired(MAX)),
        MAX, 0, 1.0, Integer.MAX_VALUE);

    assertVCEquals(counterA, counterB, maxima, MAX_UPDATES, threads);
  }

  private void assertVCEquals(SparseCounterThreaded counterA, SparseCounterThreaded counterB, PackedInts.Reader maxima, int maxUpdates, int threads) {
    final long sum = sum(maxima);
    final PackedInts.Reader increments = generateRepresentativeValueIncrements(
        maxima, (int) Math.min(sum, maxUpdates), random().nextLong(), sum);
    final ExecutorService executor = Executors.newFixedThreadPool(threads * 2);
    int splitSize = increments.size() / threads;

    for (int i = 0 ; i < threads; i++) {
      executor.submit(new UpdateJob(counterA, increments, maxima, i*splitSize, splitSize));
      executor.submit(new UpdateJob(counterB, increments, maxima, i*splitSize, splitSize));
    }

    executor.shutdown();
    assertVCEquals(counterB, counterA); // We trust PackedOpportunistic more
  }

  private void assertVCEquals(ValueCounter expected, ValueCounter actual) {
    assertEquals("The counters should have the same size", expected.size(), actual.size());
    for (int i = 0 ; i < expected.size() ; i++) {
      assertEquals("The values at index " + i + " should be equal", expected.get(i), actual.get(i));
    }
  }

  private PackedInts.Reader createMaxima(int count, int max) {
    PackedInts.Mutable maxima = PackedInts.getMutable(count, PackedInts.bitsRequired(max), PackedInts.COMPACT);
    for (int i = 0 ; i < count ; i++) {
      maxima.set(i, 1+random().nextInt(max));
    }
    return maxima;
  }

  private static PackedInts.Mutable generateRepresentativeValueIncrements(
      PackedInts.Reader maxima, int updates, long seed, long sum) {
    PackedInts.Mutable increments = PackedInts.getMutable
        (updates, PackedInts.bitsRequired(maxima.size()), PackedInts.FAST);
    if (maxima.size() < 1) {
      return increments;
    }

    final double delta = 1.0*sum/updates;
    double nextPos = 0; // Not very random to always start with 0...
    int currentPos = 1;
    long currentSum = maxima.get(0);
    out:
    for (int i = 0 ; i < updates ; i++) {
      while (nextPos > currentSum) {
        if (currentPos >= maxima.size()) {
          System.out.println(String.format(Locale.ENGLISH,
              "generateRepresentativeValueIncrements error: currentPos=%d with maxima.size()=%d at %d/%d updates",
              currentPos, maxima.size(), i+1, updates));
          break out; // Problem: This leaved the last counters dangling, potentially leading to overflow
        }
        currentSum += maxima.get(currentPos++);
      }
      increments.set(i, currentPos-1);
      nextPos += delta;
    }
    shuffle(increments, new Random(seed));
    return increments;
  }

  // http://stackoverflow.com/questions/1519736/random-shuffling-of-an-array
  private static void shuffle(PackedInts.Mutable values, Random random) {
    int index;
    long temp;
    for (int i = values.size() - 1; i > 0; i--) {
      index = random.nextInt(i + 1);
      temp = values.get(index);
      values.set(index, values.get(i));
      values.set(i, temp);
    }
  }

  public static long sum(PackedInts.Reader values) {
    long sum = 0;
    for (int i = 0 ; i < values.size() ; i++) {
      sum += values.get(i);
    }
    return sum;
  }

  private static class UpdateJob implements Callable<UpdateJob> {
    private final ValueCounter counters;
    private final PackedInts.Reader increments;
    private final PackedInts.Reader maxima;
    private final int start;
    private final int length;

    private UpdateJob(ValueCounter counters, PackedInts.Reader increments, PackedInts.Reader maxima,
                      int start, int length) {
      this.counters = counters;
      this.increments = increments;
      this.maxima = maxima;
      this.start = start;
      this.length = length;
    }

    @Override
    public UpdateJob call() throws Exception {
      for (int i = start; i < start + length; i++) {
        try {
          synchronized (ValueCounterTest.class){
          counters.inc((int) increments.get(i));}
        } catch (Exception e) {
          int totalIncs = -1;
          for (int l = 0; l <= i; l++) { // Locate duplicate increments
            if (increments.get(l) == increments.get(i)) {
              totalIncs++;
            }
          }
          System.err.println(String.format(Locale.ENGLISH,
              "Exception calling %s.inc(%d) #%d with maximum=%d on %s. Aborting updates",
              counters, increments.get(i), totalIncs, maxima.get((int) increments.get(i)), counters));
          break;
        }
      }
      return this;
    }
  }

}
