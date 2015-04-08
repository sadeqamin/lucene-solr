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

import org.apache.lucene.util.LuceneTestCase;
import org.apache.lucene.util.LuceneTestCase.Slow;

import java.util.Locale;

@Slow
public class TestNPlaneMutable extends LuceneTestCase {
  private final static int M = 1048576;

  // Once triggered an ArrayIndexOutOfBoundsException, now just runs random updates
  public void testMonkey() {
    final int DIVISOR = 500;
    final int[] UPDATES = new int[] {M};
    final int[] CACHES = new int[] {200};
    final int[] MAX_PLANES = new int[] {4};
    LongTailPerformance.measurePerformance(LongTailPerformance.reduce(LongTailPerformance.links20150209, DIVISOR),
        9, 9/2, 2, UPDATES, CACHES, MAX_PLANES, Integer.MAX_VALUE);
  }

  public void testArrayIndexOutOfBoundsCase() {
    final double DIVISOR = 1/0.01;
    final int[] UPDATES = new int[] {M};
    final int[] CACHES = new int[] {100, 20};
    final int[] MAX_PLANES = new int[] {4, 64};

    LongTailPerformance.measurePerformance(LongTailPerformance.reduce(LongTailPerformance.links20150209, DIVISOR),
        9, 9/2, 1, UPDATES, CACHES, MAX_PLANES, Integer.MAX_VALUE);
//     -u 1 5 10 50 100 -c 100 20 -p 4 64 -t 1 -i 1 -e 8
  }

  public void testOverflow() {
    final int DIVISOR = 500;
    final int CACHE = 1000;
    final int MAX_PLANES = 4;
    final int[] INCREMENTS = new int[]{999, 999};//12345, 1, 12345, 7, 1024, 999, 1000, 999, 1000};
    long[] histogram = LongTailPerformance.reduce(LongTailPerformance.links20150209, DIVISOR);

    final PackedInts.Reader maxima = LongTailPerformance.getMaxima(histogram);
    NPlaneMutable nplane =
        new NPlaneMutable(new NPlaneMutable.BPVPackedWrapper(maxima, false), CACHE, MAX_PLANES,
            NPlaneMutable.DEFAULT_COLLAPSE_FRACTION, NPlaneMutable.IMPL.shift);

    checkOverflow("Before increment", nplane);
    for (int i = 0; i < INCREMENTS.length; i++) {
      int inc = INCREMENTS[i];
      while (maxima.get(inc) <= 1) {
        inc++;
        if (inc > maxima.size()) {
          inc = 0;
        }
      }
      nplane.inc(inc);
      checkOverflow("After inc(" + inc + ") #" + (i+1), nplane);
    }
  }

  private void checkOverflow(String message, NPlaneMutable nplane) {
    for (int planeIndex = 0 ; planeIndex < nplane.planes.length ; planeIndex++) {
      NPlaneMutable.Plane plane = nplane.planes[planeIndex];
      if (!plane.hasOverflow) {
        continue;
      }
/*      for (int o = 0 ; o < 80 ; o++) {
        System.out.print(plane.isOverflow(o) ? "*" : ".");
      }
      System.out.println(" Plane " + planeIndex);
      for (int o = 0 ; o < 80 ; o++) {
        System.out.print((char)(plane.overflowRank(o) + 'a'));
      }
      System.out.println();*/
      int overflows = 0;
      for (int i = 0 ; i < plane.valueCount ; i++) {
        if (plane.isOverflow(i)) {
          overflows++;
          assertEquals(message + ". Rank should return #overflows-1 when the current overflow bit is set @ index " + i,
              overflows-1, plane.overflowRank(i));
        } else if (overflows > 0) {
          assertEquals(message +". Rank should return #overflows when the current overflow bit is not set @ index " + i,
              overflows, plane.overflowRank(i));
        }
      }
      if (planeIndex != 0) {
        assertEquals(message + ". The number of set overflows for plane " + planeIndex + " should match plane "
            + planeIndex+1,
            nplane.planes[planeIndex+1].valueCount, overflows);
      }
    }
  }

  public void testSmallAdd() {
    final int[] MAXIMA = new int[]{10, 1, 16, 2, 3};
    final int MAX = 16;
    final PackedInts.Mutable maxima =
        PackedInts.getMutable(MAXIMA.length, PackedInts.bitsRequired(MAX), PackedInts.COMPACT);
    for (int i = 0 ; i < MAXIMA.length ; i++) {
      maxima.set(i, MAXIMA[i]);
    }
    System.out.println("maxima: " + toString(maxima));

    PackedInts.Mutable bpm = new NPlaneMutable(maxima);
    bpm.set(1, bpm.get(1)+1);
    assertEquals("Test 1: index 1", 1, bpm.get(1));
    assertEquals("The unmodified counter 0 should be zero", 0 , bpm.get(0));
    bpm.set(0, bpm.get(0)+1);
    assertEquals("Test 2: index 0", 1, bpm.get(0));
    bpm.set(0, bpm.get(0)+1);
    bpm.set(0, bpm.get(0)+1);
    assertEquals("Test 3: index 0", 3, bpm.get(0));
    bpm.set(0, bpm.get(0)+1);
    assertEquals("Test 4: index 0", 4, bpm.get(0));
    bpm.set(2, bpm.get(2)+1);
  }

