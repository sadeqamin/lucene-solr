package org.apache.lucene.search;

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

import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

import org.apache.lucene.util.LuceneTestCase;

/**
 * This test is an explorative performance test.
 *
 * The goal is to determine if there are any gains in switching from the Object-heavy HitQueue in Solr to an
 * array-based equivalent. Candidates so far are {@link HitQueueArray} and
 * {@link HitQueuePacked}. Note that the focus for now is performance. As such, the new
 * classes has only loosely (through debugging runs) been inspected for correctness and probably contains errors.
 *
 * The test does not emulate a full search. It only allocates, randomly fills and empties the queues.
 *
 * When running the tests, there are 4 different designations:
 * - Sentinel:   The default Solr HitQueue with sentinel objects, used for standard Solr top-X searches.
 * - No_Sentinel: Same as Sentinel, but without sentinel objects.
 * - Array:  Instead of storing the heap as an array of Objects, two atomic arrays (one for scores, one for docIDs)
 *           are used.
 * - Packed: Instead of storing the heap as an array of Objects, a single atomic array of longs is used, where score
 *           and docID is packed together.
 */
public class TestBHeap extends LuceneTestCase {

  public void test1() {
    BHeap heap = new BHeap(20, 2);
    heap.insert(100);
    assertEquals("Element 1,1 should be correct", 100, heap.elements[5]);
//    heap.dump();
  }

  public void test2() {
    BHeap heap = new BHeap(20, 2);
    heap.insert(100);
    heap.insert(99);
    assertHeap(heap, new long[][]{
        {99, 100}
    });
  }

  public void testFullFirstMiniheap() {
    BHeap heap = new BHeap(20, 2);
    insert(heap, 100, 99, 101);
    assertHeap(heap, new long[][]{
        {99, 100, 101}
    });
  }

  public void testTwoMiniheaps() {
    BHeap heap = new BHeap(20, 2);
    insert(heap, 100, 99, 101);
    insert(heap, 87);
    assertHeap(heap, new long[][]{
        {87, 99, 101},
        {100}
    });
  }

  public void testTinyHeap() {
    BHeap heap = new BHeap(3, 2);
    insert(heap, 100, 99, 101);
    assertHeap(heap, new long[][]{
        {99, 100, 101}
    });
  }

  public void testTinyHeapOverflow() {
    BHeap heap = new BHeap(3, 2);
    insert(heap, 100, 99, 101);
    insert(heap, 102);
    assertHeap(heap, new long[][]{
        {100, 102, 101}
    });
  }

  public void testTwoMiniheapsOverflow() {
    BHeap heap = new BHeap(4, 2);
    insert(heap, 100, 99, 101, 102);
    assertHeap(heap, new long[][]{
        {99, 100, 101},
        {102}
    });
    assertFlush(heap, 99,100, 101, 102);
  }

  public void testAlternateSmall() {
    BHeap heap = new BHeap(20, 2);
    insert(heap, 100, 99, 101, 102);
    assertHeap("Initial", heap, new long[][]{
        {99, 100, 101},
        {102}
    });

    heap.pop();
    assertHeap("Pop 1", heap, new long[][]{
        {100, 102, 101}
    });

    insert(heap, 87);
    assertHeap("Insert 87", heap, new long[][]{
        {87, 100, 101},
        {102}
    });

    insert(heap, 110);
    assertHeap("Insert 110", heap, new long[][]{
        {87, 100, 101},
        {102, 110}
    });

    insert(heap, 115);
    assertHeap("Insert 115", heap, new long[][]{
        {87, 100, 101},
        {102, 110, 115}
    });

    heap.pop();
    assertHeap("Pop 2", heap, new long[][]{
        {100, 102, 101},
        {110, 115}
    });

    heap.pop();
    assertHeap("Pop 3", heap, new long[][]{
        {101, 102, 110},
        {115}
    });

    heap.pop();
    assertHeap("Pop 4", heap, new long[][]{
        {102, 115, 110}
    });

    assertFlush("Final flush", heap, 102, 110, 115);
  }

