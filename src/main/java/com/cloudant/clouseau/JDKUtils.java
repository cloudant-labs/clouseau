package com.cloudant.clouseau;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

class JDKUtils {

    // Use Map.of in JDK 11+
    static <K, V> Map<K, V> mapOf(final K k, final V v) {
        return Collections.singletonMap(k, v);
    }

    // Use Map.of in JDK 11+
    static <K, V> Map<K, V> mapOf(final K k1, final V v1, final K k2, final V v2) {
        final Map<K, V> result = new HashMap<K, V>(2);
        result.put(k1, v1);
        result.put(k2, v2);
        return Collections.unmodifiableMap(result);
    }

}
