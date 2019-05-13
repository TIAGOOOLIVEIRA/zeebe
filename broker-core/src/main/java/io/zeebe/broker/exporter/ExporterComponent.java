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
package io.zeebe.broker.exporter;

import static io.zeebe.broker.clustering.base.ClusterBaseLayerServiceNames.LEADER_PARTITION_GROUP_NAME;
import static io.zeebe.broker.engine.EngineServiceNames.STREAM_PROCESSOR_SERVICE_FACTORY;
import static io.zeebe.broker.exporter.ExporterServiceNames.EXPORTER_MANAGER;

import io.zeebe.broker.system.Component;
import io.zeebe.broker.system.SystemContext;
import io.zeebe.broker.system.configuration.ExporterCfg;
import io.zeebe.servicecontainer.ServiceContainer;
import java.util.List;

public class ExporterComponent implements Component {

  @Override
  public void init(SystemContext context) {
    final ServiceContainer serviceContainer = context.getServiceContainer();

    final List<ExporterCfg> exporters = context.getBrokerConfiguration().getExporters();

    final ExporterManagerService exporterManagerService = new ExporterManagerService(exporters);

    serviceContainer
        .createService(EXPORTER_MANAGER, exporterManagerService)
        .dependency(
            STREAM_PROCESSOR_SERVICE_FACTORY,
            exporterManagerService.getStreamProcessorServiceFactoryInjector())
        .groupReference(
            LEADER_PARTITION_GROUP_NAME, exporterManagerService.getPartitionsGroupReference())
        .install();
  }
}
