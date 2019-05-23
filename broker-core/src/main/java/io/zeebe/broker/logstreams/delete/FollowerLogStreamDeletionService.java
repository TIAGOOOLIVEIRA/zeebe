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
package io.zeebe.broker.logstreams.delete;

import static io.zeebe.broker.clustering.base.partitions.Partition.LOG;

import io.zeebe.broker.exporter.ExporterManagerService;
import io.zeebe.broker.exporter.stream.ExportersState;
import io.zeebe.db.ZeebeDb;
import io.zeebe.logstreams.impl.delete.LogStreamDeletionService;
import io.zeebe.logstreams.log.LogStream;
import io.zeebe.logstreams.state.StateSnapshotController;
import io.zeebe.servicecontainer.Injector;

public class FollowerLogStreamDeletionService extends LogStreamDeletionService {
  private final Injector<ExporterManagerService> exporterManagerInjector = new Injector<>();
  private final StateSnapshotController snapshotController;
  private final int partitionId;
  private final int brokerId;

  private ExporterManagerService exporterManagerService;

  public FollowerLogStreamDeletionService(
      LogStream logStream, StateSnapshotController snapshotController, int brokerId) {
    super(logStream);
    this.snapshotController = snapshotController;
    this.partitionId = logStream.getPartitionId();
    this.brokerId = brokerId;
  }

  @Override
  protected long getMinimumExportedPosition() {
    try {
      if (snapshotController.getValidSnapshotsCount() > 0) {
        snapshotController.recover();
        final ZeebeDb zeebeDb = snapshotController.openDb();
        final ExportersState exporterState = new ExportersState(zeebeDb, zeebeDb.createContext());

        final long lowestPosition = exporterState.getLowestPosition();

        LOG.debug(
            "The lowest exported position for partition {} at broker {} is {}.",
            partitionId,
            brokerId,
            lowestPosition);
        return lowestPosition;
      } else {
        LOG.info(
            "Follower {} for partition {} has no exporter snapshot so it can't delete data.",
            brokerId,
            partitionId);
      }
    } catch (Exception e) {
      LOG.error(
          "Unexpected error occurred while obtaining the lowest exported position at follower {} for partition {}.",
          brokerId,
          partitionId,
          e);
    } finally {
      try {
        snapshotController.close();
      } catch (Exception e) {
        LOG.error("Unexpected error occurred while closing the DB.", e);
      }
    }

    return -1;
  }
}
