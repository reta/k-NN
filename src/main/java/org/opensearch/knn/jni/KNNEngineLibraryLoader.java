/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.jni;

import static org.opensearch.knn.index.KNNSettings.isFaissAVX2Disabled;
import static org.opensearch.knn.index.KNNSettings.isFaissAVX512Disabled;
import static org.opensearch.knn.index.KNNSettings.isFaissAVX512SPRDisabled;
import static org.opensearch.knn.jni.PlatformUtils.isAVX2SupportedBySystem;
import static org.opensearch.knn.jni.PlatformUtils.isAVX512SupportedBySystem;

import org.opensearch.knn.common.KNNConstants;

import static org.opensearch.knn.jni.PlatformUtils.isAVX512SPRSupportedBySystem;

public class KNNEngineLibraryLoader extends KNNLibraryLoader {
    /**
     * Loads the appropriate Faiss library based on system capabilities and settings.
     *
     * Selects the highest performance variant available:
     * 1. AVX512 SPR if supported and not disabled
     * 2. AVX512 if supported and not disabled
     * 3. AVX2 if supported and not disabled
     * 4. Default fallback library
     */
    static void loadFaissLibrary() {
        if (!isFaissAVX512SPRDisabled() && isAVX512SPRSupportedBySystem()) {
            loadLibrary(KNNConstants.FAISS_AVX512_SPR_JNI_LIBRARY_NAME);
        } else if (!isFaissAVX512Disabled() && isAVX512SupportedBySystem()) {
            loadLibrary(KNNConstants.FAISS_AVX512_JNI_LIBRARY_NAME);
        } else if (!isFaissAVX2Disabled() && isAVX2SupportedBySystem()) {
            loadLibrary(KNNConstants.FAISS_AVX2_JNI_LIBRARY_NAME);
        } else {
            loadLibrary(KNNConstants.FAISS_JNI_LIBRARY_NAME);
        }
    }

    /**
     * Loads the NMSLIB JNI library for nearest neighbor search operations.
     */
    static void loadNmslibLibrary() {
        loadLibrary(KNNConstants.NMSLIB_JNI_LIBRARY_NAME);
    }

    /**
     * Loads the appropriate SIMD computing library based on system capabilities.
     *
     * Follows the same selection logic as Faiss library:
     * 1. AVX512 SPR variant if supported and not disabled
     * 2. AVX512 variant if supported and not disabled
     * 3. AVX2 variant if supported and not disabled
     * 4. Default variant as fallback
     */
    static void loadSimdLibrary() {
        if (!isFaissAVX512SPRDisabled() && isAVX512SPRSupportedBySystem()) {
            loadLibrary(KNNConstants.SIMD_COMPUTING_AVX512_SPR_JNI_LIBRARY_NAME);
        } else if (!isFaissAVX512Disabled() && isAVX512SupportedBySystem()) {
            loadLibrary(KNNConstants.SIMD_COMPUTING_AVX512_JNI_LIBRARY_NAME);
        } else if (!isFaissAVX2Disabled() && isAVX2SupportedBySystem()) {
            loadLibrary(KNNConstants.SIMD_COMPUTING_AVX2_JNI_LIBRARY_NAME);
        } else {
            loadLibrary(KNNConstants.DEFAULT_SIMD_COMPUTING_JNI_LIBRARY_NAME);
        }
    }
}