  public void testNPlaneLayout() {
    long EXPECTED = 400;
    long[] histogram = LongTailPerformance.reduce(LongTailPerformance.links20150209, 1.0);

    long[] full = NPlaneMutable.directHistogramToFullZero(histogram);
    NPlaneMutable.Layout layout = NPlaneMutable.getLayout(full, 0, 64, NPlaneMutable.DEFAULT_COLLAPSE_FRACTION);
    assertTrue("There should be more than 3 planes", layout.size() > 3);
    long mem = 0;
    for (NPlaneMutable.PseudoPlane plane: layout) {
      mem += plane.estimateBytesNeeded(false, NPlaneMutable.IMPL.spank);
    }
    assertTrue("The size should be less than " + EXPECTED + "MB, but was " + mem / M + "MB",
        EXPECTED * M > mem);

    final long estimated = NPlaneMutable.estimateBytesNeeded(
        histogram, 0, 64, NPlaneMutable.DEFAULT_COLLAPSE_FRACTION, false, NPlaneMutable.IMPL.spank);
    assertTrue("The estimated size should be less than " + EXPECTED + "MB, but was " + estimated / M + "MB",
        EXPECTED * M > estimated);
//    System.out.println(String.format("%d planes, PseudoPlane mem %dMB, estimated mem %dMB",
//        layout.size(), mem/M, estimated/M));
  }

  public void testSmallInc() {
    final PackedInts.Mutable maxima = toMutable(10, 1, 16, 2, 3);
    System.out.println("maxima: " + toString(maxima));
    NPlaneMutable bpm = new NPlaneMutable(maxima);

    bpm.inc(1);
    assertEquals("Test 1: index 1", 1, bpm.get(1));
    assertEquals("The unmodified counter 0 should be zero", 0, bpm.get(0));
    bpm.inc(0);
    assertEquals("Test 2: index 0", 1, bpm.get(0));
    bpm.inc(0);
    bpm.inc(0);
    assertEquals("Test 3: index 0", 3, bpm.get(0));
    bpm.inc(0);
    assertEquals("Test 4: index 0", 4, bpm.get(0));
    bpm.inc(2);
  }

  public void testOverflowCache() {
    final PackedInts.Mutable maxima = toMutable(10, 1, 16, 2, 3, 2, 3, 100, 140);
    NPlaneMutable bpm = new NPlaneMutable(maxima, 5);
    final int[][] TESTS = new int[][]{
        {8, 14},
        {7, 50},
        {4, 3},
        {2, 7},
        {5, 1}
    };
    for (int[] test: TESTS) {
      assertValue(bpm, test[0], 0);
      bpm.set(test[0], test[1]);
      assertValue(bpm, test[0], test[1]);
//      System.out.println(bpm.toString(true));
    }
    for (int i = 0 ; i < maxima.size() ; i++) {
      bpm.set(i, maxima.get(i));
      assertValue(bpm, i, maxima.get(i));
    }
  }

  private void assertValue(PackedInts.Mutable maxima, int index, long expected) {
    assertEquals("The value at position " + index + " should be correct", expected, maxima.get(index));
  }

  public static PackedInts.Mutable toMutable(int... maxValues) {
    int maxMax = 0;
    for (int maxValue: maxValues) {
      if (maxValue > maxMax) {
        maxMax = maxValue;
      }
    }
    final PackedInts.Mutable maxima =
        PackedInts.getMutable(maxValues.length, PackedInts.bitsRequired(maxMax), PackedInts.COMPACT);
    for (int i = 0 ; i < maxValues.length ; i++) {
      maxima.set(i, maxValues[i]);
    }
    return maxima;
  }

  public void testRandom() {
    final int COUNTERS = 100;
    final int MAX = 1000;
    final int updates = M/100;
    final PackedInts.Reader maxima = getMaxima(COUNTERS, MAX);

    assertMonkey(maxima, updates);
  }

  public void testRandomSmallLongTail() {
//    PackedInts.Reader maxima = getMaxima(TestDualPlaneMutable.getLinksHistogram());
    PackedInts.Reader maxima = LongTailPerformance.getMaxima(LongTailPerformance.pad(1, 3, 2)); // 1 + 3*3 + 2*7 = 24
    assertMonkey(maxima, (int) sum(maxima));
  }

  private long sum(PackedInts.Reader values) {
    long total = 0;
    for (int i = 0 ; i < values.size() ; i++) {
      total += values.get(i);
    }
    return total;
  }

  public void testRandomRealWorldHistogramLongTail() {
    assertMonkey(LongTailPerformance.getMaxima(LongTailPerformance.reduce(LongTailPerformance.links20150209, 10)), M);
  }

