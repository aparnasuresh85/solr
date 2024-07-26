/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.handler.admin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.cloud.Aliases;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.DocRouter;
import org.apache.solr.common.cloud.PerReplicaStates;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.cloud.ZkNodeProps;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.params.ShardParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.common.util.Utils;
import org.apache.zookeeper.KeeperException;

public class ClusterStatus {
  private final ZkStateReader zkStateReader;
  private final ZkNodeProps message;
  private final String collectionParam; // maybe null
  private final boolean liveNodesParam;
  private final boolean clusterPropertiesParam;
  private final boolean rolesParam;
  private final boolean includeAll;

  /** Shard / collection health state. */
  public enum Health {
    /** All replicas up, leader exists. */
    GREEN,
    /** Some replicas down, leader exists. */
    YELLOW,
    /** Most replicas down, leader exists. */
    ORANGE,
    /** No leader or all replicas down. */
    RED;

    public static final float ORANGE_LEVEL = 0.5f;
    public static final float RED_LEVEL = 0.0f;

    public static Health calcShardHealth(float fractionReplicasUp, boolean hasLeader) {
      if (hasLeader) {
        if (fractionReplicasUp == 1.0f) {
          return GREEN;
        } else if (fractionReplicasUp > ORANGE_LEVEL) {
          return YELLOW;
        } else if (fractionReplicasUp > RED_LEVEL) {
          return ORANGE;
        } else {
          return RED;
        }
      } else {
        return RED;
      }
    }

    /** Combine multiple states into one. Always reports as the worst state. */
    public static Health combine(Collection<Health> states) {
      Health res = GREEN;
      for (Health state : states) {
        if (state.ordinal() > res.ordinal()) {
          res = state;
        }
      }
      return res;
    }
  }

  public ClusterStatus(ZkStateReader zkStateReader, ZkNodeProps props) {
    this.zkStateReader = zkStateReader;
    this.message = props;
    collectionParam = props.getStr(ZkStateReader.COLLECTION_PROP);
    liveNodesParam = props.getBool(ZkStateReader.LIVENODES_PROP, false);
    clusterPropertiesParam = props.getBool(ZkStateReader.CLUSTER_PROP, false);
    rolesParam = props.getBool(ZkStateReader.ROLES_PROP, false);
    includeAll = props.getBool(ZkStateReader.INCLUDE_ALL, true);
  }

  public void getClusterStatus(NamedList<Object> results)
      throws KeeperException, InterruptedException {

    List<String> liveNodes = null;
    NamedList<Object> clusterStatus = new SimpleOrderedMap<>();
    if (includeAll || collectionParam != null || liveNodesParam) {
      liveNodes =
          zkStateReader.getZkClient().getChildren(ZkStateReader.LIVE_NODES_ZKNODE, null, true);
      // add live_nodes
      clusterStatus.add("live_nodes", liveNodes);
    }

    if (includeAll || collectionParam != null)
      fetchClusterStatusForCollOrAlias(clusterStatus, liveNodes);

    if (includeAll || clusterPropertiesParam) {
      // read cluster properties
      Map<String, Object> clusterProps = zkStateReader.getClusterProperties();
      if (clusterProps != null && !clusterProps.isEmpty()) {
        clusterStatus.add("properties", clusterProps);
      }
    }

    // add the roles map
    if (includeAll || rolesParam) {

      Map<?, ?> roles = null;
      if (zkStateReader.getZkClient().exists(ZkStateReader.ROLES, true)) {
        roles =
            (Map<?, ?>)
                Utils.fromJSON(
                    zkStateReader.getZkClient().getData(ZkStateReader.ROLES, null, null, true));
      }
      if (roles != null) {
        clusterStatus.add("roles", roles);
      }
    }

    results.add("cluster", clusterStatus);
  }

