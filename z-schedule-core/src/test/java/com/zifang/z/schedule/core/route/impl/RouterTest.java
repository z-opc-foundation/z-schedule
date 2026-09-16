package io.github.yuku123.z.schedule.core.route.impl;

import io.github.yuku123.z.schedule.core.route.ExecutorRouter;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

/**
 * 各种 {@link ExecutorRouter} 实现的单元测试.
 */
public class RouterTest {

    private static final List<String> ADDRESSES = Arrays.asList(
            "127.0.0.1:8081", "127.0.0.1:8082", "127.0.0.1:8083");

    @Test
    public void testRandomReturnsKnownAddress() {
        RandomRouter router = new RandomRouter();
        for (int i = 0; i < 50; i++) {
            String a = router.route(ADDRESSES, i);
            assertNotNull(a);
            assertTrue("Random returned unknown address: " + a, ADDRESSES.contains(a));
        }
    }

    @Test
    public void testRoundRobinCyclesThroughAll() {
        RoundRobinRouter router = new RoundRobinRouter();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < ADDRESSES.size(); i++) {
            seen.add(router.route(ADDRESSES, 1));
        }
        assertEquals("RoundRobin should cover all addresses in one full cycle", ADDRESSES.size(), seen.size());
    }

    @Test
    public void testRoundRobinIsStablePerJobId() {
        // 不同 jobId 各自独立计数
        RoundRobinRouter r1 = new RoundRobinRouter();
        String a1 = r1.route(ADDRESSES, 100);
        String a2 = r1.route(ADDRESSES, 200);
        // 同一个 jobId 100 上连续两次应该推进一个地址
        String a1Next = r1.route(ADDRESSES, 100);
        assertNotNull(a1);
        assertNotNull(a2);
        assertNotNull(a1Next);
        assertFalse("Same jobId should rotate", a1.equals(a1Next));
    }

    @Test
    public void testConsistentHashStable() {
        ConsistentHashRouter router = new ConsistentHashRouter();
        String first = router.route(ADDRESSES, 42);
        for (int i = 0; i < 10; i++) {
            assertEquals("ConsistentHash must be stable for the same jobId", first, router.route(ADDRESSES, 42));
        }
        assertTrue(ADDRESSES.contains(first));
    }

    @Test
    public void testFailoverReturnsFirst() {
        FailoverRouter router = new FailoverRouter();
        assertEquals(ADDRESSES.get(0), router.route(ADDRESSES, 1));
    }

    @Test
    public void testShardingBroadcastReturnsAll() {
        ShardingBroadcastRouter router = new ShardingBroadcastRouter();
        String result = router.route(ADDRESSES, 1);
        assertNotNull(result);
        for (String addr : ADDRESSES) {
            assertTrue("Sharding broadcast should contain " + addr, result.contains(addr));
        }
    }

    @Test
    public void testEmptyAddressList() {
        assertNull(new RandomRouter().route(new ArrayList<String>(), 1));
        assertNull(new RoundRobinRouter().route(null, 1));
        assertNull(new ConsistentHashRouter().route(new ArrayList<String>(), 1));
        assertNull(new FailoverRouter().route(new ArrayList<String>(), 1));
        assertNull(new ShardingBroadcastRouter().route(null, 1));
    }

    @Test
    public void testSingleAddressAlwaysReturned() {
        List<String> one = Arrays.asList("127.0.0.1:9999");
        assertEquals("127.0.0.1:9999", new RandomRouter().route(one, 1));
        assertEquals("127.0.0.1:9999", new RoundRobinRouter().route(one, 1));
        assertEquals("127.0.0.1:9999", new ConsistentHashRouter().route(one, 1));
        assertEquals("127.0.0.1:9999", new FailoverRouter().route(one, 1));
        assertEquals("127.0.0.1:9999", new ShardingBroadcastRouter().route(one, 1));
    }
}