  public void testBytesEstimation() {
    System.out.println(String.format(Locale.ENGLISH, "ltbpm=%d/%d/%dMB",
        NPlaneMutable.estimateBytesNeeded(LongTailPerformance.links20150209) / M,
        640280533L*(NPlaneMutable.getMaxBit(LongTailPerformance.links20150209)+1)/8/M,
        640280533L*4/M));
  }

  public void disabledtestAssignRealLargeSample() {
    PackedInts.Reader maxima = LongTailPerformance.getMaxima(LongTailPerformance.links20150209);
    NPlaneMutable bpm = new NPlaneMutable(maxima);
    for (int i = 0 ; i < maxima.size() ; i++) {
      bpm.set(i, maxima.get(i));
      assertEquals("The set max value at index " + i + " should be correct", maxima.get(i), bpm.get(i));
    }
    for (int i = 0 ; i < maxima.size() ; i++) {
      assertEquals("The previously set value at index " + i + " should be correct", maxima.get(i), bpm.get(i));
    }
    for (int i = 0 ; i < maxima.size() ; i++) {
      bpm.set(i, maxima.get(i)-1);
      assertEquals("The set max-1 value at index " + i + " should be correct", maxima.get(i)-1, bpm.get(i));
    }
    for (int i = 0 ; i < maxima.size() ; i++) {
      bpm.inc(i);
      assertEquals("The set max-1 value + inc at index " + i + " should be correct", maxima.get(i), bpm.get(i));
    }
  }

  private void assertMonkey(PackedInts.Reader maxima, int updates) {
    NPlaneMutable bpm = new NPlaneMutable(maxima);
    PackedInts.Mutable expected = PackedInts.getMutable(bpm.size(), bpm.getBitsPerValue(), PackedInts.FASTEST);
    System.out.println(String.format(Locale.ENGLISH, "Memory used: %d/%dMB (%4.2f%%)",
        bpm.ramBytesUsed()/M, maxima.ramBytesUsed()/M, bpm.ramBytesUsed() * 100.0 / maxima.ramBytesUsed()));
    for (int update = 0 ; update < updates ; update++) {
      int index = random().nextInt(maxima.size());
      int oldIndex = -1;
      while (expected.get(index) >= maxima.get(index)) {
        if (oldIndex == -1) {
          oldIndex = index;
        }
        index++;
        if (index == maxima.size()) {
          index = 0;
        }
        if (oldIndex == index) {
            fail("Unable to generate sample as the number of updates (" + updates + ") is higher than the " +
                "collective counts");
        }
      }
      expected.set(index, expected.get(index)+1);
      try {
        bpm.inc(index);
//        bpm.set(index, bpm.get(index));
      } catch (Exception e) {
        fail("Unexpected exception calling bmp.inc(" + index + "): " +  e.getMessage());
      }
      try {
        assertEquals("After " + (update+1) + " updates the BPM-value should be as expected",
            expected.get(index), bpm.get(index));
      } catch (Exception e) {
        fail("Unexpected exception calling bmp.get(" + index + "): " + e.getMessage());
      }
    }
  }

  private String toString(PackedInts.Reader maxima) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0 ; i < maxima.size() ; i++) {
      if (sb.length() > 0) {
        sb.append(", ");
      }
      sb.append(Long.toString(maxima.get(i)));
    }
    return sb.toString();
  }

  private PackedInts.Reader getMaxima(int counters, int max) {
    final PackedInts.Mutable maxima = PackedInts.getMutable(counters, 30, PackedInts.FASTEST);
    for (int i = 0 ; i < counters ; i++) {
      maxima.set(i, random().nextInt(max-1)+1);
    }
    return maxima;
  }

  public static PackedInts.Reader oldGetMaxima(long[] histogram) {
    System.out.println("Creating random maxima from histogram...");
    long valueCount = 0;
    long maxValueBits = 0;
    long valueBits = 0;
    for (long h: histogram) {
      valueBits++;
      if (h != 0) {
        valueCount += h;
        maxValueBits = valueBits;
      }
    }
    PackedInts.Mutable maxima =
        PackedInts.getMutable((int) valueCount, (int) maxValueBits, PackedInts.FASTEST);
    int maxpos = 0;
    for (int valueBit = 1 ; valueBit <= maxValueBits; valueBit++) {
      long val = (long) Math.pow(2, valueBit)-1;
      for (int i = 0 ; i < histogram[valueBit-1] ; i++) {
        maxima.set(maxpos, val);
        maxpos++;
      }
    }
    System.out.println("Shuffling maxima...");
    shuffle(maxima);
    System.out.println("Finished maxima creation");
    return maxima;
  }

  // Fisher–Yates shuffle
  private static void shuffle(PackedInts.Mutable maxima) {
    for (int i = maxima.size()-1 ; i > 0 ; i--) {
      final int index = random().nextInt(i+1);
      long val = maxima.get(index);
      maxima.set(index, maxima.get(i));
      maxima.set(i, val);
    }
  }
}
