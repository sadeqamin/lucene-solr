package org.apache.lucene.util.packed;

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

import org.apache.lucene.util.Incrementable;
import org.apache.lucene.util.LuceneTestCase;
import org.apache.lucene.util.LuceneTestCase.Slow;

import java.util.Locale;
import java.util.Random;

@Slow
public class TestLongTailMutable extends LuceneTestCase {

  private final static int M = 1048576;

  public void testLinksEstimate() { // 519*M
    testEstimate("8/9 shard links", getLinksHistogram(), false);
  }

  public void testURLEstimate() {
    testEstimate("Shard 1 URL", getURLShard1Histogram(), false);
    testEstimate("Shard 2 URL", getURLShard2Histogram(), false);
    testEstimate("Shard 3 URL", getURLShard3Histogram(), false);
  }
  public void testHostEstimate() { // 1562680
    testEstimate("Shard 1 host", SHARD1_HOST, false);
  }

  public void testViability() {
    LongTailMutable.Estimate estimate = new LongTailMutable.Estimate(100*M, pad(
        1000,
        100,
        1,
        3
    ));
    // 1+3=4 and there are 2^2=4 pointers
    assertTrue("The layout should be viable for tailBPV=2", estimate.isViable(2));
  }


  public void testMemoryUsages() {
    dumpImplementationMemUsages("Shard 1 URL", getURLShard1Histogram());
    dumpImplementationMemUsages("Links raw", getLinksHistogram());
    dumpImplementationMemUsages("Links 20150309", TestLongTailBitPlaneMutable.links20150209);
  }

  private void dumpImplementationMemUsages(String source, long[] histogram) {
    long valueCount = 0;
    for (long values: histogram) {
      valueCount += values;
    }
    final long intC = valueCount*4;
    final long packC = valueCount * maxBit(histogram) / 8;
    final long ltbpmC = LongTailBitPlaneMutable.estimateBytesNeeded(histogram);
    final long ltbpmeC = LongTailBitPlaneMutable.estimateBytesNeeded(histogram, true);
    final long ltmC = LongTailMutable.estimateBytesNeeded(histogram, (int) valueCount);
    System.out.println(source + ": " + valueCount + " counters, max bit " + maxBit(histogram));
    System.out.println(String.format("Solr default int[]: %4dMB", intC/M));
    System.out.println(String.format("Sparse PackedInts:  %4dMB", packC/M));
    System.out.println(String.format("Long Tail Dual:     %4dMB", ltmC / M));
    System.out.println(String.format("Long Tail Planes:   %4dMB", ltbpmC / M));
    System.out.println(String.format("Long Tail Planes+:  %4dMB", ltbpmeC / M));
  }

  private int maxBit(long[] histogram) {
    int maxBit = 0;
    for (int i = 0 ; i < histogram.length ; i++) {
      if (histogram[i] != 0) {
        maxBit = i+1; // Counting from 0
      }
    }
    return maxBit;
  }

  public void testSimplePerformance() {
    testPerformance(pad(10000, 2000, 10, 3, 2, 1), 10000);
  }

  public void testLargePerformance() {
    testPerformance(reduce(TestLongTailBitPlaneMutable.links20150209, 5), 10*M);
  }

  private long[] reduce(long[] values, int divisor) {
    final long[] result = new long[values.length];
    for (int i = 0 ; i < values.length ; i++) {
      result[i] = values[i] / divisor;
    }
    return result;
  }

  private void testPerformance(long[] histogram, int updates) {
    final PackedInts.Reader maxima = TestLongTailBitPlaneMutable.getMaxima(histogram);
    System.out.println("Testing " + maxima.size() + " values with max bit " + maxBit(histogram));

    final LongTailBitPlaneMutable ltbpm = new LongTailBitPlaneMutable(maxima);
    final PackedInts.Mutable ltm = LongTailMutable.create(histogram, 0.99);
    final PackedInts.Mutable packed = PackedInts.getMutable(maxima.size(), maxBit(histogram), PackedInts.FAST);
    System.out.println(String.format("Memory usage: ltbpm=%dMB (%dMB), ltm=%dMB, packed=%dMB",
        ltbpm.ramBytesUsed()/M, ltbpm.ramBytesUsed(true)/M, ltm.ramBytesUsed()/M, packed.ramBytesUsed()/M));

    for (int i = 0 ; i < 10 ; i++) {
      final long seed = random().nextLong();
      {
        ltbpm.clear();
        double avg = measure(maxima, ltbpm, updates, new Random(seed));
        System.out.println(String.format("LongTailBitPlaneMutable: %5d updates/ms", (long)avg));
      }
      {
        ltm.clear();
        double avg = measure(maxima, ltm, updates, new Random(seed));
        System.out.println(String.format("LongTailMutable:         %5d updates/ms", (long)avg));
      }
      {
        packed.clear();
        double avg = measure(maxima, packed, updates, new Random(seed));
        System.out.println(String.format("Packed:                  %5d updates/ms", (long)avg));
      }
      System.out.println("");
    }
  }

