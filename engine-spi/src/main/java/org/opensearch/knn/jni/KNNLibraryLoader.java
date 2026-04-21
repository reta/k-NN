/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.jni;

import lombok.extern.log4j.Log4j2;
import org.opensearch.knn.common.KNNConstants;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.HashSet;
import java.util.Set;

/**
 * Thread-safe loader for KNN native libraries.
 *
 * Ensures each library is loaded exactly once across all threads using synchronized loading
 * and tracking of loaded libraries. All library loading is performed with appropriate
 * security privileges.
 *
 * Note: this is the only class that is allowed to load libraries, and all non-private
 * methods are automatically tested.
 */
@Log4j2
public class KNNLibraryLoader {
    /** Set of already loaded library names to prevent duplicate loading */
    static protected Set<String> loaded = new HashSet<>();
    /** Lock object for synchronizing library loading operations */
    static final Object lock = new Object();

    /**
     * Thread-safe library loading with duplicate prevention.
     *
     * @param name the library name to load
     */
    @SuppressWarnings("removal")
    static void loadLibrary(String name) {
        synchronized (lock) {
            if (loaded.contains(name)) {
                log.info("Library already loaded: {}", name);
                return;
            }
            try {
                AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
                    System.loadLibrary(name);
                    return null;
                });
                loaded.add(name);
            } catch (UnsatisfiedLinkError e) {
                log.error("Failed to load library: {}", name);
                throw e;
            }
            log.info("Loaded library: {}", name);
        }
    }

    /**
     * Loads the common JNI library containing shared functionality.
     */
    static void loadCommonLibrary() {
        loadLibrary(KNNConstants.COMMON_JNI_LIBRARY_NAME);
    }

}
