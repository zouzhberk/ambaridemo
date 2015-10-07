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

package org.apache.ambari.server.controller;

import static org.apache.ambari.server.agent.ExecutionCommand.KeyNames.COMMAND_TIMEOUT;
import static org.apache.ambari.server.agent.ExecutionCommand.KeyNames.COMPONENT_CATEGORY;
import static org.apache.ambari.server.agent.ExecutionCommand.KeyNames.SCRIPT;
import static org.apache.ambari.server.agent.ExecutionCommand.KeyNames.SCRIPT_TYPE;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.ObjectNotFoundException;
import org.apache.ambari.server.Role;
import org.apache.ambari.server.RoleCommand;
import org.apache.ambari.server.StackAccessException;
import org.apache.ambari.server.actionmanager.Stage;
import org.apache.ambari.server.actionmanager.TargetHostType;
import org.apache.ambari.server.agent.ExecutionCommand;
import org.apache.ambari.server.agent.ExecutionCommand.KeyNames;
import org.apache.ambari.server.api.services.AmbariMetaInfo;
import org.apache.ambari.server.configuration.Configuration;
import org.apache.ambari.server.controller.internal.RequestResourceFilter;
import org.apache.ambari.server.customactions.ActionDefinition;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.ComponentInfo;
import org.apache.ambari.server.state.ServiceComponentHost;
import org.apache.ambari.server.state.ServiceInfo;
import org.apache.ambari.server.state.StackId;
import org.apache.ambari.server.state.svccomphost.ServiceComponentHostOpInProgressEvent;
import org.apache.ambari.server.utils.StageUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Helper class containing logic to process custom action execution requests
 */
@Singleton
public class AmbariActionExecutionHelper {
  private final static Logger LOG =
      LoggerFactory.getLogger(AmbariActionExecutionHelper.class);
  private static final String TYPE_PYTHON = "PYTHON";

  @Inject
  private Clusters clusters;
  @Inject
  private AmbariManagementController managementController;
  @Inject
  private AmbariMetaInfo ambariMetaInfo;
  @Inject
  private MaintenanceStateHelper maintenanceStateHelper;
  @Inject
  private Configuration configs;

  /**
   * Validates the request to execute an action.
   * @param actionRequest
   * @throws AmbariException
   */
  public void validateAction(ExecuteActionRequest actionRequest) throws AmbariException {
    if (actionRequest.getActionName() == null || actionRequest.getActionName().isEmpty()) {
      throw new AmbariException("Action name must be specified");
    }

    ActionDefinition actionDef = ambariMetaInfo.getActionDefinition(actionRequest.getActionName());
    if (actionDef == null) {
      throw new AmbariException("Action " + actionRequest.getActionName() + " does not exist");
    }

    if (actionDef.getInputs() != null) {
      String[] inputs = actionDef.getInputs().split(",");
      for (String input : inputs) {
        String inputName = input.trim();
        if (!inputName.isEmpty()) {
          boolean mandatory = true;
          if (inputName.startsWith("[") && inputName.endsWith("]")) {
            mandatory = false;
          }
          if (mandatory && !actionRequest.getParameters().containsKey(inputName)) {
            throw new AmbariException("Action " + actionRequest.getActionName() + " requires input '" +
              input.trim() + "' that is not provided.");
          }
        }
      }
    }

    List<RequestResourceFilter> resourceFilters = actionRequest.getResourceFilters();
    RequestResourceFilter resourceFilter = null;
    if (resourceFilters != null && !resourceFilters.isEmpty()) {
      if (resourceFilters.size() > 1) {
        throw new AmbariException("Custom action definition only allows one " +
          "resource filter to be specified.");
      } else {
        resourceFilter = resourceFilters.get(0);
      }
    }

    String targetService = "";
    String targetComponent = "";

    if (null != actionRequest.getClusterName()) {
      Cluster cluster = clusters.getCluster(actionRequest.getClusterName());

      if (cluster == null) {
        throw new AmbariException("Unable to find cluster. clusterName = " +
          actionRequest.getClusterName());
      }

      StackId stackId = cluster.getCurrentStackVersion();

      String expectedService = actionDef.getTargetService() == null ? "" : actionDef.getTargetService();

      String actualService = resourceFilter == null || resourceFilter.getServiceName() == null ? "" : resourceFilter.getServiceName();
      if (!expectedService.isEmpty() && !actualService.isEmpty() && !expectedService.equals(actualService)) {
        throw new AmbariException("Action " + actionRequest.getActionName() + " targets service " + actualService +
          " that does not match with expected " + expectedService);
      }

      targetService = expectedService;
      if (targetService == null || targetService.isEmpty()) {
        targetService = actualService;
      }

      if (targetService != null && !targetService.isEmpty()) {
        ServiceInfo serviceInfo;
        try {
          serviceInfo = ambariMetaInfo.getService(stackId.getStackName(), stackId.getStackVersion(),
            targetService);
        } catch (StackAccessException se) {
          serviceInfo = null;
        }

        if (serviceInfo == null) {
          throw new AmbariException("Action " + actionRequest.getActionName() +
            " targets service " + targetService + " that does not exist.");
        }
      }

      String expectedComponent = actionDef.getTargetComponent() == null ? "" : actionDef.getTargetComponent();
      String actualComponent = resourceFilter == null || resourceFilter.getComponentName() == null ? "" : resourceFilter.getComponentName();
      if (!expectedComponent.isEmpty() && !actualComponent.isEmpty() && !expectedComponent.equals(actualComponent)) {
        throw new AmbariException("Action " + actionRequest.getActionName() + " targets component " + actualComponent +
          " that does not match with expected " + expectedComponent);
      }

      targetComponent = expectedComponent;
      if (targetComponent == null || targetComponent.isEmpty()) {
        targetComponent = actualComponent;
      }

      if (!targetComponent.isEmpty() && targetService.isEmpty()) {
        throw new AmbariException("Action " + actionRequest.getActionName() + " targets component " + targetComponent +
          " without specifying the target service.");
      }

      if (targetComponent != null && !targetComponent.isEmpty()) {
        ComponentInfo compInfo;
        try {
          compInfo = ambariMetaInfo.getComponent(stackId.getStackName(), stackId.getStackVersion(),
            targetService, targetComponent);
        } catch (StackAccessException se) {
          compInfo = null;
        }

        if (compInfo == null) {
          throw new AmbariException("Action " + actionRequest.getActionName() + " targets component " + targetComponent +
            " that does not exist.");
        }
      }
    }

    if (TargetHostType.SPECIFIC.equals(actionDef.getTargetType())
      || (targetService.isEmpty() && targetComponent.isEmpty())) {
      if (resourceFilter == null || resourceFilter.getHostNames().size() == 0) {
        throw new AmbariException("Action " + actionRequest.getActionName() + " requires explicit target host(s)" +
          " that is not provided.");
      }
    }
  }


