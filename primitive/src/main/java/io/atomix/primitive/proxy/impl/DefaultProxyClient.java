/*
 * Copyright 2018-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.primitive.proxy.impl;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.BaseEncoding;
import io.atomix.primitive.PrimitiveState;
import io.atomix.primitive.PrimitiveType;
import io.atomix.primitive.partition.PartitionId;
import io.atomix.primitive.partition.Partitioner;
import io.atomix.primitive.protocol.PrimitiveProtocol;
import io.atomix.primitive.proxy.ProxyClient;
import io.atomix.primitive.proxy.ProxySession;
import io.atomix.primitive.session.SessionClient;
import io.atomix.utils.concurrent.Futures;
import io.atomix.utils.serializer.Serializer;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Default primitive proxy.
 */
public class DefaultProxyClient<S> implements ProxyClient<S> {
  private final String name;
  private final PrimitiveType type;
  private final PrimitiveProtocol protocol;
  private final Serializer serializer;
  private final List<PartitionId> partitionIds = new CopyOnWriteArrayList<>();
  private final Map<PartitionId, ProxySession<S>> partitions = Maps.newConcurrentMap();
  private final Partitioner<String> partitioner;
  private final Set<Consumer<PrimitiveState>> stateChangeListeners = Sets.newCopyOnWriteArraySet();
  private final Map<PartitionId, PrimitiveState> states = Maps.newHashMap();
  private volatile PrimitiveState state = PrimitiveState.CLOSED;

  public DefaultProxyClient(
      String name,
      PrimitiveType type,
      PrimitiveProtocol protocol,
      Class<S> serviceType,
      Collection<SessionClient> partitions,
      Partitioner<String> partitioner) {
    this.name = checkNotNull(name, "name cannot be null");
    this.type = checkNotNull(type, "type cannot be null");
    this.protocol = checkNotNull(protocol, "protocol cannot be null");
    this.serializer = Serializer.using(type.namespace());
    this.partitioner = checkNotNull(partitioner, "partitioner cannot be null");
    partitions.forEach(partition -> {
      this.partitionIds.add(partition.partitionId());
      this.partitions.put(partition.partitionId(), new DefaultProxySession<>(partition, serviceType, serializer));
      states.put(partition.partitionId(), PrimitiveState.CLOSED);
      partition.addStateChangeListener(state -> onStateChange(partition.partitionId(), state));
    });
    Collections.sort(partitionIds);
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public PrimitiveType type() {
    return type;
  }

  @Override
  public PrimitiveProtocol protocol() {
    return protocol;
  }

  @Override
  public PrimitiveState getState() {
    return state;
  }

  @Override
  public Collection<ProxySession<S>> getPartitions() {
    return partitions.values();
  }

  @Override
  public Collection<PartitionId> getPartitionIds() {
    return partitions.keySet();
  }

  @Override
  public ProxySession<S> getPartition(PartitionId partitionId) {
    return partitions.get(partitionId);
  }

  @Override
  public PartitionId getPartitionId(String key) {
    return partitioner.partition(key, partitionIds);
  }

  @Override
  public PartitionId getPartitionId(Object key) {
    return partitioner.partition(BaseEncoding.base16().encode(serializer.encode(key)), partitionIds);
  }

  @Override
  public void addStateChangeListener(Consumer<PrimitiveState> listener) {
    stateChangeListeners.add(listener);
  }

  @Override
  public void removeStateChangeListener(Consumer<PrimitiveState> listener) {
    stateChangeListeners.remove(listener);
  }

  @Override
  public CompletableFuture<ProxyClient<S>> connect() {
    partitions.forEach((partitionId, partition) -> {
      partition.addStateChangeListener(state -> onStateChange(partitionId, state));
    });
    return Futures.allOf(partitions.values()
        .stream()
        .map(ProxySession::connect)
        .collect(Collectors.toList()))
        .thenApply(v -> this);
  }

  @Override
  public CompletableFuture<Void> delete() {
    return Futures.allOf(partitions.values()
        .stream()
        .map(ProxySession::delete)
        .collect(Collectors.toList()))
        .thenApply(v -> null);
  }

  @Override
  public CompletableFuture<Void> close() {
    return Futures.allOf(partitions.values()
        .stream()
        .map(ProxySession::close)
        .collect(Collectors.toList()))
        .thenApply(v -> null);
  }

  /**
   * Handles a partition proxy state change.
   */
  private synchronized void onStateChange(PartitionId partitionId, PrimitiveState state) {
    states.put(partitionId, state);
    switch (state) {
      case CONNECTED:
        if (this.state != PrimitiveState.CONNECTED && !states.containsValue(PrimitiveState.SUSPENDED) && !states.containsValue(PrimitiveState.CLOSED)) {
          this.state = PrimitiveState.CONNECTED;
          stateChangeListeners.forEach(l -> l.accept(PrimitiveState.CONNECTED));
        }
        break;
      case SUSPENDED:
        if (this.state == PrimitiveState.CONNECTED) {
          this.state = PrimitiveState.SUSPENDED;
          stateChangeListeners.forEach(l -> l.accept(PrimitiveState.SUSPENDED));
        }
        break;
      case CLOSED:
        if (this.state != PrimitiveState.CLOSED) {
          this.state = PrimitiveState.CLOSED;
          stateChangeListeners.forEach(l -> l.accept(PrimitiveState.CLOSED));
        }
        break;
    }
  }
}
