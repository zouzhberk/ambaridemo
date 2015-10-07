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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.ServiceNotFoundException;
import org.apache.ambari.server.controller.PrereqCheckRequest;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Config;
import org.apache.ambari.server.state.DesiredConfig;
import org.apache.ambari.server.state.stack.PrereqCheckStatus;
import org.apache.ambari.server.state.stack.PrerequisiteCheck;
import org.apache.commons.lang.StringUtils;

import com.google.inject.Singleton;

/**
 * Checks that MR jobs reference hadoop libraries from the distributed cache.
 */
@Singleton
@UpgradeCheck(group = UpgradeCheckGroup.NAMENODE_HA, order = 3.0f)
public class ServicesMapReduceDistributedCacheCheck extends AbstractCheckDescriptor {

  static final String KEY_APP_CLASSPATH = "app_classpath";
  static final String KEY_FRAMEWORK_PATH = "framework_path";
  static final String KEY_NOT_DFS = "not_dfs";

  @Override
  public boolean isApplicable(PrereqCheckRequest request)
    throws AmbariException {

    if (!super.isApplicable(request)) {
      return false;
    }

    final Cluster cluster = clustersProvider.get().getCluster(request.getClusterName());
    try {
      cluster.getService("YARN");
    } catch (ServiceNotFoundException ex) {
      return false;
    }

    PrereqCheckStatus ha = request.getResult(CheckDescription.SERVICES_NAMENODE_HA);
    if (null != ha && ha == PrereqCheckStatus.FAIL) {
      return false;
    }

    return true;
  }

  /**
   * Constructor.
   */
  public ServicesMapReduceDistributedCacheCheck() {
    super(CheckDescription.SERVICES_MR_DISTRIBUTED_CACHE);
  }

  @Override
  public void perform(PrerequisiteCheck prerequisiteCheck, PrereqCheckRequest request) throws AmbariException {
    final String clusterName = request.getClusterName();
    final Cluster cluster = clustersProvider.get().getCluster(clusterName);
    final String mrConfigType = "mapred-site";
    final String coreSiteConfigType = "core-site";
    final Map<String, DesiredConfig> desiredConfigs = cluster.getDesiredConfigs();

    final DesiredConfig mrDesiredConfig = desiredConfigs.get(mrConfigType);
    final DesiredConfig coreSiteDesiredConfig = desiredConfigs.get(coreSiteConfigType);
    final Config mrConfig = cluster.getConfig(mrConfigType, mrDesiredConfig.getTag());
    final Config coreSiteConfig = cluster.getConfig(coreSiteConfigType, coreSiteDesiredConfig.getTag());
    final String applicationClasspath = mrConfig.getProperties().get("mapreduce.application.classpath");
    final String frameworkPath = mrConfig.getProperties().get("mapreduce.application.framework.path");
    final String defaultFS = coreSiteConfig.getProperties().get("fs.defaultFS");

    List<String> errorMessages = new ArrayList<String>();
    if (applicationClasspath == null || applicationClasspath.isEmpty()) {
      errorMessages.add(getFailReason(KEY_APP_CLASSPATH, prerequisiteCheck, request));
    }

    if (frameworkPath == null || frameworkPath.isEmpty()) {
      errorMessages.add(getFailReason(KEY_FRAMEWORK_PATH, prerequisiteCheck, request));
    }

    if (!errorMessages.isEmpty()) {
      prerequisiteCheck.getFailedOn().add("MAPREDUCE2");
      prerequisiteCheck.setStatus(PrereqCheckStatus.FAIL);
      prerequisiteCheck.setFailReason(StringUtils.join(errorMessages, " "));
      return;
    }

    if (!frameworkPath.matches("^[^:]*dfs:.*") && (defaultFS == null || !defaultFS.matches("^[^:]*dfs:.*"))) {
      prerequisiteCheck.getFailedOn().add("MAPREDUCE2");
      prerequisiteCheck.setStatus(PrereqCheckStatus.FAIL);
      prerequisiteCheck.setFailReason(getFailReason(KEY_NOT_DFS, prerequisiteCheck, request));
    }
  }
}
