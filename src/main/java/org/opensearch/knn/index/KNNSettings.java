/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.index;

import lombok.NonNull;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.OpenSearchParseException;
import org.opensearch.action.admin.cluster.settings.ClusterUpdateSettingsRequest;
import org.opensearch.action.admin.cluster.settings.ClusterUpdateSettingsResponse;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.Booleans;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.common.unit.ByteSizeUnit;
import org.opensearch.core.common.unit.ByteSizeValue;
import org.opensearch.index.IndexModule;
import org.opensearch.knn.index.memory.NativeMemoryCacheManager;
import org.opensearch.knn.index.memory.NativeMemoryCacheManagerDto;
import org.opensearch.knn.index.util.IndexHyperParametersUtil;
import org.opensearch.knn.quantization.models.quantizationState.QuantizationStateCacheManager;
import org.opensearch.monitor.jvm.JvmInfo;
import org.opensearch.monitor.os.OsProbe;
import org.opensearch.transport.client.Client;

import java.security.InvalidParameterException;
import org.opensearch.common.util.concurrent.OpenSearchExecutors;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toUnmodifiableMap;
import static org.opensearch.common.Booleans.parseBoolean;
import static org.opensearch.common.settings.Setting.Property.Dynamic;
import static org.opensearch.common.settings.Setting.Property.NodeScope;
import static org.opensearch.core.common.unit.ByteSizeValue.parseBytesSizeValue;
import static org.opensearch.knn.common.featureflags.KNNFeatureFlags.getFeatureFlags;

import static org.opensearch.knn.common.KNNConstants.INDEX_THREAD_QUANTITY_THRESHOLD;
import static org.opensearch.knn.common.KNNConstants.INDEX_THREAD_QUANTITY_DEFAULT_LARGE;
import static org.opensearch.knn.common.KNNConstants.INDEX_THREAD_QUANTITY_DEFAULT_SMALL;
import static org.opensearch.knn.common.featureflags.KNNFeatureFlags.getFeatureFlagsWhichRebuildsCache;

/**
 * This class defines
 * 1. KNN settings to hold the <a href="https://github.com/nmslib/hnswlib/blob/master/ALGO_PARAMS.md">HNSW algorithm parameters</a>.
 * 2. KNN settings to enable/disable plugin, circuit breaker settings
 * 3. KNN settings to manage graphs loaded in native memory
 */
@Log4j2
public class KNNSettings extends KNNCommonSettings {

    private static final Logger logger = LogManager.getLogger(KNNSettings.class);
    private static KNNSettings INSTANCE;
    private static final OsProbe osProbe = OsProbe.getInstance();

    private static final int INDEX_THREAD_QTY_MAX = 32;
    private static final QuantizationStateCacheManager quantizationStateCacheManager = QuantizationStateCacheManager.getInstance();

    /**
     * index_thread_quantity - the parameter specifies how many threads the nms library should use to create the graph.
     * By default, the nms library sets this value to NUM_CORES. However, because ES can spawn NUM_CORES threads for
     * indexing, and each indexing thread calls the NMS library to build the graph, which can also spawn NUM_CORES threads,
     * this could lead to NUM_CORES^2 threads running and could lead to 100% CPU utilization. This setting allows users to
     * configure number of threads for graph construction.
     */
    public static final Setting<Integer> KNN_ALGO_PARAM_INDEX_THREAD_QTY_SETTING = new Setting<>(
        KNN_ALGO_PARAM_INDEX_THREAD_QTY,
        settings -> Integer.toString(getHardwareDefaultIndexThreadQty(settings)),
        s -> {
            int value = Integer.parseInt(s);
            if (value < 1 || value > INDEX_THREAD_QTY_MAX) {
                throw new IllegalArgumentException("Value must be between 1 and " + INDEX_THREAD_QTY_MAX);
            }
            return value;
        },
        NodeScope,
        Dynamic
    );

