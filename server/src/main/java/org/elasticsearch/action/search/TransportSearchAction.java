/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.action.search;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.OriginalIndices;
import org.elasticsearch.action.admin.cluster.shards.ClusterSearchShardsGroup;
import org.elasticsearch.action.admin.cluster.shards.ClusterSearchShardsResponse;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.HandledTransportAction;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.GroupShardsIterator;
import org.elasticsearch.cluster.routing.ShardIterator;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.query.Rewriteable;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.search.SearchService;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.internal.AliasFilter;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.RemoteClusterAware;
import org.elasticsearch.transport.RemoteClusterService;
import org.elasticsearch.transport.Transport;
import org.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;

import static org.elasticsearch.action.search.SearchType.QUERY_THEN_FETCH;

/**
 * 会进行search 分发
 */
public class TransportSearchAction extends HandledTransportAction<SearchRequest, SearchResponse> {

    /** The maximum number of shards for a single search request. */
    public static final Setting<Long> SHARD_COUNT_LIMIT_SETTING = Setting.longSetting(
            "action.search.shard_count.limit", Long.MAX_VALUE, 1L, Property.Dynamic, Property.NodeScope);

    private final ClusterService clusterService;
    private final SearchTransportService searchTransportService;
    private final RemoteClusterService remoteClusterService;
    private final SearchPhaseController searchPhaseController;
    private final SearchService searchService;

    @Inject
    public TransportSearchAction(Settings settings, ThreadPool threadPool, TransportService transportService, SearchService searchService,
                                 SearchTransportService searchTransportService, SearchPhaseController searchPhaseController,
                                 ClusterService clusterService, ActionFilters actionFilters,
                                 IndexNameExpressionResolver indexNameExpressionResolver) {
        super(settings, SearchAction.NAME, threadPool, transportService, actionFilters, indexNameExpressionResolver, SearchRequest::new);
        this.searchPhaseController = searchPhaseController;
        this.searchTransportService = searchTransportService;
        this.remoteClusterService = searchTransportService.getRemoteClusterService();
        SearchTransportService.registerRequestHandler(transportService, searchService);
        this.clusterService = clusterService;
        this.searchService = searchService;
    }

    private Map<String, AliasFilter> buildPerIndexAliasFilter(SearchRequest request, ClusterState clusterState,
                                                              Index[] concreteIndices, Map<String, AliasFilter> remoteAliasMap) {
        final Map<String, AliasFilter> aliasFilterMap = new HashMap<>();
        for (Index index : concreteIndices) {
            clusterState.blocks().indexBlockedRaiseException(ClusterBlockLevel.READ, index.getName());
            AliasFilter aliasFilter = searchService.buildAliasFilter(clusterState, index.getName(), request.indices());
            assert aliasFilter != null;
            aliasFilterMap.put(index.getUUID(), aliasFilter);
        }
        aliasFilterMap.putAll(remoteAliasMap);
        return aliasFilterMap;
    }

    private Map<String, Float> resolveIndexBoosts(SearchRequest searchRequest, ClusterState clusterState) {
        if (searchRequest.source() == null) {
            return Collections.emptyMap();
        }

        SearchSourceBuilder source = searchRequest.source();
        if (source.indexBoosts() == null) {
            return Collections.emptyMap();
        }

        Map<String, Float> concreteIndexBoosts = new HashMap<>();
        for (SearchSourceBuilder.IndexBoost ib : source.indexBoosts()) {
            Index[] concreteIndices =
                indexNameExpressionResolver.concreteIndices(clusterState, searchRequest.indicesOptions(), ib.getIndex());

            for (Index concreteIndex : concreteIndices) {
                concreteIndexBoosts.putIfAbsent(concreteIndex.getUUID(), ib.getBoost());
            }
        }
        return Collections.unmodifiableMap(concreteIndexBoosts);
    }

    /**
     * Search operations need two clocks. One clock is to fulfill real clock needs (e.g., resolving
     * "now" to an index name). Another clock is needed for measuring how long a search operation
     * took. These two uses are at odds with each other. There are many issues with using a real
     * clock for measuring how long an operation took (they often lack precision, they are subject
     * to moving backwards due to NTP and other such complexities, etc.). There are also issues with
     * using a relative clock for reporting real time. Thus, we simply separate these two uses.
     */
    static class SearchTimeProvider {

        private final long absoluteStartMillis;
        private final long relativeStartNanos;
        private final LongSupplier relativeCurrentNanosProvider;