  // Runs a performance test and reports updates/ms
  private double measure(
      PackedInts.Reader maxima, PackedInts.Mutable counters, long updates, Random random) {
    assertEquals("maxima must have the same lengthas counters", maxima.size(), counters.size());
    final PackedInts.Mutable tracker =
        PackedInts.getMutable(maxima.size(), maxima.getBitsPerValue(), PackedInts.FAST);
    final Incrementable incCounters = counters instanceof Incrementable ?
        (Incrementable)counters :
        new Incrementable.IncrementableMutable(counters);

    long start = System.nanoTime();
    for (int i = 0 ; i < updates ; i++) {
      int index = random.nextInt(counters.size());
      while (tracker.get(index) == maxima.get(index)) {
        if (++index == counters.size()) {
          index = 0;
        }
      }
      tracker.set(index, tracker.get(index)+1);
      incCounters.inc(index);
    }
    return ((double)updates)/(System.nanoTime()-start)*1000000;
  }

  public void testNonViability() {
    LongTailMutable.Estimate estimate = new LongTailMutable.Estimate(100*M, pad(
        1000,
        100,
        2,
        3
    ));
    // 2+3=5 and there are 2^2=4 pointers
    assertFalse("The layout should not be viable for tailBPV=2", estimate.isViable(2));
  }

  public void testEstimate(String designation, long[] histogram, boolean table) {
    long uniqueCount = LongTailMutable.totalCounters(histogram);
    LongTailMutable.Estimate estimate = new LongTailMutable.Estimate(uniqueCount, histogram);
    final double PACKED_MB = uniqueCount*estimate.getMaxBPV()/8 / 1024.0 / 1024;
    System.out.println(String.format(Locale.ENGLISH,
        table ?
            "<table style=\"width: 80%%\"><caption>%s: %s uniques, Packed64 size: %.0fMB</caption>\n" +
                "<tr style=\"text-align: right\"><th>tailBPV</th> <th>mem</th> <th>saved</th> <th>headCounters</th></tr>" :
            "%s: %s uniques, Packed64 size: %.0fMB",
        designation, uniqueCount < 10*M ? uniqueCount/1000 + "K>" : uniqueCount/M + "M", PACKED_MB));
    for (int tailBPV = 0 ; tailBPV < 64 ; tailBPV++) {
      if (estimate.isViable(tailBPV) && estimate.getFractionEstimate(tailBPV) <= 1.0) {
        double mb = estimate.getMemEstimate(tailBPV) / 1024.0 / 1024;
        System.out.println(String.format(Locale.ENGLISH,
            table ?
                "<tr style=\"text-align: right\"><td>%2d</td> <td>%4.0fMB</td> <td>%3.0fMB / %2.0f%%</td> <td>%4d</td></tr>" :
                "tailBPV=%2d mem=%4.0fMB (%3.0fMB / %2.0f%% saved) headCounters=%4d (%6.4f%%)",
            tailBPV, mb, PACKED_MB-mb,
            // TODO: Split in fast and slow head
            (1-estimate.getFractionEstimate(tailBPV))*100, estimate.getHeadValueCount(tailBPV),
            estimate.getHeadValueCount(tailBPV)*100.0/uniqueCount));
      }
    }
    if (table) {
      System.out.println("</table>");
    }
  }

  private long[] getURLShard1Histogram() { // Taken from URL from shard 1 in netarchive.dk
    // 228M uniques
    return pad(
        196552211,
        20504581,
        5626432,
        3187738,
        1123002,
        411353,
        164206,
        81438,
        10421, // 256
        2252,
        2526,
        100,
        6      // 4096
    );
  }