    /**
     * Dynamic settings
     */
    public static Map<String, Setting<?>> dynamicCacheSettings = new HashMap<String, Setting<?>>() {
        {
            /**
             * Weight circuit breaker settings
             */
            put(KNN_MEMORY_CIRCUIT_BREAKER_ENABLED, Setting.boolSetting(KNN_MEMORY_CIRCUIT_BREAKER_ENABLED, true, NodeScope, Dynamic));

            /**
             * Group setting that manages node-level circuit breaker configurations based on node tiers.
             * Settings under this group define memory limits for different node classifications.
             * Validation of limit occurs before the setting is retrieved.
             */
            put(
                KNN_MEMORY_CIRCUIT_BREAKER_LIMIT_PREFIX,
                Setting.groupSetting(KNNSettings.KNN_MEMORY_CIRCUIT_BREAKER_LIMIT_PREFIX, settings -> {
                    settings.keySet()
                        .forEach(
                            (limit) -> parseknnMemoryCircuitBreakerValue(
                                settings.get(limit),
                                KNNSettings.KNN_MEMORY_CIRCUIT_BREAKER_CLUSTER_LIMIT
                            )
                        );
                }, NodeScope, Dynamic)
            );

            /**
             * Cluster-wide circuit breaker limit that serves as the default configuration.
             * This setting is used when a node either:
             * - Has no knn_cb_tier attribute defined
             * - Has a tier that doesn't match any node-level configuration
             * Default value: {@value KNN_DEFAULT_MEMORY_CIRCUIT_BREAKER_LIMIT}
             */
            put(
                KNN_MEMORY_CIRCUIT_BREAKER_CLUSTER_LIMIT,
                new Setting<>(
                    KNNSettings.KNN_MEMORY_CIRCUIT_BREAKER_CLUSTER_LIMIT,
                    KNNSettings.KNN_DEFAULT_MEMORY_CIRCUIT_BREAKER_LIMIT,
                    (s) -> parseknnMemoryCircuitBreakerValue(s, KNNSettings.KNN_MEMORY_CIRCUIT_BREAKER_CLUSTER_LIMIT),
                    NodeScope,
                    Dynamic
                )
            );
            /**
             * Cache expiry time settings
             */
            put(KNN_CACHE_ITEM_EXPIRY_ENABLED, Setting.boolSetting(KNN_CACHE_ITEM_EXPIRY_ENABLED, false, NodeScope, Dynamic));
            put(
                KNN_CACHE_ITEM_EXPIRY_TIME_MINUTES,
                Setting.positiveTimeSetting(KNN_CACHE_ITEM_EXPIRY_TIME_MINUTES, TimeValue.timeValueHours(3), NodeScope, Dynamic)
            );
        }
    };

    private final static Map<String, Setting<?>> FEATURE_FLAGS_WHICH_REBUILDS_CACHE = getFeatureFlagsWhichRebuildsCache().stream()
        .collect(toUnmodifiableMap(Setting::getKey, Function.identity()));

    private final static Map<String, Setting<?>> FEATURE_FLAGS = getFeatureFlags().stream()
        .collect(toUnmodifiableMap(Setting::getKey, Function.identity()));

    private ClusterService clusterService;
    private Client client;
    @Setter
    private Optional<String> nodeCbAttribute;

    private KNNSettings() {}

    public static synchronized KNNSettings state() {
        if (INSTANCE == null) {
            INSTANCE = new KNNSettings();
        }
        return INSTANCE;
    }

