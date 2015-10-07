/**
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

package org.apache.ambari.server.topology;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.controller.ClusterRequest;
import org.apache.ambari.server.controller.ConfigurationRequest;
import org.apache.ambari.server.controller.internal.BlueprintConfigurationProcessor;
import org.apache.ambari.server.controller.internal.ClusterResourceProvider;
import org.apache.ambari.server.controller.internal.ConfigurationTopologyException;
import org.apache.ambari.server.controller.internal.Stack;
import org.apache.ambari.server.state.SecurityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Responsible for cluster configuration.
 */
public class ClusterConfigurationRequest {

  protected final static Logger LOG = LoggerFactory.getLogger(ClusterConfigurationRequest.class);

  private AmbariContext ambariContext;
  private ClusterTopology clusterTopology;
  private BlueprintConfigurationProcessor configurationProcessor;
  private Stack stack;

  public ClusterConfigurationRequest(AmbariContext ambariContext, ClusterTopology clusterTopology, boolean setInitial) {
    this.ambariContext = ambariContext;
    this.clusterTopology = clusterTopology;
    Blueprint blueprint = clusterTopology.getBlueprint();
    this.stack = blueprint.getStack();
    // set initial configuration (not topology resolved)
    this.configurationProcessor = new BlueprintConfigurationProcessor(clusterTopology);
    if (setInitial) {
      setConfigurationsOnCluster(clusterTopology, TopologyManager.INITIAL_CONFIG_TAG, Collections.<String>emptySet());
    }
  }

  // get names of required host groups
  public Collection<String> getRequiredHostGroups() {
    return configurationProcessor.getRequiredHostGroups();
  }

  public void process() throws AmbariException, ConfigurationTopologyException {
    // this will update the topo cluster config and all host group configs in the cluster topology
    Set<String> updatedConfigTypes = Collections.emptySet();
    try {
      updatedConfigTypes =
        configurationProcessor.doUpdateForClusterCreate();
    } catch (ConfigurationTopologyException e) {
      //log and continue to set configs on cluster to make progress
      LOG.error("An exception occurred while doing configuration topology update: " + e, e);
    }

    setConfigurationsOnCluster(clusterTopology, TopologyManager.TOPOLOGY_RESOLVED_TAG, updatedConfigTypes);
  }

  /**
   * Set all configurations on the cluster resource.
   * @param clusterTopology  cluster topology
   * @param tag              config tag
   */
  public void setConfigurationsOnCluster(ClusterTopology clusterTopology, String tag, Set<String> updatedConfigTypes)  {
    //todo: also handle setting of host group scoped configuration which is updated by config processor
    List<BlueprintServiceConfigRequest> configurationRequests = new LinkedList<BlueprintServiceConfigRequest>();

    Blueprint blueprint = clusterTopology.getBlueprint();
    Configuration clusterConfiguration = clusterTopology.getConfiguration();

    for (String service : blueprint.getServices()) {
      //todo: remove intermediate request type
      // one bp config request per service
      BlueprintServiceConfigRequest blueprintConfigRequest = new BlueprintServiceConfigRequest(service);

      for (String serviceConfigType : stack.getAllConfigurationTypes(service)) {
        Set<String> excludedConfigTypes = stack.getExcludedConfigurationTypes(service);
        if (!excludedConfigTypes.contains(serviceConfigType)) {
          // skip handling of cluster-env here
          if (! serviceConfigType.equals("cluster-env")) {
            if (clusterConfiguration.getFullProperties().containsKey(serviceConfigType)) {
              blueprintConfigRequest.addConfigElement(serviceConfigType,
                  clusterConfiguration.getFullProperties().get(serviceConfigType),
                  clusterConfiguration.getFullAttributes().get(serviceConfigType));
            }
          }
        }
      }

      configurationRequests.add(blueprintConfigRequest);
    }

    // since the stack returns "cluster-env" with each service's config ensure that only one
    // ClusterRequest occurs for the global cluster-env configuration
    BlueprintServiceConfigRequest globalConfigRequest = new BlueprintServiceConfigRequest("GLOBAL-CONFIG");
    Map<String, String> clusterEnvProps = clusterConfiguration.getFullProperties().get("cluster-env");
    Map<String, Map<String, String>> clusterEnvAttributes = clusterConfiguration.getFullAttributes().get("cluster-env");

    globalConfigRequest.addConfigElement("cluster-env", clusterEnvProps,clusterEnvAttributes);
    configurationRequests.add(globalConfigRequest);

    setConfigurationsOnCluster(configurationRequests, tag, updatedConfigTypes);
  }

