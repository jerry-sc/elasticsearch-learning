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

import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.cluster.routing.GroupShardsIterator;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.search.SearchPhaseResult;
import org.elasticsearch.search.internal.AliasFilter;
import org.elasticsearch.transport.Transport;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;

final class SearchQueryThenFetchAsyncAction extends AbstractSearchAsyncAction<SearchPhaseResult> {

    private final SearchPhaseController searchPhaseController;

    /**
     *
     * @param logger logger
     * @param searchTransportService 远程搜索传输服务
     * @param nodeIdToConnection 节点连接管理器
     * @param aliasFilter
     * @param concreteIndexBoosts
     * @param searchPhaseController search controller
     * @param executor 线程池
     * @param request 此次请求
     * @param listener 响应监听回调
     * @param shardsIts 该索引的所有分片迭代器
     * @param timeProvider 执行时间统计器
     * @param clusterStateVersion version
     * @param task 此次搜索任务
     * @param clusters cluster
     */
    SearchQueryThenFetchAsyncAction(final Logger logger, final SearchTransportService searchTransportService,
            final BiFunction<String, String, Transport.Connection> nodeIdToConnection, final Map<String, AliasFilter> aliasFilter,
            final Map<String, Float> concreteIndexBoosts, final SearchPhaseController searchPhaseController, final Executor executor,
            final SearchRequest request, final ActionListener<SearchResponse> listener,
            final GroupShardsIterator<SearchShardIterator> shardsIts, final TransportSearchAction.SearchTimeProvider timeProvider,
            long clusterStateVersion, SearchTask task, SearchResponse.Clusters clusters) {
        super("query", logger, searchTransportService, nodeIdToConnection, aliasFilter, concreteIndexBoosts, executor, request, listener,
            shardsIts, timeProvider, clusterStateVersion, task, searchPhaseController.newSearchPhaseResults(request, shardsIts.size()),
                request.getMaxConcurrentShardRequests(), clusters);
        this.searchPhaseController = searchPhaseController;
    }

    protected void executePhaseOnShard(final SearchShardIterator shardIt, final ShardRouting shard,
                                       final SearchActionListener<SearchPhaseResult> listener) {
        // 发起远程shard query请求
        getSearchTransport().sendExecuteQuery(getConnection(shardIt.getClusterAlias(), shard.currentNodeId()),
            buildShardSearchRequest(shardIt), getTask(), listener);
    }

    @Override
    protected SearchPhase getNextPhase(final SearchPhaseResults<SearchPhaseResult> results, final SearchPhaseContext context) {
        return new FetchSearchPhase(results, searchPhaseController, context);
    }
}