    private void setSettingsUpdateConsumers() {
        clusterService.getClusterSettings().addSettingsUpdateConsumer(updatedSettings -> {
            // When any of the dynamic settings are updated, rebuild the cache with the updated values. Use the current
            // cluster settings values as defaults.
            NativeMemoryCacheManagerDto.NativeMemoryCacheManagerDtoBuilder builder = NativeMemoryCacheManagerDto.builder();

            builder.isWeightLimited(
                updatedSettings.getAsBoolean(KNN_MEMORY_CIRCUIT_BREAKER_ENABLED, getSettingValue(KNN_MEMORY_CIRCUIT_BREAKER_ENABLED))
            );

            // Recompute cache weight
            builder.maxWeight(getUpdatedCircuitBreakerLimit(updatedSettings).getKb());

            builder.isExpirationLimited(
                updatedSettings.getAsBoolean(KNN_CACHE_ITEM_EXPIRY_ENABLED, getSettingValue(KNN_CACHE_ITEM_EXPIRY_ENABLED))
            );

            builder.expiryTimeInMin(
                updatedSettings.getAsTime(KNN_CACHE_ITEM_EXPIRY_TIME_MINUTES, getSettingValue(KNN_CACHE_ITEM_EXPIRY_TIME_MINUTES))
                    .getMinutes()
            );

            NativeMemoryCacheManager.getInstance().rebuildCache(builder.build());
        },
            Stream.concat(dynamicCacheSettings.values().stream(), FEATURE_FLAGS_WHICH_REBUILDS_CACHE.values().stream())
                .collect(Collectors.toUnmodifiableList())
        );
        clusterService.getClusterSettings().addSettingsUpdateConsumer(QUANTIZATION_STATE_CACHE_SIZE_LIMIT_SETTING, it -> {
            quantizationStateCacheManager.setMaxCacheSizeInKB(it.getKb());
            quantizationStateCacheManager.rebuildCache();
        });
        clusterService.getClusterSettings().addSettingsUpdateConsumer(QUANTIZATION_STATE_CACHE_EXPIRY_TIME_MINUTES_SETTING, it -> {
            quantizationStateCacheManager.rebuildCache();
        });
    }

    /**
     * Get setting value by key. Return default value if not configured explicitly.
     *
     * @param key   setting key.
     * @param <T> Setting type
     * @return T     setting value or default
     */
    @SuppressWarnings("unchecked")
    public <T> T getSettingValue(String key) {
        return (T) clusterService.getClusterSettings().get(getSetting(key));
    }

    private Setting<?> getSetting(String key) {
        if (dynamicCacheSettings.containsKey(key)) {
            return dynamicCacheSettings.get(key);
        }

        if (FEATURE_FLAGS.containsKey(key)) {
            return FEATURE_FLAGS.get(key);
        }

        if (KNN_CIRCUIT_BREAKER_TRIGGERED.equals(key)) {
            return KNN_CIRCUIT_BREAKER_TRIGGERED_SETTING;
        }

        if (KNN_CIRCUIT_BREAKER_UNSET_PERCENTAGE.equals(key)) {
            return KNN_CIRCUIT_BREAKER_UNSET_PERCENTAGE_SETTING;
        }

        if (KNN_ALGO_PARAM_INDEX_THREAD_QTY.equals(key)) {
            return KNN_ALGO_PARAM_INDEX_THREAD_QTY_SETTING;
        }

        if (ADVANCED_FILTERED_EXACT_SEARCH_THRESHOLD.equals(key)) {
            return ADVANCED_FILTERED_EXACT_SEARCH_THRESHOLD_SETTING;
        }

        if (KNN_FAISS_AVX2_DISABLED.equals(key)) {
            return KNN_FAISS_AVX2_DISABLED_SETTING;
        }

        if (KNN_FAISS_AVX512_DISABLED.equals(key)) {
            return KNN_FAISS_AVX512_DISABLED_SETTING;
        }

        if (KNN_FAISS_AVX512_SPR_DISABLED.equals(key)) {
            return KNN_FAISS_AVX512_SPR_DISABLED_SETTING;
        }

        if (KNN_VECTOR_STREAMING_MEMORY_LIMIT_IN_MB.equals(key)) {
            return KNN_VECTOR_STREAMING_MEMORY_LIMIT_PCT_SETTING;
        }

        if (QUANTIZATION_STATE_CACHE_SIZE_LIMIT.equals(key)) {
            return QUANTIZATION_STATE_CACHE_SIZE_LIMIT_SETTING;
        }

        if (QUANTIZATION_STATE_CACHE_EXPIRY_TIME_MINUTES.equals(key)) {
            return QUANTIZATION_STATE_CACHE_EXPIRY_TIME_MINUTES_SETTING;
        }

        if (KNN_DISK_VECTOR_SHARD_LEVEL_RESCORING_DISABLED.equals(key)) {
            return KNN_DISK_VECTOR_SHARD_LEVEL_RESCORING_DISABLED_SETTING;
        }
        if (KNN_DERIVED_SOURCE_ENABLED.equals(key)) {
            return KNN_DERIVED_SOURCE_ENABLED_SETTING;
        }

        if (KNN_REMOTE_VECTOR_BUILD.equals(key)) {
            return KNN_REMOTE_VECTOR_BUILD_SETTING;
        }

        if (KNN_INDEX_REMOTE_VECTOR_BUILD.equals(key)) {
            return KNN_INDEX_REMOTE_VECTOR_BUILD_SETTING;
        }

        if (KNN_REMOTE_REPOSITORY.equals(key)) {
            return KNN_REMOTE_VECTOR_REPOSITORY_SETTING;
        }

        if (KNN_INDEX_REMOTE_VECTOR_BUILD_SIZE_MIN.equals(key)) {
            return KNN_INDEX_REMOTE_VECTOR_BUILD_SIZE_MIN_SETTING;
        }

        if (KNN_REMOTE_VECTOR_BUILD_SIZE_MAX.equals(key)) {
            return KNN_REMOTE_VECTOR_BUILD_SIZE_MAX_SETTING;
        }

        if (KNN_REMOTE_BUILD_SERVICE_ENDPOINT.equals(key)) {
            return KNN_REMOTE_BUILD_SERVICE_ENDPOINT_SETTING;
        }

        if (KNN_REMOTE_BUILD_CLIENT_TIMEOUT.equals(key)) {
            return KNN_REMOTE_BUILD_CLIENT_TIMEOUT_SETTING;
        }

        if (KNN_REMOTE_BUILD_POLL_INTERVAL.equals(key)) {
            return KNN_REMOTE_BUILD_POLL_INTERVAL_SETTING;
        }

        if (KNN_REMOTE_BUILD_SERVICE_USERNAME.equals(key)) {
            return KNN_REMOTE_BUILD_SERVER_USERNAME_SETTING;
        }

        if (KNN_REMOTE_BUILD_SERVICE_PASSWORD.equals(key)) {
            return KNN_REMOTE_BUILD_SERVER_PASSWORD_SETTING;
        }

        if (INDEX_KNN_FAISS_EFFICIENT_FILTER_DISABLE_EXACT_SEARCH.equals(key)) {
            return INDEX_KNN_FAISS_EFFICIENT_FILTER_DISABLE_EXACT_SEARCH_SETTING;
        }

        throw new IllegalArgumentException("Cannot find setting by key [" + key + "]");
    }

