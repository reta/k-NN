package org.opensearch.knn.index.engine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import lombok.NonNull;

public final class KNNEngines {
    private static final Map<String, KNNEngine> engines = new ConcurrentHashMap<>();

    public static void register(final KNNEngine engine) {
        engines.putIfAbsent(engine.getName().toLowerCase(), engine);
    }

    public static @NonNull KNNEngine getEngine(String name) {
        final KNNEngine engine = engines.get(name.toLowerCase());

        if (engine == null) {
            throw new IllegalArgumentException(String.format("Invalid engine type: %s", name));
        }

        return engine;
    }
}
