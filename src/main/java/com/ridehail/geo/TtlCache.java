package com.ridehail.geo;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** A small thread-safe cache: least recently used out first when full, and entries expire after a fixed time. */
final class TtlCache<K, V> {
    private record Entry<V>(V value, Instant expires) {}

    private final Map<K, Entry<V>> map;
    private final Duration ttl;
    private final Clock clock;

    TtlCache(int capacity, Duration ttl, Clock clock) {
        this.ttl = ttl;
        this.clock = clock;
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<K, Entry<V>> eldest) { return size() > capacity; }
        };
    }

    synchronized V get(K key) {
        Entry<V> e = map.get(key);
        if (e == null) return null;
        if (!clock.instant().isBefore(e.expires())) { map.remove(key); return null; }
        return e.value();
    }

    synchronized void put(K key, V value) { map.put(key, new Entry<>(value, clock.instant().plus(ttl))); }
}