    public List<Setting<?>> getSettings() {
        List<Setting<?>> settings = Arrays.asList(
            INDEX_KNN_ADVANCED_APPROXIMATE_THRESHOLD_SETTING,
            INDEX_KNN_ALGO_PARAM_EF_SEARCH_SETTING,
            KNN_ALGO_PARAM_INDEX_THREAD_QTY_SETTING,
            KNN_CIRCUIT_BREAKER_TRIGGERED_SETTING,
            KNN_CIRCUIT_BREAKER_UNSET_PERCENTAGE_SETTING,
            IS_KNN_INDEX_SETTING,
            MODEL_INDEX_NUMBER_OF_SHARDS_SETTING,
            MODEL_INDEX_NUMBER_OF_REPLICAS_SETTING,
            MODEL_CACHE_SIZE_LIMIT_SETTING,
            ADVANCED_FILTERED_EXACT_SEARCH_THRESHOLD_SETTING,
            KNN_FAISS_AVX2_DISABLED_SETTING,
            KNN_VECTOR_STREAMING_MEMORY_LIMIT_PCT_SETTING,
            KNN_FAISS_AVX512_DISABLED_SETTING,
            KNN_FAISS_AVX512_SPR_DISABLED_SETTING,
            QUANTIZATION_STATE_CACHE_SIZE_LIMIT_SETTING,
            QUANTIZATION_STATE_CACHE_EXPIRY_TIME_MINUTES_SETTING,
            KNN_DISK_VECTOR_SHARD_LEVEL_RESCORING_DISABLED_SETTING,
            KNN_DERIVED_SOURCE_ENABLED_SETTING,
            MEMORY_OPTIMIZED_KNN_SEARCH_MODE_SETTING,
            // Index level remote vector build settings
            KNN_INDEX_REMOTE_VECTOR_BUILD_SETTING,
            KNN_INDEX_REMOTE_VECTOR_BUILD_SIZE_MIN_SETTING,
            // Cluster level remote vector build settings
            KNN_REMOTE_VECTOR_BUILD_SETTING,
            KNN_REMOTE_VECTOR_REPOSITORY_SETTING,
            KNN_REMOTE_VECTOR_BUILD_SIZE_MAX_SETTING,
            KNN_REMOTE_BUILD_SERVICE_ENDPOINT_SETTING,
            KNN_REMOTE_BUILD_POLL_INTERVAL_SETTING,
            KNN_REMOTE_BUILD_CLIENT_TIMEOUT_SETTING,
            KNN_REMOTE_BUILD_SERVER_USERNAME_SETTING,
            KNN_REMOTE_BUILD_SERVER_PASSWORD_SETTING,
            INDEX_KNN_FAISS_EFFICIENT_FILTER_DISABLE_EXACT_SEARCH_SETTING
        );
        return Stream.concat(settings.stream(), Stream.concat(getFeatureFlags().stream(), dynamicCacheSettings.values().stream()))
            .collect(Collectors.toList());
    }