  public void test1_1() {
    BHeap heap = new BHeap(1, 2);
    insert(heap, 100);
    assertHeap(heap, new long[][]{
        {100}
    });

    insert(heap, 99);
    assertHeap(heap, new long[][]{
        {100}
    });
    insert(heap, 101);
    assertHeap(heap, new long[][]{
        {101}
    });
  }

  public void testTiny_5() {
    BHeap heap = new BHeap(5, 2);

    insert(heap, 2);
    assertHeap("Insert 2", heap, new long[][]{
        {2}
    });

    insert(heap, 4);
    assertHeap("Insert 4", heap, new long[][]{
        {2, 4}
    });

    insert(heap, 1);
    assertHeap("Insert 1", heap, new long[][]{
        {1, 4, 2}
    });

    insert(heap, 3);
    assertHeap("Insert 3", heap, new long[][]{
        {1, 3, 2},
        {4}
    });

    insert(heap, 5);
    assertHeap("Insert 5", heap, new long[][]{
        {1, 3, 2},
        {4, 5}
    });

    heap.pop();
    assertHeap("Pop 1", heap, new long[][]{
        {2, 3, 4},
        {5}
    });

    heap.pop();
    assertHeap("Pop 2", heap, new long[][]{
        {3, 5, 4}
    });

    heap.pop();
    assertHeap("Pop 3", heap, new long[][]{
        {4, 5}
    });

    heap.pop();
    assertHeap("Pop 4", heap, new long[][]{
        {5}
    });
  }

  public void testMonkeySmall() {
    testMonkeyMulti(10, 10, 100, 3);
  }

  // Failed at one point
  public void testMonkeySpecific() {
    testMonkey(1, 5, 19, 2, 87L);
  }

  // ,

  public void testMonkeyReproduced2() {
    final long[] INSERTS = new long[]{
        1559930263, 1185591563, 1905463594, 992500083, 1551741466, 849278534, 959321707, 1614690370, 1027113656,
        367197353, 1398133165, 323706493, 1910156708, 1045165184, 1484036190, 250637342, 746926416, 653656415,
        1564936362
    };
    final int lastCount = 5;
    assertInsertExtract(lastCount, INSERTS);
  }
  public void testMonkeyReproduced2b() {
    final long[] INSERTS = new long[]{
        20, 10, 70, 40, 14, 60, 80, 5, 50
    };
    assertInsertExtract(5, INSERTS);
  }
  public void testMonkeyReproduced2c() {
    //  20, 10, 70, 40, 14, 60, 80, 5, 50

    BHeap heap = new BHeap(5, 2);

    insertAssert(heap, new long[][]{
        {10, 20, 70}
    }, 20, 10, 70);

    insertAssert(heap, new long[][]{
        {10, 20, 70},
        {40}
    }, 40);

    insertAssert(heap, new long[][]{
        {10, 14, 70},
        {20, 40}
    }, 14);

    insertAssert(heap, new long[][]{
        {14, 20, 70},
        {40, 60}
    }, 60);

    insertAssert(heap, new long[][]{
        {20, 40, 70},
        {60, 80}
    }, 80);

    insertAssert(heap, new long[][]{
        {20, 40, 70},
        {60, 80}
    }, 5);

    insertAssert(heap, new long[][]{
        {40, 50, 70},
        {60, 80}
    }, 50);
  }

  public void testMonkeyReproduced1() {
    final long[] INSERTS = new long[]{
        //1559930263, 1905463594, 959321707, 1614690370, 1910156708
        2, 4, 1, 3, 5
    };
    final int lastCount = 5;

    assertInsertExtract(lastCount, INSERTS);
  }

  private void assertInsertExtract(int heapsize, long[] inserts) {
    BHeap heap = new BHeap(heapsize, 2);
    insert(heap, inserts);

    Arrays.sort(inserts);
    long[] last = new long[heapsize];
    System.arraycopy(inserts, inserts.length-heapsize, last, 0, heapsize);
    System.out.println("Last " + heapsize + ":");
    for (long element : last) {
      System.out.print(" " + Long.toString(element));
    }
    System.out.println();
    assertFlush(heap, last);
  }