  /**
   * Creates a ClusterRequest for each service that
   *   includes any associated config types and configuration. The Blueprints
   *   implementation will now create one ClusterRequest per service, in order
   *   to comply with the ServiceConfigVersioning framework in Ambari.
   *
   * This method will also send these requests to the management controller.
   *
   * @param configurationRequests a list of requests to send to the AmbariManagementController.
   */
  private void setConfigurationsOnCluster(List<BlueprintServiceConfigRequest> configurationRequests,
                                         String tag, Set<String> updatedConfigTypes)  {
    String clusterName = null;
    try {
      clusterName = ambariContext.getClusterName(clusterTopology.getClusterId());
    } catch (AmbariException e) {
      LOG.error("Cannot get cluster name for clusterId = " + clusterTopology.getClusterId(), e);
      throw new RuntimeException(e);
    }
    // iterate over services to deploy
    for (BlueprintServiceConfigRequest blueprintConfigRequest : configurationRequests) {
      ClusterRequest clusterRequest = null;
      // iterate over the config types associated with this service
      List<ConfigurationRequest> requestsPerService = new LinkedList<ConfigurationRequest>();
      for (BlueprintServiceConfigElement blueprintElement : blueprintConfigRequest.getConfigElements()) {
        Map<String, Object> clusterProperties = new HashMap<String, Object>();
        clusterProperties.put(ClusterResourceProvider.CLUSTER_NAME_PROPERTY_ID, clusterName);
        clusterProperties.put(ClusterResourceProvider.CLUSTER_DESIRED_CONFIGS_PROPERTY_ID + "/type", blueprintElement.getTypeName());
        clusterProperties.put(ClusterResourceProvider.CLUSTER_DESIRED_CONFIGS_PROPERTY_ID + "/tag", tag);
        for (Map.Entry<String, String> entry : blueprintElement.getConfiguration().entrySet()) {
          clusterProperties.put(ClusterResourceProvider.CLUSTER_DESIRED_CONFIGS_PROPERTY_ID +
              "/properties/" + entry.getKey(), entry.getValue());
        }
        if (blueprintElement.getAttributes() != null) {
          for (Map.Entry<String, Map<String, String>> attribute : blueprintElement.getAttributes().entrySet()) {
            String attributeName = attribute.getKey();
            for (Map.Entry<String, String> attributeOccurrence : attribute.getValue().entrySet()) {
              clusterProperties.put(ClusterResourceProvider.CLUSTER_DESIRED_CONFIGS_PROPERTY_ID + "/properties_attributes/"
                  + attributeName + "/" + attributeOccurrence.getKey(), attributeOccurrence.getValue());
            }
          }
        }

        // only create one cluster request per service, which includes
        // all the configuration types for that service
        if (clusterRequest == null) {
          SecurityType securityType;
          String requestedSecurityType = (String) clusterProperties.get(
              ClusterResourceProvider.CLUSTER_SECURITY_TYPE_PROPERTY_ID);
          if(requestedSecurityType == null)
            securityType = null;
          else {
            try {
              securityType = SecurityType.valueOf(requestedSecurityType.toUpperCase());
            } catch (IllegalArgumentException e) {
              throw new IllegalArgumentException(String.format(
                  "Cannot set cluster security type to invalid value: %s", requestedSecurityType));
            }
          }

          clusterRequest = new ClusterRequest(
              (Long) clusterProperties.get(ClusterResourceProvider.CLUSTER_ID_PROPERTY_ID),
              (String) clusterProperties.get(ClusterResourceProvider.CLUSTER_NAME_PROPERTY_ID),
              (String) clusterProperties.get(ClusterResourceProvider.CLUSTER_PROVISIONING_STATE_PROPERTY_ID),
              securityType,
              (String) clusterProperties.get(ClusterResourceProvider.CLUSTER_VERSION_PROPERTY_ID),
              null);
        }

        List<ConfigurationRequest> listOfRequests = ambariContext.createConfigurationRequests(clusterProperties);
        requestsPerService.addAll(listOfRequests);
      }

      // set total list of config requests, including all config types for this service
      if (clusterRequest != null) {
        clusterRequest.setDesiredConfig(requestsPerService);
        LOG.info("Sending cluster config update request for service = " + blueprintConfigRequest.getServiceName());
        ambariContext.setConfigurationOnCluster(clusterRequest);
      } else {
        LOG.error("ClusterRequest should not be null for service = " + blueprintConfigRequest.getServiceName());
      }
    }

    if (tag.equals(TopologyManager.TOPOLOGY_RESOLVED_TAG)) {
      // if this is a request to resolve config, then wait until resolution is completed
      try {
        // wait until the cluster topology configuration is set/resolved
        ambariContext.waitForConfigurationResolution(clusterName, updatedConfigTypes);
      } catch (AmbariException e) {
        LOG.error("Error while attempting to wait for the cluster configuration to reach TOPOLOGY_RESOLVED state.", e);
      }
    }
  }

  /**
   * Internal class meant to represent the collection of configuration
   * items and configuration attributes that are associated with a given service.
   *
   * This class is used to support proper configuration versioning when
   * Ambari Blueprints is used to deploy a cluster.
   */
  private static class BlueprintServiceConfigRequest {

    private final String serviceName;

    private List<BlueprintServiceConfigElement> configElements =
        new LinkedList<BlueprintServiceConfigElement>();

    BlueprintServiceConfigRequest(String serviceName) {
      this.serviceName = serviceName;
    }

    void addConfigElement(String type, Map<String, String> props, Map<String, Map<String, String>> attributes) {
      if (props == null) {
        props = Collections.emptyMap();
      }

      if (attributes == null) {
        attributes = Collections.emptyMap();
      }
      configElements.add(new BlueprintServiceConfigElement(type, props, attributes));
    }

    public String getServiceName() {
      return serviceName;
    }

    List<BlueprintServiceConfigElement> getConfigElements() {
      return configElements;
    }
  }

  /**
   * Internal class that represents the configuration
   *  and attributes for a given configuration type.
   */
  private static class BlueprintServiceConfigElement {
    private final String typeName;

    private final Map<String, String> configuration;

    private final Map<String, Map<String, String>> attributes;

    BlueprintServiceConfigElement(String type, Map<String, String> props, Map<String, Map<String, String>> attributes) {
      this.typeName = type;
      this.configuration = props;
      this.attributes = attributes;
    }

    public String getTypeName() {
      return typeName;
    }

    public Map<String, String> getConfiguration() {
      return configuration;
    }

    public Map<String, Map<String, String>> getAttributes() {
      return attributes;
    }
  }
}
