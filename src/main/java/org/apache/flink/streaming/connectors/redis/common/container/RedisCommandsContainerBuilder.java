package org.apache.flink.streaming.connectors.redis.common.container;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;

import org.apache.flink.streaming.connectors.redis.common.config.FlinkClusterConfig;
import org.apache.flink.streaming.connectors.redis.common.config.FlinkConfigBase;
import org.apache.flink.streaming.connectors.redis.common.config.FlinkSentinelConfig;
import org.apache.flink.streaming.connectors.redis.common.config.FlinkSingleConfig;
import org.apache.flink.util.StringUtils;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** The builder for {@link RedisCommandsContainer}. */
public class RedisCommandsContainerBuilder {

    /**
     * Initialize the {@link RedisCommandsContainer} based on the instance type.
     *
     * @param flinkConfigBase configuration base
     * @return @throws IllegalArgumentException if Config, ClusterConfig and SentinelConfig are all
     *     null
     */
    public static RedisCommandsContainer build(FlinkConfigBase flinkConfigBase) {
        DefaultClientResources.Builder builder = DefaultClientResources.builder();
        if (flinkConfigBase.getLettuceConfig() != null) {
            if (flinkConfigBase.getLettuceConfig().getNettyIoPoolSize() != null) {
                builder.ioThreadPoolSize(flinkConfigBase.getLettuceConfig().getNettyIoPoolSize());
            }
            if (flinkConfigBase.getLettuceConfig().getNettyEventPoolSize() != null) {
                builder.computationThreadPoolSize(
                        flinkConfigBase.getLettuceConfig().getNettyEventPoolSize());
            }
        }

        ClientResources clientResources = builder.build();

        if (flinkConfigBase instanceof FlinkSingleConfig) {
            return build((FlinkSingleConfig) flinkConfigBase, clientResources);
        } else if (flinkConfigBase instanceof FlinkClusterConfig) {
            return RedisCommandsContainerBuilder.build(
                    (FlinkClusterConfig) flinkConfigBase, clientResources);
        } else if (flinkConfigBase instanceof FlinkSentinelConfig) {
            return RedisCommandsContainerBuilder.build(
                    (FlinkSentinelConfig) flinkConfigBase, clientResources);
        } else {
            throw new IllegalArgumentException(" configuration not found");
        }
    }

    /**
     * Builds container for single Redis environment.
     *
     * @param singleConfig configuration for redis
     * @return container for single Redis environment
     * @throws NullPointerException if singleConfig is null
     */
    public static RedisCommandsContainer build(
            FlinkSingleConfig singleConfig, ClientResources clientResources) {
        Objects.requireNonNull(singleConfig, "Redis config should not be Null");

        RedisURI.Builder builder =
                RedisURI.builder()
                        .withHost(singleConfig.getHost())
                        .withPort(singleConfig.getPort())
                        .withDatabase(singleConfig.getDatabase());
        if (!StringUtils.isNullOrWhitespaceOnly(singleConfig.getPassword())) {
            builder.withPassword(singleConfig.getPassword().toCharArray());
        }

        return new RedisContainer(RedisClient.create(clientResources, builder.build()));
    }

    /**
     * Builds container for Redis Cluster environment.
     *
     * @param clusterConfig configuration for Cluster
     * @return container for Redis Cluster environment
     * @throws NullPointerException if ClusterConfig is null
     */
    public static RedisCommandsContainer build(
            FlinkClusterConfig clusterConfig, ClientResources clientResources) {
        Objects.requireNonNull(clusterConfig, "Redis cluster config should not be Null");

        List<RedisURI> redisURIS =
                Arrays.stream(clusterConfig.getNodesInfo().split(","))
                        .map(
                                node -> {
                                    String[] redis = node.split(":");
                                    RedisURI.Builder builder =
                                            RedisURI.builder()
                                                    .withHost(redis[0])
                                                    .withPort(Integer.parseInt(redis[1]));
                                    if (!StringUtils.isNullOrWhitespaceOnly(
                                            clusterConfig.getPassword())) {
                                        builder.withPassword(
                                                clusterConfig.getPassword().toCharArray());
                                    }
                                    return builder.build();
                                })
                        .collect(Collectors.toList());

        RedisClusterClient clusterClient = RedisClusterClient.create(clientResources, redisURIS);

        ClusterTopologyRefreshOptions topologyRefreshOptions =
                ClusterTopologyRefreshOptions.builder()
                        .enableAdaptiveRefreshTrigger(
                                ClusterTopologyRefreshOptions.RefreshTrigger.MOVED_REDIRECT,
                                ClusterTopologyRefreshOptions.RefreshTrigger.PERSISTENT_RECONNECTS)
                        .adaptiveRefreshTriggersTimeout(Duration.ofSeconds(10L))
                        .build();

        clusterClient.setOptions(
                ClusterClientOptions.builder()
                        .topologyRefreshOptions(topologyRefreshOptions)
                        .build());

        return new RedisClusterContainer(clusterClient);
    }

    /**
     * Builds container for Redis Sentinel environment.
     *
     * @param sentinelConfig configuration for Sentinel
     * @return container for Redis sentinel environment
     * @throws NullPointerException if SentinelConfig is null
     */
    public static RedisCommandsContainer build(
            FlinkSentinelConfig sentinelConfig, ClientResources clientResources) {
        Objects.requireNonNull(sentinelConfig, "Redis sentinel config should not be Null");

        RedisURI.Builder builder =
                RedisURI.builder()
                        .withSentinelMasterId(sentinelConfig.getMasterName())
                        .withDatabase(sentinelConfig.getDatabase());

        Arrays.stream(sentinelConfig.getSentinelsInfo().split(","))
                .forEach(
                        node -> {
                            String[] redis = node.split(":");
                            if (StringUtils.isNullOrWhitespaceOnly(sentinelConfig.getPassword())) {
                                builder.withSentinel(
                                        redis[0],
                                        Integer.parseInt(redis[1]),
                                        sentinelConfig.getSentinelsPassword());
                            } else {
                                builder.withSentinel(
                                                redis[0],
                                                Integer.parseInt(redis[1]),
                                                sentinelConfig.getSentinelsPassword())
                                        .withPassword(sentinelConfig.getPassword());
                            }
                        });

        return new RedisContainer(RedisClient.create(clientResources, builder.build()));
    }
}