        /**
         * Instantiates a new search time provider. The absolute start time is the real clock time
         * used for resolving index expressions that include dates. The relative start time is the
         * start of the search operation according to a relative clock. The total time the search
         * operation took can be measured against the provided relative clock and the relative start
         * time.
         *
         * @param absoluteStartMillis the absolute start time in milliseconds since the epoch
         * @param relativeStartNanos the relative start time in nanoseconds
         * @param relativeCurrentNanosProvider provides the current relative time
         */
        SearchTimeProvider(
                final long absoluteStartMillis,
                final long relativeStartNanos,
                final LongSupplier relativeCurrentNanosProvider) {
            this.absoluteStartMillis = absoluteStartMillis;
            this.relativeStartNanos = relativeStartNanos;
            this.relativeCurrentNanosProvider = relativeCurrentNanosProvider;
        }

        long getAbsoluteStartMillis() {
            return absoluteStartMillis;
        }

        long getRelativeStartNanos() {
            return relativeStartNanos;
        }

        long getRelativeCurrentNanos() {
            return relativeCurrentNanosProvider.getAsLong();
        }
    }

    @Override
    protected void doExecute(Task task, SearchRequest searchRequest, ActionListener<SearchResponse> listener) {
        final long absoluteStartMillis = System.currentTimeMillis();
        final long relativeStartNanos = System.nanoTime();
        final SearchTimeProvider timeProvider =
                new SearchTimeProvider(absoluteStartMillis, relativeStartNanos, System::nanoTime);

        ActionListener<SearchSourceBuilder> rewriteListener = ActionListener.wrap(source -> {
            if (source != searchRequest.source()) {
                // only set it if it changed - we don't allow null values to be set but it might be already null be we want to catch
                // situations when it possible due to a bug changes to null
                searchRequest.source(source);
            }
            // 获得集群状态
            final ClusterState clusterState = clusterService.state();
            final Map<String, OriginalIndices> remoteClusterIndices = remoteClusterService.groupIndices(searchRequest.indicesOptions(),
                searchRequest.indices(), idx -> indexNameExpressionResolver.hasIndexOrAlias(idx, clusterState));
            OriginalIndices localIndices = remoteClusterIndices.remove(RemoteClusterAware.LOCAL_CLUSTER_GROUP_KEY);
            if (remoteClusterIndices.isEmpty()) {
                executeSearch((SearchTask)task, timeProvider, searchRequest, localIndices, remoteClusterIndices, Collections.emptyList(),
                    (clusterName, nodeId) -> null, clusterState, Collections.emptyMap(), listener, clusterState.getNodes()
                        .getDataNodes().size(), SearchResponse.Clusters.EMPTY);
            } else {
                remoteClusterService.collectSearchShards(searchRequest.indicesOptions(), searchRequest.preference(),
                    searchRequest.routing(), remoteClusterIndices, ActionListener.wrap((searchShardsResponses) -> {
                        List<SearchShardIterator> remoteShardIterators = new ArrayList<>();
                        Map<String, AliasFilter> remoteAliasFilters = new HashMap<>();
                        BiFunction<String, String, DiscoveryNode> clusterNodeLookup = processRemoteShards(searchShardsResponses,
                            remoteClusterIndices, remoteShardIterators, remoteAliasFilters);
                        int numNodesInvolved = searchShardsResponses.values().stream().mapToInt(r -> r.getNodes().length).sum()
                            + clusterState.getNodes().getDataNodes().size();
                        SearchResponse.Clusters clusters = buildClusters(localIndices, remoteClusterIndices, searchShardsResponses);
                        executeSearch((SearchTask) task, timeProvider, searchRequest, localIndices, remoteClusterIndices,
                            remoteShardIterators, clusterNodeLookup, clusterState, remoteAliasFilters, listener, numNodesInvolved,
                            clusters);
                    }, listener::onFailure));
            }
        }, listener::onFailure);
        if (searchRequest.source() == null) {
            rewriteListener.onResponse(searchRequest.source());
        } else {
            // 查询语句重写，主要是为了加快语句在lucene中的搜索速度，例如会移除重复的语句等
            Rewriteable.rewriteAndFetch(searchRequest.source(), searchService.getRewriteContext(timeProvider::getAbsoluteStartMillis),
                rewriteListener);
        }
    }