    public static boolean isCircuitBreakerTriggered() {
        return KNNSettings.state().getSettingValue(KNNSettings.KNN_CIRCUIT_BREAKER_TRIGGERED);
    }

    /**
     * Retrieves the node-specific circuit breaker limit based on the existing settings.
     *
     * @return String representation of the node-specific circuit breaker limit,
     *         or null if no node-specific limit is set or found
     */
    private String getNodeCbLimit() {
        if (nodeCbAttribute.isPresent()) {
            Settings configuredNodeCbLimits = KNNSettings.state().getSettingValue(KNNSettings.KNN_MEMORY_CIRCUIT_BREAKER_LIMIT_PREFIX);
            return configuredNodeCbLimits.get(nodeCbAttribute.get());
        }
        return null;
    }

    /**
     * Gets node-specific circuit breaker limit from updated settings.
     *
     * @param updatedSettings Settings object containing pending updates
     * @return String representation of new limit if exists for this node's tier, null otherwise
     */
    private String getNodeCbLimit(Settings updatedSettings) {
        if (nodeCbAttribute.isPresent()) {
            return updatedSettings.getByPrefix(KNN_MEMORY_CIRCUIT_BREAKER_LIMIT_PREFIX).get(nodeCbAttribute.get());
        }
        return null;
    }

    /**
     * Returns the cluster-level circuit breaker limit. Needed for initialization
     * during startup when node attributes are not yet available through ClusterService.
     * This limit serves two purposes:
     * 1. As a temporary value during node startup before node-specific limits can be determined
     * 2. As a fallback value for nodes that don't have a knn_cb_tier attribute or
     *    whose tier doesn't match any configured node-level limit
     *
     * @return ByteSizeValue representing the cluster-wide circuit breaker limit
     */
    public static ByteSizeValue getClusterCbLimit() {
        return KNNSettings.state().getSettingValue(KNNSettings.KNN_MEMORY_CIRCUIT_BREAKER_CLUSTER_LIMIT);
    }

    /**
     * Returns the circuit breaker limit for this node using existing configuration. The limit is determined by:
     * 1. Node-specific limit based on the node's circuit breaker tier attribute, if configured
     * 2. Cluster-level default limit if no node-specific configuration exists
     *
     * @return ByteSizeValue representing the circuit breaker limit, either as a percentage
     *         of available memory or as an absolute value
     */
    public ByteSizeValue getCircuitBreakerLimit() {

        return parseknnMemoryCircuitBreakerValue(getNodeCbLimit(), getClusterCbLimit(), KNN_MEMORY_CIRCUIT_BREAKER_CLUSTER_LIMIT);

    }

