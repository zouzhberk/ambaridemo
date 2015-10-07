/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ambari.server.checks;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.ServiceComponentNotFoundException;
import org.apache.ambari.server.ServiceNotFoundException;
import org.apache.ambari.server.controller.PrereqCheckRequest;
import org.apache.ambari.server.orm.dao.HostComponentStateDAO;
import org.apache.ambari.server.orm.entities.HostComponentStateEntity;
import org.apache.ambari.server.stack.MasterHostResolver;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.ServiceComponent;
import org.apache.ambari.server.state.stack.PrereqCheckStatus;
import org.apache.ambari.server.state.stack.PrerequisiteCheck;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Checks that the Secondary NameNode is not present on any of the hosts.
 */
@Singleton
@UpgradeCheck(group = UpgradeCheckGroup.NAMENODE_HA, order = 2.0f)
public class SecondaryNamenodeDeletedCheck extends AbstractCheckDescriptor {
  @Inject
  HostComponentStateDAO hostComponentStateDao;
  /**
   * Constructor.
   */
  public SecondaryNamenodeDeletedCheck() {
    super(CheckDescription.SECONDARY_NAMENODE_MUST_BE_DELETED);
  }

  @Override
  public boolean isApplicable(PrereqCheckRequest request) throws AmbariException {
    if (!super.isApplicable(request)) {
      return false;
    }

    final Cluster cluster = clustersProvider.get().getCluster(request.getClusterName());
    try {
      cluster.getService("HDFS");
    } catch (ServiceNotFoundException ex) {
      return false;
    }

    PrereqCheckStatus ha = request.getResult(CheckDescription.SERVICES_NAMENODE_HA);
    if (null != ha && ha == PrereqCheckStatus.FAIL) {
      return false;
    }

    return true;
  }

  @Override
  public void perform(PrerequisiteCheck prerequisiteCheck, PrereqCheckRequest request) throws AmbariException {
    Set<String> hosts = new HashSet<String>();
    final String SECONDARY_NAMENODE = "SECONDARY_NAMENODE";

    final String clusterName = request.getClusterName();
    final Cluster cluster = clustersProvider.get().getCluster(clusterName);
    try {
      ServiceComponent serviceComponent = cluster.getService(MasterHostResolver.Service.HDFS.name()).getServiceComponent(SECONDARY_NAMENODE);
      if (serviceComponent != null) {
        hosts = serviceComponent.getServiceComponentHosts().keySet();
      }
    } catch (ServiceComponentNotFoundException err) {
      // This exception can be ignored if the component doesn't exist because it is a best-attempt at finding it.
      ;
    }

    // Try another method to find references to SECONDARY_NAMENODE
    if (hosts.isEmpty()) {
      List<HostComponentStateEntity> allHostComponents = hostComponentStateDao.findAll();
      for(HostComponentStateEntity hc : allHostComponents) {
        if (hc.getServiceName().equalsIgnoreCase(MasterHostResolver.Service.HDFS.name()) && hc.getComponentName().equalsIgnoreCase(SECONDARY_NAMENODE)) {
          hosts.add(hc.getHostName());
        }
      }
    }

    if (!hosts.isEmpty()) {
      prerequisiteCheck.getFailedOn().add(SECONDARY_NAMENODE);
      prerequisiteCheck.setStatus(PrereqCheckStatus.FAIL);
      prerequisiteCheck.setFailReason(getFailReason(prerequisiteCheck, request));
    }
  }
}