    static SearchResponse.Clusters buildClusters(OriginalIndices localIndices, Map<String, OriginalIndices> remoteIndices,
                                                 Map<String, ClusterSearchShardsResponse> searchShardsResponses) {
        int localClusters = Math.min(localIndices.indices().length, 1);
        int totalClusters = remoteIndices.size() + localClusters;
        int successfulClusters = localClusters;
        for (ClusterSearchShardsResponse searchShardsResponse : searchShardsResponses.values()) {
            if (searchShardsResponse != ClusterSearchShardsResponse.EMPTY) {
                successfulClusters++;
            }
        }
        int skippedClusters = totalClusters - successfulClusters;
        return new SearchResponse.Clusters(totalClusters, successfulClusters, skippedClusters);
    }

    static BiFunction<String, String, DiscoveryNode> processRemoteShards(Map<String, ClusterSearchShardsResponse> searchShardsResponses,
                                                                      Map<String, OriginalIndices> remoteIndicesByCluster,
                                                                      List<SearchShardIterator> remoteShardIterators,
                                                                      Map<String, AliasFilter> aliasFilterMap) {
        Map<String, Map<String, DiscoveryNode>> clusterToNode = new HashMap<>();
        for (Map.Entry<String, ClusterSearchShardsResponse> entry : searchShardsResponses.entrySet()) {
            String clusterAlias = entry.getKey();
            ClusterSearchShardsResponse searchShardsResponse = entry.getValue();
            HashMap<String, DiscoveryNode> idToDiscoveryNode = new HashMap<>();
            clusterToNode.put(clusterAlias, idToDiscoveryNode);
            for (DiscoveryNode remoteNode : searchShardsResponse.getNodes()) {
                idToDiscoveryNode.put(remoteNode.getId(), remoteNode);
            }
            final Map<String, AliasFilter> indicesAndFilters = searchShardsResponse.getIndicesAndFilters();
            for (ClusterSearchShardsGroup clusterSearchShardsGroup : searchShardsResponse.getGroups()) {
                //add the cluster name to the remote index names for indices disambiguation
                //this ends up in the hits returned with the search response
                ShardId shardId = clusterSearchShardsGroup.getShardId();
                final AliasFilter aliasFilter;
                if (indicesAndFilters == null) {
                    aliasFilter = AliasFilter.EMPTY;
                } else {
                    aliasFilter = indicesAndFilters.get(shardId.getIndexName());
                    assert aliasFilter != null : "alias filter must not be null for index: " + shardId.getIndex();
                }
                String[] aliases = aliasFilter.getAliases();
                String[] finalIndices = aliases.length == 0 ? new String[] {shardId.getIndexName()} : aliases;
                // here we have to map the filters to the UUID since from now on we use the uuid for the lookup
                aliasFilterMap.put(shardId.getIndex().getUUID(), aliasFilter);
                final OriginalIndices originalIndices = remoteIndicesByCluster.get(clusterAlias);
                assert originalIndices != null : "original indices are null for clusterAlias: " + clusterAlias;
                SearchShardIterator shardIterator = new SearchShardIterator(clusterAlias, shardId,
                    Arrays.asList(clusterSearchShardsGroup.getShards()), new OriginalIndices(finalIndices,
                    originalIndices.indicesOptions()));
                remoteShardIterators.add(shardIterator);
            }
        }
        return (clusterAlias, nodeId) -> {
                Map<String, DiscoveryNode> clusterNodes = clusterToNode.get(clusterAlias);
                if (clusterNodes == null) {
                    throw new IllegalArgumentException("unknown remote cluster: " + clusterAlias);
                }
            return clusterNodes.get(nodeId);
        };
    }