    /**
     * Determines if and how the circuit breaker limit should be updated for this node.
     * Evaluates both node-specific and cluster-level updates in the updated settings,
     * maintaining proper precedence:
     * 1. Node-tier specific limit from updates (if available)
     * 2. Appropriate fallback value based on node's current configuration
     *
     * @param updatedCbLimits Settings object containing pending circuit breaker updates
     * @return ByteSizeValue representing the new circuit breaker limit to apply,
     *         or null if no applicable updates found
     */
    private ByteSizeValue getUpdatedCircuitBreakerLimit(Settings updatedCbLimits) {
        // Parse any updates, using appropriate fallback if no node-specific limit update exists
        return parseknnMemoryCircuitBreakerValue(
            getNodeCbLimit(updatedCbLimits),
            getFallbackCbLimitValue(updatedCbLimits),
            KNN_MEMORY_CIRCUIT_BREAKER_CLUSTER_LIMIT
        );
    }

    /**
     * Determines the appropriate fallback circuit breaker limit value.
     * The fallback logic follows this hierarchy:
     * 1. If node currently uses cluster-level limit:
     *    - Use updated cluster-level limit if available
     *    - Otherwise maintain current limit
     * 2. If node uses tier-specific limit:
     *    - Maintain current limit (ignore cluster-level updates)
     *
     * This ensures nodes maintain their configuration hierarchy and don't
     * inadvertently fall back to cluster-level limits when they should use
     * tier-specific values.
     *
     * @param updatedCbLimits Settings object containing pending updates
     * @return ByteSizeValue representing the appropriate fallback limit
     */
    private ByteSizeValue getFallbackCbLimitValue(Settings updatedCbLimits) {
        // Update cluster level limit if used
        if (getNodeCbLimit() == null && updatedCbLimits.hasValue(KNN_MEMORY_CIRCUIT_BREAKER_CLUSTER_LIMIT)) {
            return (ByteSizeValue) getSetting(KNN_MEMORY_CIRCUIT_BREAKER_CLUSTER_LIMIT).get(updatedCbLimits);

        }

        // Otherwise maintain current limit (either tier-specific or cluster-level)
        return getCircuitBreakerLimit();
    }

    public static double getCircuitBreakerUnsetPercentage() {
        return KNNSettings.state().getSettingValue(KNNSettings.KNN_CIRCUIT_BREAKER_UNSET_PERCENTAGE);
    }

    public static boolean isKnnIndexFaissEfficientFilterExactSearchDisabled(String indexName) {
        return KNNSettings.state().clusterService.state()
            .getMetadata()
            .index(indexName)
            .getSettings()
            .getAsBoolean(INDEX_KNN_FAISS_EFFICIENT_FILTER_DISABLE_EXACT_SEARCH, false);
    }

    /**
     * @return true if remote vector index build cluster is enabled
     */
    public static boolean isKNNRemoteVectorBuildEnabled() {
        return Booleans.parseBooleanStrict(KNNSettings.state().getSettingValue(KNN_REMOTE_VECTOR_BUILD).toString(), false);
    }

    /**
     * Gets the remote build service endpoint.
     * @return String representation of the remote build service endpoint URL
     */
    public static String getRemoteBuildServiceEndpoint() {
        return KNNSettings.state().getSettingValue(KNNSettings.KNN_REMOTE_BUILD_SERVICE_ENDPOINT);
    }

    /**
     * Gets the amount of time the client will wait before abandoning a remote build.
     */
    public static TimeValue getRemoteBuildClientTimeout() {
        return KNNSettings.state().getSettingValue(KNNSettings.KNN_REMOTE_BUILD_CLIENT_TIMEOUT);
    }

    /**
     * Gets the interval at which a RemoteIndexPoller will poll for remote build status.
     */
    public static TimeValue getRemoteBuildClientPollInterval() {
        return KNNSettings.state().getSettingValue(KNNSettings.KNN_REMOTE_BUILD_POLL_INTERVAL);
    }

