/*
 * Zeebe Broker Core
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package io.zeebe.broker.clustering.base.partitions;

import io.atomix.cluster.messaging.ClusterCommunicationService;
import io.atomix.cluster.messaging.ClusterEventService;
import io.zeebe.broker.Loggers;
import io.zeebe.broker.engine.EngineService;
import io.zeebe.broker.engine.EngineServiceNames;
import io.zeebe.broker.engine.impl.StateReplication;
import io.zeebe.broker.exporter.ExporterServiceNames;
import io.zeebe.broker.logstreams.delete.FollowerLogStreamDeletionService;
import io.zeebe.broker.logstreams.delete.LeaderLogStreamDeletionService;
import io.zeebe.broker.logstreams.state.DefaultOnDemandSnapshotReplication;
import io.zeebe.broker.system.configuration.BrokerCfg;
import io.zeebe.db.ZeebeDb;
import io.zeebe.distributedlog.StorageConfiguration;
import io.zeebe.engine.state.DefaultZeebeDbFactory;
import io.zeebe.engine.state.StateStorageFactory;
import io.zeebe.logstreams.impl.delete.DeletionService;
import io.zeebe.logstreams.log.LogStream;
import io.zeebe.logstreams.state.NoneSnapshotReplication;
import io.zeebe.logstreams.state.SnapshotReplication;
import io.zeebe.logstreams.state.StateSnapshotController;
import io.zeebe.logstreams.state.StateStorage;
import io.zeebe.servicecontainer.Injector;
import io.zeebe.servicecontainer.Service;
import io.zeebe.servicecontainer.ServiceStartContext;
import io.zeebe.servicecontainer.ServiceStopContext;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;

/** Service representing a partition. */
public class Partition implements Service<Partition> {
  public static final String PARTITION_NAME_FORMAT = "raft-atomix-partition-%d";
  public static final Logger LOG = Loggers.CLUSTERING_LOGGER;
  private final StorageConfiguration configuration;
  private final BrokerCfg brokerCfg;
  private final ClusterCommunicationService communicationService;
  private DefaultOnDemandSnapshotReplication snapshotRequestServer;
  private ExecutorService executor;
  private StateSnapshotController snapshotController;
  private SnapshotReplication stateReplication;

  public static String getPartitionName(final int partitionId) {
    return String.format(PARTITION_NAME_FORMAT, partitionId);
  }

  private final Injector<LogStream> logStreamInjector = new Injector<>();

  private final ClusterEventService clusterEventService;
  private final int partitionId;
  private final RaftState state;

  private LogStream logStream;

  public Partition(
      final StorageConfiguration configuration,
      BrokerCfg brokerCfg,
      ClusterCommunicationService communicationService,
      final ClusterEventService clusterEventService,
      final int partitionId,
      final RaftState state) {
    this.configuration = configuration;
    this.brokerCfg = brokerCfg;
    this.clusterEventService = clusterEventService;
    this.partitionId = partitionId;
    this.state = state;
    this.communicationService = communicationService;
  }

  @Override
  public void start(final ServiceStartContext startContext) {
    final String streamProcessorName = EngineService.PROCESSOR_NAME;
    logStream = logStreamInjector.getValue();

    snapshotController = createSnapshotController();

    if (state == RaftState.FOLLOWER) {
      final DeletionService followerDeletionService =
          new FollowerLogStreamDeletionService(
              logStream, snapshotController, brokerCfg.getCluster().getNodeId());

      snapshotController.consumeReplicatedSnapshots(followerDeletionService);
    } else {
      final LeaderLogStreamDeletionService leaderDeletionService =
          new LeaderLogStreamDeletionService(logStream);
      startContext
          .createService(
              EngineServiceNames.leaderLogStreamDeletionService(partitionId), leaderDeletionService)
          .dependency(
              ExporterServiceNames.EXPORTER_MANAGER,
              leaderDeletionService.getExporterManagerInjector())
          .install();

      try {
        snapshotController.recover();
      } catch (Exception e) {
        throw new IllegalStateException(
            String.format(
                "Unexpected error occurred while recovering snapshot controller during leader partition install for partition %d",
                partitionId),
            e);
      }
      executor =
          Executors.newSingleThreadExecutor(
              (r) -> new Thread(r, String.format("snapshot-request-server-%d", partitionId)));
      snapshotRequestServer =
          new DefaultOnDemandSnapshotReplication(
              communicationService, partitionId, streamProcessorName, executor);
      snapshotRequestServer.serve(
          request -> {
            LOG.info("Received snapshot replication request for partition {}", partitionId);
            this.snapshotController.replicateLatestSnapshot(Runnable::run);
          });
    }
  }

  public StorageConfiguration getConfiguration() {
    return configuration;
  }

  private StateSnapshotController createSnapshotController() {
    final String streamProcessorName = EngineService.PROCESSOR_NAME;

    final StateStorageFactory storageFactory =
        new StateStorageFactory(configuration.getStatesDirectory());
    final StateStorage stateStorage = storageFactory.create(partitionId, streamProcessorName);

    stateReplication =
        shouldReplicateSnapshots()
            ? new StateReplication(clusterEventService, partitionId, streamProcessorName)
            : new NoneSnapshotReplication();

    return new StateSnapshotController(
        DefaultZeebeDbFactory.DEFAULT_DB_FACTORY,
        stateStorage,
        stateReplication,
        brokerCfg.getData().getMaxSnapshots());
  }

  private boolean shouldReplicateSnapshots() {
    return brokerCfg.getCluster().getReplicationFactor() > 1;
  }

  @Override
  public void stop(ServiceStopContext stopContext) {
    stateReplication.close();

    try {
      snapshotController.close();
    } catch (Exception e) {
      LOG.error(
          "Unexpected error occurred while closing the state snapshot controller for partition {}.",
          partitionId,
          e);
    }

    if (snapshotRequestServer != null) {
      snapshotRequestServer.close();
    }
    if (executor != null) {
      executor.shutdown();
    }
  }

  @Override
  public Partition get() {
    return this;
  }

  public int getPartitionId() {
    return partitionId;
  }

  public StateSnapshotController getSnapshotController() {
    return snapshotController;
  }

  public RaftState getState() {
    return state;
  }

  public LogStream getLogStream() {
    return logStream;
  }

  public Injector<LogStream> getLogStreamInjector() {
    return logStreamInjector;
  }

  public ZeebeDb getZeebeDb() {
    return snapshotController.openDb();
  }
}