  private void fetchClusterStatusForCollOrAlias(
      NamedList<Object> clusterStatus, List<String> liveNodes) {

    // read aliases
    Aliases aliases = zkStateReader.getAliases();
    Map<String, List<String>> collectionVsAliases = new HashMap<>();
    Map<String, List<String>> aliasVsCollections = aliases.getCollectionAliasListMap();
    for (Map.Entry<String, List<String>> entry : aliasVsCollections.entrySet()) {
      String alias = entry.getKey();
      List<String> colls = entry.getValue();
      for (String coll : colls) {
        if (collectionParam == null || collectionParam.equals(coll)) {
          List<String> list = collectionVsAliases.computeIfAbsent(coll, k -> new ArrayList<>());
          list.add(alias);
        }
      }
    }

    ClusterState clusterState = zkStateReader.getClusterState();

    String routeKey = message.getStr(ShardParams._ROUTE_);
    String shard = message.getStr(ZkStateReader.SHARD_ID_PROP);

    Map<String, DocCollection> collectionsMap = null;
    if (collectionParam == null) {
      collectionsMap = clusterState.getCollectionsMap();
    } else {
      collectionsMap =
          Collections.singletonMap(
              collectionParam, clusterState.getCollectionOrNull(collectionParam));
    }

    boolean isAlias = aliasVsCollections.containsKey(collectionParam);
    boolean didNotFindCollection = collectionsMap.get(collectionParam) == null;

    if (didNotFindCollection && isAlias) {
      // In this case this.collection is an alias name not a collection
      // get all collections and filter out collections not in the alias
      // clusterState.getCollectionsMap() should be replaced with an inexpensive call
      collectionsMap =
          clusterState.getCollectionsMap().entrySet().stream()
              .filter((entry) -> aliasVsCollections.get(collectionParam).contains(entry.getKey()))
              .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    NamedList<Object> collectionProps = new SimpleOrderedMap<>();

    // loop will run only once
    for (Map.Entry<String, DocCollection> entry : collectionsMap.entrySet()) {
      Map<String, Object> collectionStatus;
      String name = entry.getKey();
      DocCollection clusterStateCollection = entry.getValue();
      if (clusterStateCollection == null) {
        if (collectionParam != null) {
          SolrException solrException =
              new SolrException(
                  SolrException.ErrorCode.BAD_REQUEST, "Collection: " + name + " not found");
          solrException.setMetadata("CLUSTERSTATUS", "NOT_FOUND");
          throw solrException;
        } else {
          // collection might have got deleted at the same time
          continue;
        }
      }

      Set<String> requestedShards = new HashSet<>();
      if (routeKey != null) {
        DocRouter router = clusterStateCollection.getRouter();
        Collection<Slice> slices = router.getSearchSlices(routeKey, null, clusterStateCollection);
        for (Slice slice : slices) {
          requestedShards.add(slice.getName());
        }
      }
      if (shard != null) {
        String[] paramShards = shard.split(",");
        requestedShards.addAll(Arrays.asList(paramShards));
      }

      byte[] bytes = Utils.toJSON(clusterStateCollection);
      @SuppressWarnings("unchecked")
      Map<String, Object> docCollection = (Map<String, Object>) Utils.fromJSON(bytes);
      collectionStatus = getCollectionStatus(docCollection, name, requestedShards);

      collectionStatus.put("znodeVersion", clusterStateCollection.getZNodeVersion());
      collectionStatus.put(
          "creationTimeMillis", clusterStateCollection.getCreationTime().toEpochMilli());

      if (collectionVsAliases.containsKey(name) && !collectionVsAliases.get(name).isEmpty()) {
        collectionStatus.put("aliases", collectionVsAliases.get(name));
      }
      String configName = clusterStateCollection.getConfigName();
      collectionStatus.put("configName", configName);
      if (message.getBool("prs", false) && clusterStateCollection.isPerReplicaState()) {
        PerReplicaStates prs = clusterStateCollection.getPerReplicaStates();
        collectionStatus.put("PRS", prs);
      }
      collectionProps.add(name, collectionStatus);
    }

    // add the alias map too
    Map<String, String> collectionAliasMap = aliases.getCollectionAliasMap(); // comma delim
    if (!collectionAliasMap.isEmpty()) {
      clusterStatus.add("aliases", collectionAliasMap);
    }

    // now we need to walk the collectionProps tree to cross-check replica state with live nodes
    crossCheckReplicaStateWithLiveNodes(liveNodes, collectionProps);

    clusterStatus.add("collections", collectionProps);
  }

  /**
   * Get collection status from cluster state. Can return collection status by given shard name.
   *
   * @param collection collection map parsed from JSON-serialized {@link ClusterState}
   * @param name collection name
   * @param requestedShards a set of shards to be returned in the status. An empty or null values
   *     indicates <b>all</b> shards.
   * @return map of collection properties
   */
  @SuppressWarnings("unchecked")
  private Map<String, Object> getCollectionStatus(
      Map<String, Object> collection, String name, Set<String> requestedShards) {
    if (collection == null) {
      throw new SolrException(
          SolrException.ErrorCode.BAD_REQUEST, "Collection: " + name + " not found");
    }
    if (requestedShards == null || requestedShards.isEmpty()) {
      return postProcessCollectionJSON(collection);
    } else {
      Map<String, Object> shards = (Map<String, Object>) collection.get("shards");
      Map<String, Object> selected = new HashMap<>();
      for (String selectedShard : requestedShards) {
        if (!shards.containsKey(selectedShard)) {
          throw new SolrException(
              SolrException.ErrorCode.BAD_REQUEST,
              "Collection: " + name + " shard: " + selectedShard + " not found");
        }
        selected.put(selectedShard, shards.get(selectedShard));
        collection.put("shards", selected);
      }
      return postProcessCollectionJSON(collection);
    }
  }

  /**
   * Walks the tree of collection status to verify that any replicas not reporting a "down" status
   * is on a live node, if any replicas reporting their status as "active" but the node is not live
   * is marked as "down"; used by CLUSTERSTATUS.
   *
   * @param liveNodes List of currently live node names.
   * @param collectionProps Map of collection status information pulled directly from ZooKeeper.
   */
  @SuppressWarnings("unchecked")
  protected void crossCheckReplicaStateWithLiveNodes(
      List<String> liveNodes, NamedList<Object> collectionProps) {
    for (Map.Entry<String, Object> next : collectionProps) {
      Map<String, Object> collMap = (Map<String, Object>) next.getValue();
      Map<String, Object> shards = (Map<String, Object>) collMap.get("shards");
      for (Object nextShard : shards.values()) {
        Map<String, Object> shardMap = (Map<String, Object>) nextShard;
        Map<String, Object> replicas = (Map<String, Object>) shardMap.get("replicas");
        for (Object nextReplica : replicas.values()) {
          Map<String, Object> replicaMap = (Map<String, Object>) nextReplica;
          if (Replica.State.getState((String) replicaMap.get(ZkStateReader.STATE_PROP))
              != Replica.State.DOWN) {
            // not down, so verify the node is live
            String node_name = (String) replicaMap.get(ZkStateReader.NODE_NAME_PROP);
            if (!liveNodes.contains(node_name)) {
              // node is not live, so this replica is actually down
              replicaMap.put(ZkStateReader.STATE_PROP, Replica.State.DOWN.toString());
            }
          }
        }
      }
    }
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> postProcessCollectionJSON(Map<String, Object> collection) {
    final Map<String, Map<String, Object>> shards =
        collection != null
            ? (Map<String, Map<String, Object>>)
                collection.getOrDefault("shards", Collections.emptyMap())
            : Collections.emptyMap();
    final List<Health> healthStates = new ArrayList<>(shards.size());
    shards.forEach(
        (shardName, s) -> {
          final Map<String, Map<String, Object>> replicas =
              (Map<String, Map<String, Object>>) s.getOrDefault("replicas", Collections.emptyMap());
          int[] totalVsActive = new int[2];
          boolean hasLeader = false;
          for (Map<String, Object> r : replicas.values()) {
            totalVsActive[0]++;
            boolean active = false;
            if (Replica.State.ACTIVE.toString().equals(r.get("state"))) {
              totalVsActive[1]++;
              active = true;
            }
            if ("true".equals(r.get("leader")) && active) {
              hasLeader = true;
            }
          }
          float ratioActive;
          if (totalVsActive[0] == 0) {
            ratioActive = 0.0f;
          } else {
            ratioActive = (float) totalVsActive[1] / totalVsActive[0];
          }
          Health health = Health.calcShardHealth(ratioActive, hasLeader);
          s.put("health", health.toString());
          healthStates.add(health);
        });
    collection.put("health", Health.combine(healthStates).toString());
    return collection;
  }
}