    public static boolean isFaissAVX2Disabled() {
        try {
            return KNNSettings.state().getSettingValue(KNNSettings.KNN_FAISS_AVX2_DISABLED);
        } catch (Exception e) {
            // In some UTs we identified that cluster setting is not set properly an leads to NPE. This check will avoid
            // those cases and will still return the default value.
            log.warn(
                "Unable to get setting value {} from cluster settings. Using default value as {}",
                KNN_FAISS_AVX2_DISABLED,
                KNN_DEFAULT_FAISS_AVX2_DISABLED_VALUE,
                e
            );
            return KNN_DEFAULT_FAISS_AVX2_DISABLED_VALUE;
        }
    }

    /**
     * check this index enabled/disabled derived source
     * @param settings Settings
     */
    public static boolean isKNNDerivedSourceEnabled(Settings settings) {
        return KNN_DERIVED_SOURCE_ENABLED_SETTING.get(settings);
    }

    public static boolean isFaissAVX512Disabled() {
        return parseBoolean(
            Objects.requireNonNullElse(
                KNNSettings.state().getSettingValue(KNNSettings.KNN_FAISS_AVX512_DISABLED),
                KNN_DEFAULT_FAISS_AVX512_DISABLED_VALUE
            ).toString()
        );
    }

    public static boolean isFaissAVX512SPRDisabled() {
        return parseBoolean(
            Objects.requireNonNullElse(
                KNNSettings.state().getSettingValue(KNNSettings.KNN_FAISS_AVX512_SPR_DISABLED),
                KNN_DEFAULT_FAISS_AVX512_SPR_DISABLED_VALUE
            ).toString()
        );
    }

    public static Integer getFilteredExactSearchThreshold(final String indexName) {
        return getIndexSettings(indexName).getAsInt(
            ADVANCED_FILTERED_EXACT_SEARCH_THRESHOLD,
            ADVANCED_FILTERED_EXACT_SEARCH_THRESHOLD_DEFAULT_VALUE
        );
    }

    public static boolean isShardLevelRescoringDisabledForDiskBasedVector(final String indexName) {
        return getIndexSettings(indexName).getAsBoolean(KNN_DISK_VECTOR_SHARD_LEVEL_RESCORING_DISABLED, false);
    }

    public void initialize(Client client, ClusterService clusterService) {
        this.client = client;
        this.clusterService = clusterService;
        this.nodeCbAttribute = Optional.empty();
        setSettingsUpdateConsumers();
    }

    public static ByteSizeValue parseknnMemoryCircuitBreakerValue(String sValue, String settingName) {
        return parseknnMemoryCircuitBreakerValue(sValue, null, settingName);
    }

    public static ByteSizeValue parseknnMemoryCircuitBreakerValue(String sValue, ByteSizeValue defaultValue, String settingName) {
        settingName = Objects.requireNonNull(settingName);
        if (sValue != null && sValue.endsWith("%")) {
            final String percentAsString = sValue.substring(0, sValue.length() - 1);
            try {
                final double percent = Double.parseDouble(percentAsString);
                if (percent < 0 || percent > 100) {
                    throw new OpenSearchParseException("percentage should be in [0-100], got [{}]", percentAsString);
                }
                long physicalMemoryInBytes = osProbe.getTotalPhysicalMemorySize();
                if (physicalMemoryInBytes <= 0) {
                    throw new IllegalStateException("Physical memory size could not be determined");
                }
                long esJvmSizeInBytes = JvmInfo.jvmInfo().getMem().getHeapMax().getBytes();
                long eligibleMemoryInBytes = physicalMemoryInBytes - esJvmSizeInBytes;
                return new ByteSizeValue((long) ((percent / 100) * eligibleMemoryInBytes), ByteSizeUnit.BYTES);
            } catch (NumberFormatException e) {
                throw new OpenSearchParseException("failed to parse [{}] as a double", e, percentAsString);
            }
        } else {
            return parseBytesSizeValue(sValue, defaultValue, settingName);
        }
    }