    private void executeSearch(SearchTask task, SearchTimeProvider timeProvider, SearchRequest searchRequest, OriginalIndices localIndices,
                               Map<String, OriginalIndices> remoteClusterIndices, List<SearchShardIterator> remoteShardIterators,
                               BiFunction<String, String, DiscoveryNode> remoteConnections, ClusterState clusterState,
                               Map<String, AliasFilter> remoteAliasMap, ActionListener<SearchResponse> listener, int nodeCount,
                               SearchResponse.Clusters clusters) {

        clusterState.blocks().globalBlockedRaiseException(ClusterBlockLevel.READ);
        // TODO: I think startTime() should become part of ActionRequest and that should be used both for index name
        // date math expressions and $now in scripts. This way all apis will deal with now in the same way instead
        // of just for the _search api
        final Index[] indices;
        if (localIndices.indices().length == 0 && remoteClusterIndices.isEmpty() == false) {
            indices = Index.EMPTY_ARRAY; // don't search on _all if only remote indices were specified
        } else {
            // 索引名字解析，解析为最终的索引名称
            indices = indexNameExpressionResolver.concreteIndices(clusterState, searchRequest.indicesOptions(),
                timeProvider.getAbsoluteStartMillis(), localIndices.indices());
        }
        Map<String, AliasFilter> aliasFilter = buildPerIndexAliasFilter(searchRequest, clusterState, indices, remoteAliasMap);
        // 解析 routing 表达式，如果没有设置routing，则为null
        Map<String, Set<String>> routingMap = indexNameExpressionResolver.resolveSearchRouting(clusterState, searchRequest.routing(),
            searchRequest.indices());
        String[] concreteIndices = new String[indices.length];
        for (int i = 0; i < indices.length; i++) {
            concreteIndices[i] = indices[i].getName();
        }
        // 获取集群中每个节点运行的查询任务数
        Map<String, Long> nodeSearchCounts = searchTransportService.getPendingSearchRequests();
        // 获取该索引的所有分片的迭代器集合，例如该某索引有5个主分片，那么就有5个迭代器，每个迭代器指定了该分片的 真正分片信息（包括主和副）
        GroupShardsIterator<ShardIterator> localShardsIterator = clusterService.operationRouting()
            .searchShards(clusterState, concreteIndices, routingMap, searchRequest.preference(),
                searchService.getResponseCollectorService(), nodeSearchCounts);
        // 迭代器合并，如果不是跨集群访问，可以忽略
        GroupShardsIterator<SearchShardIterator> shardIterators = mergeShardsIterators(localShardsIterator,
            localIndices, remoteShardIterators);

        // 如果此次请求shard数量超过上限，那么会拒绝此次请求
        failIfOverShardCountLimit(clusterService, shardIterators.size());

        Map<String, Float> concreteIndexBoosts = resolveIndexBoosts(searchRequest, clusterState);

        // 如果此次搜索只会涉及一个shard，那么会在那个节点上进行 query 和 fetch两个操作，不会再分开操作
        // optimize search type for cases where there is only one shard group to search on
        if (shardIterators.size() == 1) {
            // if we only have one group, then we always want Q_T_F, no need for DFS, and no need to do THEN since we hit one shard
            searchRequest.searchType(QUERY_THEN_FETCH);
        }
        // 如果开启了搜索建议
        if (searchRequest.isSuggestOnly()) {
            // disable request cache if we have only suggest
            searchRequest.requestCache(false);
            switch (searchRequest.searchType()) {
                case DFS_QUERY_THEN_FETCH:
                    // convert to Q_T_F if we have only suggest
                    searchRequest.searchType(QUERY_THEN_FETCH);
                    break;
            }
        }

        // 建立连接
        final DiscoveryNodes nodes = clusterState.nodes();
        BiFunction<String, String, Transport.Connection> connectionLookup = (clusterName, nodeId) -> {
            final DiscoveryNode discoveryNode = clusterName == null ? nodes.get(nodeId) : remoteConnections.apply(clusterName, nodeId);
            if (discoveryNode == null) {
                throw new IllegalStateException("no node found for id: " + nodeId);
            }
            return searchTransportService.getConnection(clusterName, discoveryNode);
        };
        // 一个开关，如果对某个shard的请求量太大了，那么会拒绝请求
        if (searchRequest.isMaxConcurrentShardRequestsSet() == false) {
            // we try to set a default of max concurrent shard requests based on
            // the node count but upper-bound it by 256 by default to keep it sane. A single
            // search request that fans out lots of shards should hit a cluster too hard while 256 is already a lot.
            // we multiply it by the default number of shards such that a single request in a cluster of 1 would hit all shards of a
            // default index.
            searchRequest.setMaxConcurrentShardRequests(Math.min(256, nodeCount
                * IndexMetaData.INDEX_NUMBER_OF_SHARDS_SETTING.getDefault(Settings.EMPTY)));
        }
        // 是否可以进行预过滤，从而减少对shard的请求
        boolean preFilterSearchShards = shouldPreFilterSearchShards(searchRequest, shardIterators);
        searchAsyncAction(task, searchRequest, shardIterators, timeProvider, connectionLookup, clusterState.version(),
            Collections.unmodifiableMap(aliasFilter), concreteIndexBoosts, listener, preFilterSearchShards, clusters).start();
    }