  private void testMonkeyMulti(int runs, int maxSize, int maxInserts, int maxExponent) {
    for  (int run = 1 ; run <= runs ; run++) {
      long seed = random().nextLong();
      Random random = new Random(seed);

      int size = random.nextInt(maxSize-1)+1;         // 1 or more
      int inserts = random.nextInt(maxInserts);       // 0 or more
      int exponent = random.nextInt(maxExponent-1)+1; // 1 or more

      testMonkey(run, size, inserts, exponent, seed);
    }
  }

  private void testMonkey(int run, int size, int inserts, int exponent, long seed) {
    Random random = new Random(seed);
    BHeap actual = new BHeap(size, exponent);
    java.util.PriorityQueue<Long> expected = new java.util.PriorityQueue<>();
    String extra = String.format(Locale.ENGLISH, "run=%d, size=%d, inserts=%d, exponent=%d, seed=" + seed,
        run, size, inserts, exponent);

    try {
      for (int i = 0; i < inserts; i++) {
        long element = random.nextInt(size*10);
        actual.insert(element);
        expected.add(element);
        if (expected.size() > size) {
          expected.poll();
        }
        if (i < 10) {
          System.out.print(", " + element);
        }
      }
      System.out.println("");
    } catch (Exception e) {
      throw new IllegalStateException("Unexpected Exception for " + extra, e);
    }

    assertEquals("Size should match for " + extra,
        expected.size(), actual.size());
    for (int i = 1 ; i <= size ; i++) {
      assertEquals(String.format(Locale.ENGLISH, "Elements for pop %d should match for %s\n%s",
          i, extra, actual.toString(true)),
          expected.poll(), new Long(actual.pop()));
    }
  }

  public static void assertFlush(BHeap heap, long... expected) {
    assertFlush("", heap, expected);
  }
  public static void assertFlush(String message, BHeap heap, long... expected) {
    String dump = heap.toString(true);
    for (int i = 0; i < expected.length; i++) {
      assertEquals(message + ". The popped value should match expected[" + i + "]\n" + dump,
          expected[i], heap.pop());
    }
  }

  private void insertAssert(BHeap heap, long[][] expected, long... elements) {
    insertAssert("Inserted " + (elements.length == 1 ? " element " + elements[0] : elements.length + " elements"),
        heap, expected, elements);
  }
  private void insertAssert(String message, BHeap heap, long[][] expected, long... elements) {
    insert(heap, elements);
    assertHeap(message, heap, expected);
  }

  public static void insert(BHeap heap, long... elements) {
    for (long element: elements) {
      heap.insert(element);
    }
  }

  public static void assertHeap(BHeap heap, long[][] content) {
    assertHeap("", heap, content);
  }

  public static void assertHeap(String message, BHeap heap, long[][] content) {
    for (int miniheap = 1 ; miniheap <= content.length ; miniheap++) {
      long[] expected = content[miniheap-1];
      for (int i = 1 ; i < expected.length ; i++) {
        if (expected[i] < expected[0]) {
          fail(message + ". Illegal heap layout specified as expected in test. Top element (" + expected[0] + ") "
              + "must be less than all other elements (" + join(expected) + ")");
        }
      }

      for (int offset = 1 ; offset <= expected.length ; offset++) {
        assertElement(message, heap, miniheap, offset, expected[offset-1]);
      }
    }
  }

  public static void assertElement(String message, BHeap heap, int mhIndex, int mhOffset, long expected) {
    assertEquals(message + ". Element " + mhIndex + ", " + mhOffset + " should be correct\n" + heap.toString(true),
        expected, heap.get(mhIndex, mhOffset));
  }

  private static String join(long[] values) {
    StringBuilder sb = new StringBuilder(values.length*10);
    for (long value: values) {
      if (sb.length() > 0) {
        sb.append(", ");
      }
      sb.append(Long.toString(value));
    }
    return sb.toString();
  }


}