    /**
     * Updates knn.circuit_breaker.triggered setting to true/false
     * @param flag true/false
     */
    public synchronized void updateCircuitBreakerSettings(boolean flag) {
        ClusterUpdateSettingsRequest clusterUpdateSettingsRequest = new ClusterUpdateSettingsRequest();
        Settings circuitBreakerSettings = Settings.builder().put(KNNSettings.KNN_CIRCUIT_BREAKER_TRIGGERED, flag).build();
        clusterUpdateSettingsRequest.persistentSettings(circuitBreakerSettings);
        client.admin().cluster().updateSettings(clusterUpdateSettingsRequest, new ActionListener<ClusterUpdateSettingsResponse>() {
            @Override
            public void onResponse(ClusterUpdateSettingsResponse clusterUpdateSettingsResponse) {
                logger.debug(
                    "Cluster setting {}, acknowledged: {} ",
                    clusterUpdateSettingsRequest.persistentSettings(),
                    clusterUpdateSettingsResponse.isAcknowledged()
                );
            }

            @Override
            public void onFailure(Exception e) {
                logger.info(
                    "Exception while updating circuit breaker setting {} to {}",
                    clusterUpdateSettingsRequest.persistentSettings(),
                    e.getMessage()
                );
            }
        });
    }

    public static ByteSizeValue getVectorStreamingMemoryLimit() {
        return KNNSettings.state().getSettingValue(KNN_VECTOR_STREAMING_MEMORY_LIMIT_IN_MB);
    }

    /**
     *
     * @param index Name of the index
     * @return efSearch value
     */
    public static int getEfSearchParam(String index) {
        final IndexMetadata indexMetadata = KNNSettings.state().clusterService.state().getMetadata().index(index);
        return indexMetadata.getSettings()
            .getAsInt(
                KNNSettings.KNN_ALGO_PARAM_EF_SEARCH,
                IndexHyperParametersUtil.getHNSWEFSearchValue(indexMetadata.getCreationVersion())
            );
    }

    /**
     * Return whether memory optimized search enabled for the given index.
     *
     * @param indexName The name of target index to test whether if it is on.
     * @return True if memory optimized search is enabled, otherwise False.
     */
    public static boolean isMemoryOptimizedKnnSearchModeEnabled(@NonNull final String indexName) {
        return getIndexSettings(indexName).getAsBoolean(MEMORY_OPTIMIZED_KNN_SEARCH_MODE, DEFAULT_MEMORY_OPTIMIZED_KNN_SEARCH_MODE);
    }

    public static Settings getIndexSettings(@NonNull final String indexName) {
        return KNNSettings.state().clusterService.state().getMetadata().index(indexName).getSettings();
    }

    public void setClusterService(ClusterService clusterService) {
        this.clusterService = clusterService;
    }

    static class SpaceTypeValidator implements Setting.Validator<String> {
        @Override
        public void validate(String value) {
            try {
                SpaceType.getSpace(value);
            } catch (IllegalArgumentException ex) {
                throw new InvalidParameterException(ex.getMessage());
            }
        }
    }

    public void onIndexModule(IndexModule module) {
        module.addSettingsUpdateConsumer(INDEX_KNN_ALGO_PARAM_EF_SEARCH_SETTING, newVal -> {
            logger.debug("The value of [KNN] setting [{}] changed to [{}]", KNN_ALGO_PARAM_EF_SEARCH, newVal);
            // TODO: replace cache-rebuild with index reload into the cache
            NativeMemoryCacheManager.getInstance().rebuildCache();
        });
    }

    /**
     * Finds the suggested number of indexing threads based on the number of available processors
     *
     * @return suggested number of indexing threads
     */
    static int getHardwareDefaultIndexThreadQty(final Settings settings) {
        try {
            int availableProcessors = OpenSearchExecutors.allocatedProcessors(settings);
            if (availableProcessors >= INDEX_THREAD_QUANTITY_THRESHOLD) {
                return INDEX_THREAD_QUANTITY_DEFAULT_LARGE;
            } else {
                return INDEX_THREAD_QUANTITY_DEFAULT_SMALL;
            }
        } catch (Exception e) {
            logger.info("[KNN] Failed to determine available processors. Defaulting to 1. [{}]", e.getMessage(), e);
            return 1;
        }
    }

    /**
     * Get the index thread quantity setting value from cluster setting.
     * @return int
     */
    public static int getIndexThreadQty() {
        return KNNSettings.state().getSettingValue(KNN_ALGO_PARAM_INDEX_THREAD_QTY);
    }
}