  /**
   * Add tasks to the stage based on the requested action execution
   *
   * @param actionContext  the context associated with the action
   * @param stage          stage into which tasks must be inserted
   * @param retryAllowed   indicates whether retry is allowed on failure
   *
   * @throws AmbariException if the task can not be added
   */
  public void addExecutionCommandsToStage(final ActionExecutionContext actionContext, Stage stage)
      throws AmbariException {

    String actionName = actionContext.getActionName();
    String clusterName = actionContext.getClusterName();
    final Cluster cluster;
    if (null != clusterName) {
      cluster = clusters.getCluster(clusterName);
    } else {
      cluster = null;
    }

    ComponentInfo componentInfo = null;
    List<RequestResourceFilter> resourceFilters = actionContext.getResourceFilters();
    final RequestResourceFilter resourceFilter;
    if (resourceFilters != null && !resourceFilters.isEmpty()) {
      resourceFilter = resourceFilters.get(0);
    } else {
      resourceFilter = new RequestResourceFilter();
    }

    // List of host to select from
    Set<String> candidateHosts = new HashSet<String>();

    final String serviceName = actionContext.getExpectedServiceName();
    final String componentName = actionContext.getExpectedComponentName();

    if (null != cluster) {
      StackId stackId = cluster.getCurrentStackVersion();
      if (serviceName != null && !serviceName.isEmpty()) {
        if (componentName != null && !componentName.isEmpty()) {
          Map<String, ServiceComponentHost> componentHosts =
            cluster.getService(serviceName)
              .getServiceComponent(componentName).getServiceComponentHosts();
          candidateHosts.addAll(componentHosts.keySet());
          try {
            componentInfo = ambariMetaInfo.getComponent(stackId.getStackName(),
                stackId.getStackVersion(), serviceName, componentName);
          } catch (ObjectNotFoundException e) {
            // do nothing, componentId is checked for null later
          }
        } else {
          for (String component : cluster.getService(serviceName).getServiceComponents().keySet()) {
            Map<String, ServiceComponentHost> componentHosts =
              cluster.getService(serviceName)
                .getServiceComponent(component).getServiceComponentHosts();
            candidateHosts.addAll(componentHosts.keySet());
          }
        }
      } else {
        // All hosts are valid target host
        candidateHosts.addAll(clusters.getHostsForCluster(cluster.getClusterName()).keySet());
      }

      // Filter hosts that are in MS
      Set<String> ignoredHosts = maintenanceStateHelper.filterHostsInMaintenanceState(
              candidateHosts, new MaintenanceStateHelper.HostPredicate() {
                @Override
                public boolean shouldHostBeRemoved(final String hostname)
                        throws AmbariException {
                  return ! maintenanceStateHelper.isOperationAllowed(
                          cluster, actionContext.getOperationLevel(),
                          resourceFilter, serviceName, componentName, hostname);
                }
              }
      );
      if (! ignoredHosts.isEmpty()) {
        LOG.debug("Ignoring action for hosts due to maintenance state." +
            "Ignored hosts =" + ignoredHosts + ", component="
            + componentName + ", service=" + serviceName
            + ", cluster=" + cluster.getClusterName() + ", " +
            "actionName=" + actionContext.getActionName());
      }
    }

    // If request did not specify hosts and there exists no host
    if (resourceFilter.getHostNames().isEmpty() && candidateHosts.isEmpty()) {
      throw new AmbariException("Suitable hosts not found, component="
              + componentName + ", service=" + serviceName
              + ((null == cluster) ? "" : ", cluster=" + cluster.getClusterName() + ", ")
              + "actionName=" + actionContext.getActionName());
    }

    // Compare specified hosts to available hosts
    if (!resourceFilter.getHostNames().isEmpty() && !candidateHosts.isEmpty()) {
      for (String hostname : resourceFilter.getHostNames()) {
        if (!candidateHosts.contains(hostname)) {
          throw new AmbariException("Request specifies host " + hostname +
            " but its not a valid host based on the " +
            "target service=" + serviceName + " and component=" + componentName);
        }
      }
    }

    List<String> targetHosts = resourceFilter.getHostNames();

    //Find target hosts to execute
    if (targetHosts.isEmpty()) {
      TargetHostType hostType = actionContext.getTargetType();
      switch (hostType) {
        case ALL:
          targetHosts.addAll(candidateHosts);
          break;
        case ANY:
          targetHosts.add(managementController.getHealthyHost(candidateHosts));
          break;
        case MAJORITY:
          for (int i = 0; i < (candidateHosts.size() / 2) + 1; i++) {
            String hostname = managementController.getHealthyHost(candidateHosts);
            targetHosts.add(hostname);
            candidateHosts.remove(hostname);
          }
          break;
        default:
          throw new AmbariException("Unsupported target type = " + hostType);
      }
    }

    // create tasks for each host
    for (String hostName : targetHosts) {
      stage.addHostRoleExecutionCommand(hostName, Role.valueOf(actionContext.getActionName()),
          RoleCommand.ACTIONEXECUTE,
          new ServiceComponentHostOpInProgressEvent(actionContext.getActionName(), hostName,
              System.currentTimeMillis()),
          clusterName, serviceName, actionContext.isRetryAllowed(),
          actionContext.isFailureAutoSkipped());

      Map<String, String> commandParams = new TreeMap<String, String>();

      int taskTimeout = Integer.parseInt(configs.getDefaultAgentTaskTimeout(false));

      // use the biggest of all these:
      // if the action context timeout is bigger than the default, use the context
      // if the action context timeout is smaller than the default, use the default
      // if the action context timeout is undefined, use the default
      if (null != actionContext.getTimeout() && actionContext.getTimeout() > taskTimeout) {
        commandParams.put(COMMAND_TIMEOUT, actionContext.getTimeout().toString());
      } else {
        commandParams.put(COMMAND_TIMEOUT, Integer.toString(taskTimeout));
      }

      commandParams.put(SCRIPT, actionName + ".py");
      commandParams.put(SCRIPT_TYPE, TYPE_PYTHON);

      ExecutionCommand execCmd = stage.getExecutionCommandWrapper(hostName,
        actionContext.getActionName()).getExecutionCommand();

      // !!! ensure that these are empty so that commands have the correct tags
      // applied when the execution is about to be scheduled to run
      execCmd.setConfigurations(new TreeMap<String, Map<String, String>>());
      execCmd.setConfigurationAttributes(new TreeMap<String, Map<String, Map<String, String>>>());

      // !!! ensure that the config tags are added to this command so that the
      // configurations can be populated from the tags before the command is
      // sent
      Map<String, Map<String, String>> configTags = managementController.findConfigurationTagsWithOverrides(cluster, hostName);
      execCmd.setConfigurationTags(configTags);

      execCmd.setCommandParams(commandParams);

      execCmd.setServiceName(serviceName == null || serviceName.isEmpty() ?
        resourceFilter.getServiceName() : serviceName);

      execCmd.setComponentName(componentName == null || componentName.isEmpty() ?
        resourceFilter.getComponentName() : componentName);

      Map<String, String> roleParams = execCmd.getRoleParams();
      if (roleParams == null) {
        roleParams = new TreeMap<String, String>();
      }

      roleParams.putAll(actionContext.getParameters());
      if (componentInfo != null) {
        roleParams.put(COMPONENT_CATEGORY, componentInfo.getCategory());
      }

      execCmd.setRoleParams(roleParams);

      // ensure that any tags that need to be refreshed are extracted from the
      // context and put onto the execution command
      Map<String, String> actionParameters = actionContext.getParameters();
      if (null != actionParameters && !actionParameters.isEmpty()) {
        if (actionParameters.containsKey(KeyNames.REFRESH_CONFIG_TAGS_BEFORE_EXECUTION)) {
          String[] split = StringUtils.split(
              actionParameters.get(KeyNames.REFRESH_CONFIG_TAGS_BEFORE_EXECUTION));
          Set<String> configsToRefresh = new HashSet<String>(Arrays.asList(split));

          execCmd.setForceRefreshConfigTagsBeforeExecution(configsToRefresh);
        }
      }

      // Generate cluster host info
      if (null != cluster) {
        execCmd.setClusterHostInfo(
          StageUtils.getClusterHostInfo(cluster));
      }
    }
  }
}