  private long[] SHARD1_HOST = pad(
      // 1562680 uniques
      194715,
      181562,
      178020,
      177279,
      168170,
      129294,
      90654,
      60348,
      39070,
      22778,
      13934,
      8515,
      5829,
      3876,
      1799,
      556,
      153,
      44,
      22,
      17,
      6,
      3,
      1
  );

  private long[] getURLShard2Histogram() { // Taken from URL in netarchive.dk
    // 230M uniques
    return pad(
        213596462,
        7814717,
        3496320,
        2446379,
        1370559,
        474296,
        207471,
        91941,
        18593,
        3467,
        2754,
        150,
        7
    );
  }
  private long[] getURLShard3Histogram() { // Taken from URL in netarchive.dk
    // 214M uniques
    return pad(
        188429984,
        17107714,
        4977514,
        2431354,
        946224,
        361199,
        140717,
        65064,
        6183,
        2278,
        1996,
        64,
        3
    );
  }

  /*

  0000
  0000

  1000
  0000

  0000
  1000
  100
  000

  1000
  1000
  100
  000

  0000
  1000
  000
  100
  10
  00

   */

  public void testBitPlanePacking() {
    final long VALUES = 519*M;
    final long[] histogram = getLinksHistogram();

    double totalBits = 2*VALUES; // Base
    System.out.println(String.format("hist=%10d, bits=%d, total=%dMB", VALUES, 0, (int)(totalBits/8/M)));

    for (int bits = 1 ; bits < 64 ; bits++) {
      totalBits += 2 * histogram[bits-1];
      System.out.println(String.format("hist=%10d, bits=%d, total=%dMB", histogram[bits-1], bits, (int)(totalBits/8/M)));
      if (histogram[bits-1] == 0) {
        break; // Shouldn't this reach 0 automatically?
      }
    }

    System.out.println(String.format("%dMB/%dMB: %4.2f", (long)(totalBits/8/M), VALUES*4/M, totalBits / (VALUES*32)));
  }

  // if (histogram[bits+1] < histogram[bits]/2) collapse

  /**
   * Current optimal space solution: Keep 1 bitplane (bitmap) for every bit in the number and 1 bitplane for
   * signaling overflow. If a subsequent bitplane is more than half the size of the previous one, the two will
   * share overflow bit.
   * </p><p>
   * As the overflow bits must be counted to infer the index of the next bit position for a given counter,
   * the raw version is extremely slow, requiring billions of bit-checks to update a single counter.
   */
  public void testMultiBitPlanePacking() {
    final long VALUES = 519*M;
    final long[] histogram = getLinksHistogram();

    final long[] values = new long[histogram.length+2];
    System.arraycopy(histogram, 0, values, 0, histogram.length);
    values[0] = 519*M;

    double totalBits = 0;
    int bits = 0;
    while (bits < 64) {
      long bitmapLength = values[bits];
      totalBits += bitmapLength;
      if (values[bits+1] < values[bits]/2) { // share overflow bits
        System.out.println(String.format(
            "Collapsing bit %d+%d (%d, %d values)", bits, bits+1, values[bits], values[bits+1]));
        totalBits += bitmapLength;
        bits++;
      } else if (values[bits] != 0) {
        System.out.println(String.format(
            "Plain storage of bit %d (%d values)", bits, values[bits]));
      }
      totalBits += bitmapLength;
      bits++;
    }
    System.out.println(String.format("%dMB/%dMB: %4.2f", (long)(totalBits/8/M), VALUES*4/M, totalBits / (VALUES*32)));
  }

  public static long[] getLinksHistogram() {
    return pad( // Taken from links in a 8/9 build shard from netarchive.dk
        // 519M uniques
        351962313,
        64785381,
        42315979,
        26072745,
        14361240,
        8035509,
        4611177,
        2686949,
        1659800,
        1005294,
        632518,
        368445,
        208885,
        94266,
        32975,
        8196,
        2807,
        1755,
        465,
        540,
        311,
        58
    );
  }

  public static long[] pad(long... maxCounts) {
    long[] full = new long[64];
    System.arraycopy(maxCounts, 0, full, 0, maxCounts.length);
    return full;
  }
}