    private boolean shouldPreFilterSearchShards(SearchRequest searchRequest, GroupShardsIterator<SearchShardIterator> shardIterators) {
        SearchSourceBuilder source = searchRequest.source();
        return searchRequest.searchType() == QUERY_THEN_FETCH && // we can't do this for DFS it needs to fan out to all shards all the time
                SearchService.canRewriteToMatchNone(source) &&
                searchRequest.getPreFilterShardSize() < shardIterators.size();
    }

    static GroupShardsIterator<SearchShardIterator> mergeShardsIterators(GroupShardsIterator<ShardIterator> localShardsIterator,
                                                             OriginalIndices localIndices,
                                                             List<SearchShardIterator> remoteShardIterators) {
        List<SearchShardIterator> shards = new ArrayList<>();
        shards.addAll(remoteShardIterators);
        for (ShardIterator shardIterator : localShardsIterator) {
            shards.add(new SearchShardIterator(null, shardIterator.shardId(), shardIterator.getShardRoutings(), localIndices));
        }
        return new GroupShardsIterator<>(shards);
    }

    @Override
    protected final void doExecute(SearchRequest searchRequest, ActionListener<SearchResponse> listener) {
        throw new UnsupportedOperationException("the task parameter is required");
    }

    /**
     * 启动异步执行该任务
     */
    private AbstractSearchAsyncAction searchAsyncAction(SearchTask task, SearchRequest searchRequest,
                                                        GroupShardsIterator<SearchShardIterator> shardIterators,
                                                        SearchTimeProvider timeProvider,
                                                        BiFunction<String, String, Transport.Connection> connectionLookup,
                                                        long clusterStateVersion, Map<String, AliasFilter> aliasFilter,
                                                        Map<String, Float> concreteIndexBoosts,
                                                        ActionListener<SearchResponse> listener, boolean preFilter,
                                                        SearchResponse.Clusters clusters) {
        // 获取查询线程池，用于并发执行各个shard的搜索任务
        Executor executor = threadPool.executor(ThreadPool.Names.SEARCH);
        if (preFilter) {
            return new CanMatchPreFilterSearchPhase(logger, searchTransportService, connectionLookup,
                aliasFilter, concreteIndexBoosts, executor, searchRequest, listener, shardIterators,
                timeProvider, clusterStateVersion, task, (iter) -> {
                AbstractSearchAsyncAction action = searchAsyncAction(task, searchRequest, iter, timeProvider, connectionLookup,
                    clusterStateVersion, aliasFilter, concreteIndexBoosts, listener, false, clusters);
                return new SearchPhase(action.getName()) {
                    @Override
                    public void run() throws IOException {
                        action.start();
                    }
                };
            }, clusters);
        } else {
            AbstractSearchAsyncAction searchAsyncAction;
            switch (searchRequest.searchType()) {
                case DFS_QUERY_THEN_FETCH:
                    searchAsyncAction = new SearchDfsQueryThenFetchAsyncAction(logger, searchTransportService, connectionLookup,
                        aliasFilter, concreteIndexBoosts, searchPhaseController, executor, searchRequest, listener, shardIterators,
                        timeProvider, clusterStateVersion, task, clusters);
                    break;
                case QUERY_AND_FETCH:
                case QUERY_THEN_FETCH:
                    searchAsyncAction = new SearchQueryThenFetchAsyncAction(logger, searchTransportService, connectionLookup,
                        aliasFilter, concreteIndexBoosts, searchPhaseController, executor, searchRequest, listener, shardIterators,
                        timeProvider, clusterStateVersion, task, clusters);
                    break;
                default:
                    throw new IllegalStateException("Unknown search type: [" + searchRequest.searchType() + "]");
            }
            return searchAsyncAction;
        }
    }

    /**
     * 如果此次请求shard数量超过上限，那么会拒绝此次请求
     * @param clusterService
     * @param shardCount
     */
    private static void failIfOverShardCountLimit(ClusterService clusterService, int shardCount) {
        final long shardCountLimit = clusterService.getClusterSettings().get(SHARD_COUNT_LIMIT_SETTING);
        if (shardCount > shardCountLimit) {
            throw new IllegalArgumentException("Trying to query " + shardCount + " shards, which is over the limit of "
                + shardCountLimit + ". This limit exists because querying many shards at the same time can make the "
                + "job of the coordinating node very CPU and/or memory intensive. It is usually a better idea to "
                + "have a smaller number of larger shards. Update [" + SHARD_COUNT_LIMIT_SETTING.getKey()
                + "] to a greater value if you really want to query that many shards at the same time.");
        }
    }
}
