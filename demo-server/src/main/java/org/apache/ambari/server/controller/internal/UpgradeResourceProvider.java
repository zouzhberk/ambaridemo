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
package org.apache.ambari.server.controller.internal;

import static org.apache.ambari.server.agent.ExecutionCommand.KeyNames.HOOKS_FOLDER;
import static org.apache.ambari.server.agent.ExecutionCommand.KeyNames.SERVICE_PACKAGE_FOLDER;
import static org.apache.ambari.server.agent.ExecutionCommand.KeyNames.VERSION;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.Role;
import org.apache.ambari.server.RoleCommand;
import org.apache.ambari.server.StaticallyInject;
import org.apache.ambari.server.actionmanager.ActionManager;
import org.apache.ambari.server.actionmanager.HostRoleCommand;
import org.apache.ambari.server.actionmanager.HostRoleStatus;
import org.apache.ambari.server.actionmanager.RequestFactory;
import org.apache.ambari.server.actionmanager.Stage;
import org.apache.ambari.server.actionmanager.StageFactory;
import org.apache.ambari.server.agent.ExecutionCommand.KeyNames;
import org.apache.ambari.server.api.resources.UpgradeResourceDefinition;
import org.apache.ambari.server.api.services.AmbariMetaInfo;
import org.apache.ambari.server.configuration.Configuration;
import org.apache.ambari.server.controller.ActionExecutionContext;
import org.apache.ambari.server.controller.AmbariActionExecutionHelper;
import org.apache.ambari.server.controller.AmbariCustomCommandExecutionHelper;
import org.apache.ambari.server.controller.AmbariManagementController;
import org.apache.ambari.server.controller.ExecuteCommandJson;
import org.apache.ambari.server.controller.spi.NoSuchParentResourceException;
import org.apache.ambari.server.controller.spi.NoSuchResourceException;
import org.apache.ambari.server.controller.spi.Predicate;
import org.apache.ambari.server.controller.spi.Request;
import org.apache.ambari.server.controller.spi.RequestStatus;
import org.apache.ambari.server.controller.spi.Resource;
import org.apache.ambari.server.controller.spi.ResourceAlreadyExistsException;
import org.apache.ambari.server.controller.spi.SystemException;
import org.apache.ambari.server.controller.spi.UnsupportedPropertyException;
import org.apache.ambari.server.controller.utilities.PredicateBuilder;
import org.apache.ambari.server.controller.utilities.PropertyHelper;
import org.apache.ambari.server.orm.dao.HostRoleCommandDAO;
import org.apache.ambari.server.orm.dao.HostRoleCommandStatusSummaryDTO;
import org.apache.ambari.server.orm.dao.RepositoryVersionDAO;
import org.apache.ambari.server.orm.dao.RequestDAO;
import org.apache.ambari.server.orm.dao.UpgradeDAO;
import org.apache.ambari.server.orm.entities.RepositoryVersionEntity;
import org.apache.ambari.server.orm.entities.RequestEntity;
import org.apache.ambari.server.orm.entities.StackEntity;
import org.apache.ambari.server.orm.entities.UpgradeEntity;
import org.apache.ambari.server.orm.entities.UpgradeGroupEntity;
import org.apache.ambari.server.orm.entities.UpgradeItemEntity;
import org.apache.ambari.server.stack.MasterHostResolver;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Config;
import org.apache.ambari.server.state.ConfigHelper;
import org.apache.ambari.server.state.DesiredConfig;
import org.apache.ambari.server.state.ServiceInfo;
import org.apache.ambari.server.state.StackId;
import org.apache.ambari.server.state.StackInfo;
import org.apache.ambari.server.state.UpgradeContext;
import org.apache.ambari.server.state.UpgradeHelper;
import org.apache.ambari.server.state.UpgradeHelper.UpgradeGroupHolder;
import org.apache.ambari.server.state.stack.PrereqCheckStatus;
import org.apache.ambari.server.state.stack.UpgradePack;
import org.apache.ambari.server.state.stack.upgrade.ConfigureTask;
import org.apache.ambari.server.state.stack.upgrade.Direction;
import org.apache.ambari.server.state.stack.upgrade.Grouping;
import org.apache.ambari.server.state.stack.upgrade.ManualTask;
import org.apache.ambari.server.state.stack.upgrade.ServerSideActionTask;
import org.apache.ambari.server.state.stack.upgrade.StageWrapper;
import org.apache.ambari.server.state.stack.upgrade.Task;
import org.apache.ambari.server.state.stack.upgrade.TaskWrapper;
import org.apache.ambari.server.state.svccomphost.ServiceComponentHostServerActionEvent;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.inject.Inject;
import com.google.inject.Provider;

/**
 * Manages the ability to start and get status of upgrades.
 */
@StaticallyInject
public class UpgradeResourceProvider extends AbstractControllerResourceProvider {

  protected static final String UPGRADE_CLUSTER_NAME = "Upgrade/cluster_name";
  protected static final String UPGRADE_VERSION = "Upgrade/repository_version";
  protected static final String UPGRADE_REQUEST_ID = "Upgrade/request_id";
  protected static final String UPGRADE_FROM_VERSION = "Upgrade/from_version";
  protected static final String UPGRADE_TO_VERSION = "Upgrade/to_version";
  protected static final String UPGRADE_DIRECTION = "Upgrade/direction";
  protected static final String UPGRADE_REQUEST_STATUS = "Upgrade/request_status";
  protected static final String UPGRADE_ABORT_REASON = "Upgrade/abort_reason";
  protected static final String UPGRADE_SKIP_PREREQUISITE_CHECKS = "Upgrade/skip_prerequisite_checks";
  protected static final String UPGRADE_FAIL_ON_CHECK_WARNINGS = "Upgrade/fail_on_check_warnings";

  /**
   * Skip slave/client component failures if the tasks are skippable.
   */
  protected static final String UPGRADE_SKIP_FAILURES = "Upgrade/skip_failures";

  /**
   * Skip service check failures if the tasks are skippable.
   */
  protected static final String UPGRADE_SKIP_SC_FAILURES = "Upgrade/skip_service_check_failures";

  /*
   * Lifted from RequestResourceProvider
   */
  private static final String REQUEST_CONTEXT_ID = "Upgrade/request_context";
  private static final String REQUEST_TYPE_ID = "Upgrade/type";
  private static final String REQUEST_CREATE_TIME_ID = "Upgrade/create_time";
  private static final String REQUEST_START_TIME_ID = "Upgrade/start_time";
  private static final String REQUEST_END_TIME_ID = "Upgrade/end_time";
  private static final String REQUEST_EXCLUSIVE_ID = "Upgrade/exclusive";

  private static final String REQUEST_PROGRESS_PERCENT_ID = "Upgrade/progress_percent";
  private static final String REQUEST_STATUS_PROPERTY_ID = "Upgrade/request_status";

  private static final Set<String> PK_PROPERTY_IDS = new HashSet<String>(
      Arrays.asList(UPGRADE_REQUEST_ID, UPGRADE_CLUSTER_NAME));
  private static final Set<String> PROPERTY_IDS = new HashSet<String>();

  private static final String COMMAND_PARAM_VERSION = VERSION;
  private static final String COMMAND_PARAM_CLUSTER_NAME = "clusterName";
  private static final String COMMAND_PARAM_DIRECTION = "upgrade_direction";
  private static final String COMMAND_PARAM_RESTART_TYPE = "restart_type";
  private static final String COMMAND_PARAM_TASKS = "tasks";
  private static final String COMMAND_PARAM_STRUCT_OUT = "structured_out";
  private static final String COMMAND_DOWNGRADE_FROM_VERSION = "downgrade_from_version";

  /**
   * The original "current" stack of the cluster before the upgrade started.
   * This is the same regardless of whether the current direction is
   * {@link Direction#UPGRADE} or {@link Direction#DOWNGRADE}.
   */
  private static final String COMMAND_PARAM_ORIGINAL_STACK = "original_stack";

  /**
   * The target upgrade stack before the upgrade started. This is the same
   * regardless of whether the current direction is {@link Direction#UPGRADE} or
   * {@link Direction#DOWNGRADE}.
   */
  private static final String COMMAND_PARAM_TARGET_STACK = "target_stack";

  private static final String DEFAULT_REASON_TEMPLATE = "Aborting upgrade %s";

  private static final Map<Resource.Type, String> KEY_PROPERTY_IDS = new HashMap<Resource.Type, String>();

  @Inject
  private static UpgradeDAO s_upgradeDAO = null;

  @Inject
  private static Provider<AmbariMetaInfo> s_metaProvider = null;

  @Inject
  private static RepositoryVersionDAO s_repoVersionDAO = null;

  @Inject
  private static Provider<RequestFactory> s_requestFactory;

  @Inject
  private static Provider<StageFactory> s_stageFactory;

  @Inject
  private static Provider<AmbariActionExecutionHelper> s_actionExecutionHelper;

  @Inject
  private static Provider<AmbariCustomCommandExecutionHelper> s_commandExecutionHelper;

  @Inject
  private static RequestDAO s_requestDAO = null;

  @Inject
  private static HostRoleCommandDAO s_hostRoleCommandDAO = null;

  /**
   * Used to generated the correct tasks and stages during an upgrade.
   */
  @Inject
  private static UpgradeHelper s_upgradeHelper;

  @Inject
  private static Configuration s_configuration;

  static {
    // properties
    PROPERTY_IDS.add(UPGRADE_CLUSTER_NAME);
    PROPERTY_IDS.add(UPGRADE_VERSION);
    PROPERTY_IDS.add(UPGRADE_REQUEST_ID);
    PROPERTY_IDS.add(UPGRADE_FROM_VERSION);
    PROPERTY_IDS.add(UPGRADE_TO_VERSION);
    PROPERTY_IDS.add(UPGRADE_DIRECTION);
    PROPERTY_IDS.add(UPGRADE_SKIP_FAILURES);
    PROPERTY_IDS.add(UPGRADE_SKIP_SC_FAILURES);
    PROPERTY_IDS.add(UPGRADE_SKIP_PREREQUISITE_CHECKS);
    PROPERTY_IDS.add(UPGRADE_FAIL_ON_CHECK_WARNINGS);

    PROPERTY_IDS.add(REQUEST_CONTEXT_ID);
    PROPERTY_IDS.add(REQUEST_CREATE_TIME_ID);
    PROPERTY_IDS.add(REQUEST_END_TIME_ID);
    PROPERTY_IDS.add(REQUEST_EXCLUSIVE_ID);
    PROPERTY_IDS.add(REQUEST_PROGRESS_PERCENT_ID);
    PROPERTY_IDS.add(REQUEST_START_TIME_ID);
    PROPERTY_IDS.add(REQUEST_STATUS_PROPERTY_ID);
    PROPERTY_IDS.add(REQUEST_TYPE_ID);

    // keys
    KEY_PROPERTY_IDS.put(Resource.Type.Upgrade, UPGRADE_REQUEST_ID);
    KEY_PROPERTY_IDS.put(Resource.Type.Cluster, UPGRADE_CLUSTER_NAME);
  }

  private static final Logger LOG = LoggerFactory.getLogger(UpgradeResourceProvider.class);

  /**
   * Constructor.
   *
   * @param controller
   *          the controller
   */
  UpgradeResourceProvider(AmbariManagementController controller) {
    super(PROPERTY_IDS, KEY_PROPERTY_IDS, controller);
  }

  @Override
  public RequestStatus createResources(final Request request) throws SystemException,
      UnsupportedPropertyException, ResourceAlreadyExistsException, NoSuchParentResourceException {

    Set<Map<String, Object>> requestMaps = request.getProperties();

    if (requestMaps.size() > 1) {
      throw new SystemException("Can only initiate one upgrade per request.");
    }

    // !!! above check ensures only one
    final Map<String, Object> requestMap = requestMaps.iterator().next();
    final Map<String, String> requestInfoProps = request.getRequestInfoProperties();

    UpgradeEntity entity = createResources(new Command<UpgradeEntity>() {
      @Override
      public UpgradeEntity invoke() throws AmbariException {
        String forceDowngrade = requestInfoProps.get(UpgradeResourceDefinition.DOWNGRADE_DIRECTIVE);

        Direction direction = Boolean.parseBoolean(forceDowngrade) ? Direction.DOWNGRADE
            : Direction.UPGRADE;

        UpgradePack up = validateRequest(direction, requestMap);

        return createUpgrade(direction, up, requestMap);
      }
    });

    if (null == entity) {
      throw new SystemException("Could not load upgrade");
    }

    notifyCreate(Resource.Type.Upgrade, request);

    Resource res = new ResourceImpl(Resource.Type.Upgrade);
    res.setProperty(UPGRADE_REQUEST_ID, entity.getRequestId());
    return new RequestStatusImpl(null, Collections.singleton(res));
  }

  @Override
  public Set<Resource> getResources(Request request, Predicate predicate) throws SystemException,
      UnsupportedPropertyException, NoSuchResourceException, NoSuchParentResourceException {

    Set<Resource> results = new HashSet<Resource>();
    Set<String> requestPropertyIds = getRequestPropertyIds(request, predicate);

    for (Map<String, Object> propertyMap : getPropertyMaps(predicate)) {
      String clusterName = (String) propertyMap.get(UPGRADE_CLUSTER_NAME);

      if (null == clusterName || clusterName.isEmpty()) {
        throw new IllegalArgumentException(
            "The cluster name is required when querying for upgrades");
      }

      Cluster cluster;
      try {
        cluster = getManagementController().getClusters().getCluster(clusterName);
      } catch (AmbariException e) {
        throw new NoSuchResourceException(
            String.format("Cluster %s could not be loaded", clusterName));
      }

      List<UpgradeEntity> upgrades = new ArrayList<UpgradeEntity>();

      String upgradeIdStr = (String) propertyMap.get(UPGRADE_REQUEST_ID);
      if (null != upgradeIdStr) {
        UpgradeEntity upgrade = s_upgradeDAO.findUpgradeByRequestId(Long.valueOf(upgradeIdStr));

        if (null != upgrade) {
          upgrades.add(upgrade);
        }
      } else {
        upgrades = s_upgradeDAO.findUpgrades(cluster.getClusterId());
      }

      for (UpgradeEntity entity : upgrades) {
        Resource r = toResource(entity, clusterName, requestPropertyIds);
        results.add(r);

        RequestEntity rentity = s_requestDAO.findByPK(entity.getRequestId());

        setResourceProperty(r, REQUEST_CONTEXT_ID, rentity.getRequestContext(), requestPropertyIds);
        setResourceProperty(r, REQUEST_TYPE_ID, rentity.getRequestType(), requestPropertyIds);
        setResourceProperty(r, REQUEST_CREATE_TIME_ID, rentity.getCreateTime(), requestPropertyIds);
        setResourceProperty(r, REQUEST_START_TIME_ID, rentity.getStartTime(), requestPropertyIds);
        setResourceProperty(r, REQUEST_END_TIME_ID, rentity.getEndTime(), requestPropertyIds);
        setResourceProperty(r, REQUEST_EXCLUSIVE_ID, rentity.isExclusive(), requestPropertyIds);

        Map<Long, HostRoleCommandStatusSummaryDTO> summary = s_hostRoleCommandDAO.findAggregateCounts(
            entity.getRequestId());

        CalculatedStatus calc = CalculatedStatus.statusFromStageSummary(summary, summary.keySet());

        setResourceProperty(r, REQUEST_STATUS_PROPERTY_ID, calc.getStatus(), requestPropertyIds);
        setResourceProperty(r, REQUEST_PROGRESS_PERCENT_ID, calc.getPercent(), requestPropertyIds);
      }
    }

    return results;
  }

  @Override
  public RequestStatus updateResources(final Request request, Predicate predicate)
      throws SystemException, UnsupportedPropertyException, NoSuchResourceException,
      NoSuchParentResourceException {

    Set<Map<String, Object>> requestMaps = request.getProperties();

    if (requestMaps.size() > 1) {
      throw new SystemException("Can only update one upgrade per request.");
    }

    // !!! above check ensures only one
    final Map<String, Object> propertyMap = requestMaps.iterator().next();

    String requestId = (String) propertyMap.get(UPGRADE_REQUEST_ID);
    if (null == requestId) {
      throw new IllegalArgumentException(String.format("%s is required", UPGRADE_REQUEST_ID));
    }

    String requestStatus = (String) propertyMap.get(UPGRADE_REQUEST_STATUS);
    if (null == requestStatus) {
      throw new IllegalArgumentException(String.format("%s is required", UPGRADE_REQUEST_STATUS));
    }

    HostRoleStatus status = HostRoleStatus.valueOf(requestStatus);
    if (status != HostRoleStatus.ABORTED && status != HostRoleStatus.PENDING) {
      throw new IllegalArgumentException(String.format("Cannot set status %s, only %s is allowed",
          status, EnumSet.of(HostRoleStatus.ABORTED, HostRoleStatus.PENDING)));
    }

    String reason = (String) propertyMap.get(UPGRADE_ABORT_REASON);
    if (null == reason) {
      reason = String.format(DEFAULT_REASON_TEMPLATE, requestId);
    }

    ActionManager actionManager = getManagementController().getActionManager();
    List<org.apache.ambari.server.actionmanager.Request> requests = actionManager.getRequests(
        Collections.singletonList(Long.valueOf(requestId)));

    org.apache.ambari.server.actionmanager.Request internalRequest = requests.get(0);

    HostRoleStatus internalStatus = CalculatedStatus.statusFromStages(
        internalRequest.getStages()).getStatus();

    if (HostRoleStatus.PENDING == status && internalStatus != HostRoleStatus.ABORTED) {
      throw new IllegalArgumentException(
          String.format("Can only set status to %s when the upgrade is %s (currently %s)", status,
              HostRoleStatus.ABORTED, internalStatus));
    }

    if (HostRoleStatus.ABORTED == status) {
      if (!internalStatus.isCompletedState()) {
        actionManager.cancelRequest(internalRequest.getRequestId(), reason);
      }
    } else {
      List<Long> taskIds = new ArrayList<Long>();

      for (HostRoleCommand hrc : internalRequest.getCommands()) {
        if (HostRoleStatus.ABORTED == hrc.getStatus()
            || HostRoleStatus.TIMEDOUT == hrc.getStatus()) {
          taskIds.add(hrc.getTaskId());
        }
      }

      actionManager.resubmitTasks(taskIds);
    }

    return getRequestStatus(null);
  }

  @Override
  public RequestStatus deleteResources(Predicate predicate) throws SystemException,
      UnsupportedPropertyException, NoSuchResourceException, NoSuchParentResourceException {
    throw new SystemException("Cannot delete Upgrades");
  }

  @Override
  protected Set<String> getPKPropertyIds() {
    return PK_PROPERTY_IDS;
  }

  private Resource toResource(UpgradeEntity entity, String clusterName, Set<String> requestedIds) {
    ResourceImpl resource = new ResourceImpl(Resource.Type.Upgrade);

    setResourceProperty(resource, UPGRADE_CLUSTER_NAME, clusterName, requestedIds);
    setResourceProperty(resource, UPGRADE_REQUEST_ID, entity.getRequestId(), requestedIds);
    setResourceProperty(resource, UPGRADE_FROM_VERSION, entity.getFromVersion(), requestedIds);
    setResourceProperty(resource, UPGRADE_TO_VERSION, entity.getToVersion(), requestedIds);
    setResourceProperty(resource, UPGRADE_DIRECTION, entity.getDirection(), requestedIds);

    return resource;
  }

  /**
   * Validates a singular API request.
   *
   * @param requestMap
   *          the map of properties
   * @return the validated upgrade pack
   * @throws AmbariException
   */
  private UpgradePack validateRequest(Direction direction, Map<String, Object> requestMap)
      throws AmbariException {
    String clusterName = (String) requestMap.get(UPGRADE_CLUSTER_NAME);
    String version = (String) requestMap.get(UPGRADE_VERSION);
    String versionForUpgradePack = (String) requestMap.get(UPGRADE_FROM_VERSION);
    boolean skipPrereqChecks = Boolean.parseBoolean((String) requestMap.get(UPGRADE_SKIP_PREREQUISITE_CHECKS));
    boolean failOnCheckWarnings = Boolean.parseBoolean((String) requestMap.get(UPGRADE_FAIL_ON_CHECK_WARNINGS));

    if (null == clusterName) {
      throw new AmbariException(String.format("%s is required", UPGRADE_CLUSTER_NAME));
    }

    if (null == version) {
      throw new AmbariException(String.format("%s is required", UPGRADE_VERSION));
    }

    Cluster cluster = getManagementController().getClusters().getCluster(clusterName);

    // !!! find upgrade packs based on current stack. This is where to upgrade
    // from.
    StackId stack = cluster.getCurrentStackVersion();

    String repoVersion = version;

    if (direction.isDowngrade() && null != versionForUpgradePack) {
      repoVersion = versionForUpgradePack;
    }

    RepositoryVersionEntity versionEntity = s_repoVersionDAO.findByStackNameAndVersion(stack.getStackName(), repoVersion);

    if (null == versionEntity) {
      throw new AmbariException(String.format("Repository version %s was not found", repoVersion));
    }

    Map<String, UpgradePack> packs = s_metaProvider.get().getUpgradePacks(stack.getStackName(),
        stack.getStackVersion());

    UpgradePack up = packs.get(versionEntity.getUpgradePackage());

    if (null == up) {
      // !!! in case there is an upgrade pack that doesn't match the name
      String repoStackId = versionEntity.getStackId().getStackId();
      for (UpgradePack upgradePack : packs.values()) {
        if (null != upgradePack.getTargetStack()
            && upgradePack.getTargetStack().equals(repoStackId)) {
          up = upgradePack;
          break;
        }
      }
    }

    if (null == up) {
      throw new AmbariException(
          String.format("Unable to perform %s.  Could not locate upgrade pack %s for version %s",
              direction.getText(false), versionEntity.getUpgradePackage(), repoVersion));
    }

    // Validate there isn't an direction == upgrade/downgrade already in progress.
    List<UpgradeEntity> upgrades = s_upgradeDAO.findUpgrades(cluster.getClusterId());
    for (UpgradeEntity entity : upgrades) {
      if(entity.getDirection() == direction) {
        Map<Long, HostRoleCommandStatusSummaryDTO> summary = s_hostRoleCommandDAO.findAggregateCounts(
            entity.getRequestId());
        CalculatedStatus calc = CalculatedStatus.statusFromStageSummary(summary, summary.keySet());
        HostRoleStatus status = calc.getStatus();
        if(!HostRoleStatus.getCompletedStates().contains(status)) {
          throw new AmbariException(
              String.format("Unable to perform %s as another %s is in progress. %s %d is in %s",
                  direction.getText(false), direction.getText(false), direction.getText(true),
                  entity.getRequestId().longValue(), status)
          );
        }
      }
    }

    if(direction.isUpgrade() && !skipPrereqChecks) {
      // Validate pre-req checks pass
      PreUpgradeCheckResourceProvider preUpgradeCheckResourceProvider = (PreUpgradeCheckResourceProvider)
          getResourceProvider(Resource.Type.PreUpgradeCheck);
      Predicate preUpgradeCheckPredicate = new PredicateBuilder().property(
          PreUpgradeCheckResourceProvider.UPGRADE_CHECK_CLUSTER_NAME_PROPERTY_ID).equals(clusterName).and().property(
          PreUpgradeCheckResourceProvider.UPGRADE_CHECK_REPOSITORY_VERSION_PROPERTY_ID).equals(repoVersion).toPredicate();
      Request preUpgradeCheckRequest = PropertyHelper.getReadRequest();

      Set<Resource> preUpgradeCheckResources;
      try {
        preUpgradeCheckResources = preUpgradeCheckResourceProvider.getResources(
            preUpgradeCheckRequest, preUpgradeCheckPredicate);
      } catch (NoSuchResourceException e) {
        throw new AmbariException(
            String.format("Unable to perform %s. Prerequisite checks could not be run",
                direction.getText(false)));
      }
      List<Resource> failedResources = new LinkedList<Resource>();
      if (preUpgradeCheckResources != null) {
        for(Resource res : preUpgradeCheckResources) {
          String id = (String) res.getPropertyValue((PreUpgradeCheckResourceProvider.UPGRADE_CHECK_ID_PROPERTY_ID));
          PrereqCheckStatus prereqCheckStatus = (PrereqCheckStatus) res.getPropertyValue(
              PreUpgradeCheckResourceProvider.UPGRADE_CHECK_STATUS_PROPERTY_ID);
          if(prereqCheckStatus == PrereqCheckStatus.FAIL
              || (failOnCheckWarnings && prereqCheckStatus == PrereqCheckStatus.WARNING)) {
            failedResources.add(res);
          }
        }
      }
      if(!failedResources.isEmpty()) {
        Gson gson = new Gson();
        throw new AmbariException(
            String.format("Unable to perform %s. Prerequisite checks failed %s",
                direction.getText(false), gson.toJson(failedResources)));
      }
    }

    return up;
  }

  /**
   * Inject variables into the
   * {@link org.apache.ambari.server.orm.entities.UpgradeItemEntity}, whose
   * tasks may use strings like {{configType/propertyName}} that need to be
   * retrieved from the properties.
   *
   * @param configHelper
   *          Configuration Helper
   * @param cluster
   *          Cluster
   * @param upgradeItem
   *          the item whose tasks will be injected.
   */
  private void injectVariables(ConfigHelper configHelper, Cluster cluster,
      UpgradeItemEntity upgradeItem) {
    final String regexp = "(\\{\\{.*?\\}\\})";

    String task = upgradeItem.getTasks();
    if (task != null && !task.isEmpty()) {
      Matcher m = Pattern.compile(regexp).matcher(task);
      while (m.find()) {
        String origVar = m.group(1);
        String configValue = configHelper.getPlaceholderValueFromDesiredConfigurations(cluster,
            origVar);

        if (null != configValue) {
          task = task.replace(origVar, configValue);
        } else {
          LOG.error("Unable to retrieve value for {}", origVar);
        }

      }
      upgradeItem.setTasks(task);
    }
  }

  private UpgradeEntity createUpgrade(Direction direction, UpgradePack pack,
      Map<String, Object> requestMap) throws AmbariException {

    String clusterName = (String) requestMap.get(UPGRADE_CLUSTER_NAME);

    if (null == clusterName) {
      throw new AmbariException(String.format("%s is required", UPGRADE_CLUSTER_NAME));
    }

    Cluster cluster = getManagementController().getClusters().getCluster(clusterName);
    ConfigHelper configHelper = getManagementController().getConfigHelper();

    // the version being upgraded or downgraded to (ie hdp-2.2.1.0-1234)
    final String version = (String) requestMap.get(UPGRADE_VERSION);

    MasterHostResolver resolver = direction.isUpgrade()
        ? new MasterHostResolver(configHelper, cluster)
        : new MasterHostResolver(configHelper, cluster, version);

    StackId sourceStackId = null;
    StackId targetStackId = null;

    switch (direction) {
      case UPGRADE:
        sourceStackId = cluster.getCurrentStackVersion();

        RepositoryVersionEntity targetRepositoryVersion = s_repoVersionDAO.findByStackNameAndVersion(
            sourceStackId.getStackName(), version);
        targetStackId = targetRepositoryVersion.getStackId();
        break;
      case DOWNGRADE:
        sourceStackId = cluster.getCurrentStackVersion();
        targetStackId = cluster.getDesiredStackVersion();
        break;
    }

    UpgradeContext ctx = new UpgradeContext(resolver, sourceStackId, targetStackId, version,
        direction);

    if (direction.isDowngrade()) {
      if (requestMap.containsKey(UPGRADE_FROM_VERSION)) {
        ctx.setDowngradeFromVersion((String) requestMap.get(UPGRADE_FROM_VERSION));
      } else {
        UpgradeEntity lastUpgradeItemForCluster = s_upgradeDAO.findLastUpgradeForCluster(cluster.getClusterId());
        ctx.setDowngradeFromVersion(lastUpgradeItemForCluster.getToVersion());
      }
    }

    // optionally skip failures - this can be supplied on either the request or
    // in the upgrade pack explicitely, however the request will always override
    // the upgrade pack if explicitely specified
    boolean skipComponentFailures = pack.isComponentFailureAutoSkipped();
    boolean skipServiceCheckFailures = pack.isServiceCheckFailureAutoSkipped();

    // only override the upgrade pack if set on the request
    if (requestMap.containsKey(UPGRADE_SKIP_FAILURES)) {
      skipComponentFailures = Boolean.parseBoolean((String) requestMap.get(UPGRADE_SKIP_FAILURES));
    }

    // only override the upgrade pack if set on the request
    if (requestMap.containsKey(UPGRADE_SKIP_SC_FAILURES)) {
      skipServiceCheckFailures = Boolean.parseBoolean(
          (String) requestMap.get(UPGRADE_SKIP_SC_FAILURES));
    }

    ctx.setAutoSkipComponentFailures(skipComponentFailures);
    ctx.setAutoSkipServiceCheckFailures(skipServiceCheckFailures);

    List<UpgradeGroupHolder> groups = s_upgradeHelper.createSequence(pack, ctx);

    if (groups.isEmpty()) {
      throw new AmbariException("There are no groupings available");
    }

    List<UpgradeGroupEntity> groupEntities = new ArrayList<UpgradeGroupEntity>();
    RequestStageContainer req = createRequest(direction, version);

    // desired configs must be set before creating stages because the config tag
    // names are read and set on the command for filling in later
    processConfigurations(targetStackId.getStackName(), cluster, version, direction, pack);

    for (UpgradeGroupHolder group : groups) {
      UpgradeGroupEntity groupEntity = new UpgradeGroupEntity();
      groupEntity.setName(group.name);
      groupEntity.setTitle(group.title);
      boolean skippable = group.skippable;
      boolean allowRetry = group.allowRetry;

      List<UpgradeItemEntity> itemEntities = new ArrayList<UpgradeItemEntity>();

      for (StageWrapper wrapper : group.items) {
        if (wrapper.getType() == StageWrapper.Type.SERVER_SIDE_ACTION) {
          // !!! each stage is guaranteed to be of one type. but because there
          // is a bug that prevents one stage with multiple tasks assigned for
          // the same host, break them out into individual stages.
          for (TaskWrapper taskWrapper : wrapper.getTasks()) {
            for (Task task : taskWrapper.getTasks()) {
              UpgradeItemEntity itemEntity = new UpgradeItemEntity();
              itemEntity.setText(wrapper.getText());
              itemEntity.setTasks(wrapper.getTasksJson());
              itemEntity.setHosts(wrapper.getHostsJson());
              itemEntities.add(itemEntity);

              injectVariables(configHelper, cluster, itemEntity);

              makeServerSideStage(ctx, req, itemEntity, (ServerSideActionTask) task, skippable,
                  allowRetry);
            }
          }
        } else {
          UpgradeItemEntity itemEntity = new UpgradeItemEntity();
          itemEntity.setText(wrapper.getText());
          itemEntity.setTasks(wrapper.getTasksJson());
          itemEntity.setHosts(wrapper.getHostsJson());
          itemEntities.add(itemEntity);

          injectVariables(configHelper, cluster, itemEntity);

          // upgrade items match a stage
          createStage(ctx, req, itemEntity, wrapper, skippable, allowRetry);
        }
      }

      groupEntity.setItems(itemEntities);
      groupEntities.add(groupEntity);
    }

    UpgradeEntity entity = new UpgradeEntity();
    entity.setFromVersion(cluster.getCurrentClusterVersion().getRepositoryVersion().getVersion());
    entity.setToVersion(version);
    entity.setUpgradeGroups(groupEntities);
    entity.setClusterId(Long.valueOf(cluster.getClusterId()));
    entity.setDirection(direction);

    req.getRequestStatusResponse();

    entity.setRequestId(req.getId());

    req.persist();

    s_upgradeDAO.create(entity);

    return entity;
  }

  /**
   * Handles the creation or resetting of configurations based on whether an
   * upgrade or downgrade is occurring. This method will not do anything when
   * the target stack version is the same as the cluster's current stack version
   * since, by definition, no new configurations are automatically created when
   * upgrading with the same stack (ie HDP 2.2.0.0 -> HDP 2.2.1.0).
   * <p/>
   * When upgrading or downgrade between stacks (HDP 2.2.0.0 -> HDP 2.3.0.0)
   * then this will perform the following:
   * <ul>
   * <li>Upgrade: Create new configurations that are a merge between the current
   * stack and the desired stack. If a value has changed between stacks, then
   * the target stack value should be taken unless the cluster's value differs
   * from the old stack. This can occur if a property has been customized after
   * installation.</li>
   * <li>Downgrade: Reset the latest configurations from the cluster's original
   * stack. The new configurations that were created on upgrade must be left
   * intact until all components have been reverted, otherwise heartbeats will
   * fail due to missing configurations.</li>
   * </ul>
   *
   *
   * @param stackName Stack name such as HDP, HDPWIN, BIGTOP
   * @param cluster
   *          the cluster
   * @param version
   *          the version
   * @param direction
   *          upgrade or downgrade
   * @param upgradePack
   *          upgrade pack used for upgrade or downgrade. This is needed to determine
   *          which services are effected.
   * @throws AmbariException
   */
  void processConfigurations(String stackName, Cluster cluster, String version, Direction direction, UpgradePack upgradePack)
      throws AmbariException {
    RepositoryVersionEntity targetRve = s_repoVersionDAO.findByStackNameAndVersion(stackName, version);
    if (null == targetRve) {
      LOG.info("Could not find version entity for {}; not setting new configs", version);
      return;
    }

    // if the current and target stacks are the same (ie HDP 2.2.0.0 -> 2.2.1.0)
    // then we should never do anything with configs on either upgrade or
    // downgrade; however if we are going across stacks, we have to do the stack
    // checks differently depending on whether this is an upgrade or downgrade
    StackEntity targetStack = targetRve.getStack();
    StackId currentStackId = cluster.getCurrentStackVersion();
    StackId desiredStackId = cluster.getDesiredStackVersion();
    StackId targetStackId = new StackId(targetStack);
    switch (direction) {
      case UPGRADE:
        if (currentStackId.equals(targetStackId)) {
          return;
        }
        break;
      case DOWNGRADE:
        if (desiredStackId.equals(targetStackId)) {
          return;
        }
        break;
    }

    Map<String, Map<String, String>> newConfigurationsByType = null;
    ConfigHelper configHelper = getManagementController().getConfigHelper();

    if (direction == Direction.UPGRADE) {
      // populate a map of default configurations for the old stack (this is
      // used when determining if a property has been customized and should be
      // overriden with the new stack value)
      Map<String, Map<String, String>> oldStackDefaultConfigurationsByType = configHelper.getDefaultProperties(
          currentStackId, cluster);

      // populate a map with default configurations from the new stack
      newConfigurationsByType = configHelper.getDefaultProperties(targetStackId, cluster);

      // We want to skip updating config-types of services that are not in the upgrade pack.
      // Care should be taken as some config-types could be in services that are in and out
      // of the upgrade pack. We should never ignore config-types of services in upgrade pack.
      Set<String> skipConfigTypes = new HashSet<String>();
      Set<String> upgradePackServices = new HashSet<String>();
      Set<String> upgradePackConfigTypes = new HashSet<String>();
      AmbariMetaInfo ambariMetaInfo = s_metaProvider.get();
      Map<String, ServiceInfo> stackServicesMap = ambariMetaInfo.getServices(targetStack.getStackName(), targetStack.getStackVersion());
      for (Grouping group : upgradePack.getGroups(direction)) {
        for (UpgradePack.OrderService service : group.services) {
          if (service.serviceName == null || upgradePackServices.contains(service.serviceName)) {
            // No need to re-process service that has already been looked at
            continue;
          }
          upgradePackServices.add(service.serviceName);
          ServiceInfo serviceInfo = stackServicesMap.get(service.serviceName);
          if (serviceInfo == null) {
            continue;
          }
          Set<String> serviceConfigTypes = serviceInfo.getConfigTypeAttributes().keySet();
          for (String serviceConfigType : serviceConfigTypes) {
            if (!upgradePackConfigTypes.contains(serviceConfigType)) {
              upgradePackConfigTypes.add(serviceConfigType);
            }
          }
        }
      }
      Set<String> servicesNotInUpgradePack = new HashSet<String>(stackServicesMap.keySet());
      servicesNotInUpgradePack.removeAll(upgradePackServices);
      for (String serviceNotInUpgradePack : servicesNotInUpgradePack) {
        ServiceInfo serviceInfo = stackServicesMap.get(serviceNotInUpgradePack);
        Set<String> configTypesOfServiceNotInUpgradePack = serviceInfo.getConfigTypeAttributes().keySet();
        for (String configType : configTypesOfServiceNotInUpgradePack) {
          if (!upgradePackConfigTypes.contains(configType) && !skipConfigTypes.contains(configType)) {
            skipConfigTypes.add(configType);
          }
        }
      }
      // Remove unused config-types from 'newConfigurationsByType'
      Iterator<String> iterator = newConfigurationsByType.keySet().iterator();
      while (iterator.hasNext()) {
        String configType = iterator.next();
        if (skipConfigTypes.contains(configType)) {
          LOG.info("RU: Removing configs for config-type {}", configType);
          iterator.remove();
        }
      }

      // now that the map has been populated with the default configurations
      // from the stack/service, overlay the existing configurations on top
      Map<String, DesiredConfig> existingDesiredConfigurationsByType = cluster.getDesiredConfigs();
      for (Map.Entry<String, DesiredConfig> existingEntry : existingDesiredConfigurationsByType.entrySet()) {
        String configurationType = existingEntry.getKey();
        if(skipConfigTypes.contains(configurationType)) {
          LOG.info("RU: Skipping config-type {} as upgrade-pack contains no updates to its service", configurationType);
          continue;
        }

        // NPE sanity, althought shouldn't even happen since we are iterating
        // over the desired configs to start with
        Config currentClusterConfig = cluster.getDesiredConfigByType(configurationType);
        if (null == currentClusterConfig) {
          continue;
        }

        // get the existing configurations
        Map<String, String> existingConfigurations = currentClusterConfig.getProperties();

        // if the new stack configurations don't have the type, then simple add
        // all of the existing in
        Map<String, String> newDefaultConfigurations = newConfigurationsByType.get(
            configurationType);
        if (null == newDefaultConfigurations) {
          newConfigurationsByType.put(configurationType, existingConfigurations);
          continue;
        }

        // for every existing configuration, see if an entry exists; if it does
        // not exist, then put it in the map, otherwise we'll have to compare
        // the existing value to the original stack value to see if its been
        // customized
        for (Map.Entry<String, String> existingConfigurationEntry : existingConfigurations.entrySet()) {
          String existingConfigurationKey = existingConfigurationEntry.getKey();
          String existingConfigurationValue = existingConfigurationEntry.getValue();

          // if there is already an entry, we now have to try to determine if
          // the value was customized after stack installation
          if (newDefaultConfigurations.containsKey(existingConfigurationKey)) {
            String newDefaultConfigurationValue = newDefaultConfigurations.get(
                existingConfigurationKey);
            if (!StringUtils.equals(existingConfigurationValue, newDefaultConfigurationValue)) {
              // the new default is different from the existing cluster value;
              // only override the default value if the existing value differs
              // from the original stack
              Map<String, String> configurationTypeDefaultConfigurations = oldStackDefaultConfigurationsByType.get(
                  configurationType);
              if (null != configurationTypeDefaultConfigurations) {
                String oldDefaultValue = configurationTypeDefaultConfigurations.get(
                    existingConfigurationKey);
                if (!StringUtils.equals(existingConfigurationValue, oldDefaultValue)) {
                  // at this point, we've determined that there is a difference
                  // between default values between stacks, but the value was
                  // also customized, so keep the customized value
                  newDefaultConfigurations.put(existingConfigurationKey,
                      existingConfigurationValue);
                }
              }
            }
          } else {
            // there is no entry in the map, so add the existing key/value pair
            newDefaultConfigurations.put(existingConfigurationKey, existingConfigurationValue);
          }
        }
      }
    } else {
      // downgrade
      cluster.applyLatestConfigurations(cluster.getCurrentStackVersion());
    }

    // !!! update the stack
    cluster.setDesiredStackVersion(
        new StackId(targetStack.getStackName(), targetStack.getStackVersion()), true);

    // !!! configs must be created after setting the stack version
    if (null != newConfigurationsByType) {
      configHelper.createConfigTypes(cluster, getManagementController(), newConfigurationsByType,
          getManagementController().getAuthName(), "Configuration created for Upgrade");
    }
  }

  private RequestStageContainer createRequest(Direction direction, String version) {
    ActionManager actionManager = getManagementController().getActionManager();

    RequestStageContainer requestStages = new RequestStageContainer(
        actionManager.getNextRequestId(), null, s_requestFactory.get(), actionManager);
    requestStages.setRequestContext(String.format("%s to %s", direction.getVerb(true), version));

    return requestStages;
  }

  private void createStage(UpgradeContext context, RequestStageContainer request,
      UpgradeItemEntity entity, StageWrapper wrapper, boolean skippable, boolean allowRetry)
          throws AmbariException {

    switch (wrapper.getType()) {
      case RESTART:
        makeRestartStage(context, request, entity, wrapper, skippable, allowRetry);
        break;
      case RU_TASKS:
        makeActionStage(context, request, entity, wrapper, skippable, allowRetry);
        break;
      case SERVICE_CHECK:
        makeServiceCheckStage(context, request, entity, wrapper, skippable, allowRetry);
        break;
      default:
        break;
    }
  }

  private void makeActionStage(UpgradeContext context, RequestStageContainer request,
      UpgradeItemEntity entity, StageWrapper wrapper, boolean skippable, boolean allowRetry)
          throws AmbariException {

    if (0 == wrapper.getHosts().size()) {
      throw new AmbariException(
          String.format("Cannot create action for '%s' with no hosts", wrapper.getText()));
    }

    Cluster cluster = context.getCluster();

    // add each host to this stage
    RequestResourceFilter filter = new RequestResourceFilter("", "",
        new ArrayList<String>(wrapper.getHosts()));

    Map<String, String> params = getNewParameterMap();
    params.put(COMMAND_PARAM_TASKS, entity.getTasks());
    params.put(COMMAND_PARAM_VERSION, context.getVersion());
    params.put(COMMAND_PARAM_DIRECTION, context.getDirection().name().toLowerCase());
    params.put(COMMAND_PARAM_ORIGINAL_STACK, context.getOriginalStackId().getStackId());
    params.put(COMMAND_PARAM_TARGET_STACK, context.getTargetStackId().getStackId());
    params.put(COMMAND_DOWNGRADE_FROM_VERSION, context.getDowngradeFromVersion());

    // Because custom task may end up calling a script/function inside a
    // service, it is necessary to set the
    // service_package_folder and hooks_folder params.
    AmbariMetaInfo ambariMetaInfo = s_metaProvider.get();
    StackId stackId = cluster.getDesiredStackVersion();

    StackInfo stackInfo = ambariMetaInfo.getStack(stackId.getStackName(),
        stackId.getStackVersion());

    if (wrapper.getTasks() != null && wrapper.getTasks().size() > 0
        && wrapper.getTasks().get(0).getService() != null) {
      String serviceName = wrapper.getTasks().get(0).getService();
      ServiceInfo serviceInfo = ambariMetaInfo.getService(stackId.getStackName(),
          stackId.getStackVersion(), serviceName);
      params.put(SERVICE_PACKAGE_FOLDER, serviceInfo.getServicePackageFolder());
      params.put(HOOKS_FOLDER, stackInfo.getStackHooksFolder());
    }

    ActionExecutionContext actionContext = new ActionExecutionContext(cluster.getClusterName(),
        "ru_execute_tasks", Collections.singletonList(filter), params);

    actionContext.setIgnoreMaintenance(true);
    actionContext.setTimeout(Short.valueOf(s_configuration.getDefaultAgentTaskTimeout(false)));
    actionContext.setRetryAllowed(allowRetry);
    actionContext.setAutoSkipFailures(context.isComponentFailureAutoSkipped());

    ExecuteCommandJson jsons = s_commandExecutionHelper.get().getCommandJson(actionContext,
        cluster);

    Stage stage = s_stageFactory.get().createNew(request.getId().longValue(), "/tmp/ambari",
        cluster.getClusterName(), cluster.getClusterId(), entity.getText(),
        jsons.getClusterHostInfo(), jsons.getCommandParamsForStage(),
        jsons.getHostParamsForStage());

    stage.setSkippable(skippable);

    long stageId = request.getLastStageId() + 1;
    if (0L == stageId) {
      stageId = 1L;
    }

    stage.setStageId(stageId);
    entity.setStageId(Long.valueOf(stageId));

    s_actionExecutionHelper.get().addExecutionCommandsToStage(actionContext, stage);

    // need to set meaningful text on the command
    for (Map<String, HostRoleCommand> map : stage.getHostRoleCommands().values()) {
      for (HostRoleCommand hrc : map.values()) {
        hrc.setCommandDetail(entity.getText());
      }
    }

    request.addStages(Collections.singletonList(stage));
  }

  private void makeRestartStage(UpgradeContext context, RequestStageContainer request,
      UpgradeItemEntity entity, StageWrapper wrapper, boolean skippable, boolean allowRetry)
          throws AmbariException {

    Cluster cluster = context.getCluster();

    List<RequestResourceFilter> filters = new ArrayList<RequestResourceFilter>();

    for (TaskWrapper tw : wrapper.getTasks()) {
      // add each host to this stage
      filters.add(new RequestResourceFilter(tw.getService(), tw.getComponent(),
          new ArrayList<String>(tw.getHosts())));
    }

    Map<String, String> restartCommandParams = getNewParameterMap();
    restartCommandParams.put(COMMAND_PARAM_RESTART_TYPE, "rolling_upgrade");
    restartCommandParams.put(COMMAND_PARAM_VERSION, context.getVersion());
    restartCommandParams.put(COMMAND_PARAM_DIRECTION, context.getDirection().name().toLowerCase());
    restartCommandParams.put(COMMAND_PARAM_ORIGINAL_STACK,context.getOriginalStackId().getStackId());
    restartCommandParams.put(COMMAND_PARAM_TARGET_STACK, context.getTargetStackId().getStackId());
    restartCommandParams.put(COMMAND_DOWNGRADE_FROM_VERSION, context.getDowngradeFromVersion());

    ActionExecutionContext actionContext = new ActionExecutionContext(cluster.getClusterName(),
        "RESTART", filters, restartCommandParams);
    actionContext.setTimeout(Short.valueOf(s_configuration.getDefaultAgentTaskTimeout(false)));
    actionContext.setIgnoreMaintenance(true);
    actionContext.setRetryAllowed(allowRetry);
    actionContext.setAutoSkipFailures(context.isComponentFailureAutoSkipped());

    ExecuteCommandJson jsons = s_commandExecutionHelper.get().getCommandJson(actionContext,
        cluster);

    Stage stage = s_stageFactory.get().createNew(request.getId().longValue(), "/tmp/ambari",
        cluster.getClusterName(), cluster.getClusterId(), entity.getText(),
        jsons.getClusterHostInfo(), jsons.getCommandParamsForStage(),
        jsons.getHostParamsForStage());

    stage.setSkippable(skippable);

    long stageId = request.getLastStageId() + 1;
    if (0L == stageId) {
      stageId = 1L;
    }

    stage.setStageId(stageId);
    entity.setStageId(Long.valueOf(stageId));

    Map<String, String> requestParams = new HashMap<String, String>();
    requestParams.put("command", "RESTART");

    s_commandExecutionHelper.get().addExecutionCommandsToStage(actionContext, stage, requestParams);

    request.addStages(Collections.singletonList(stage));
  }

  private void makeServiceCheckStage(UpgradeContext context, RequestStageContainer request,
      UpgradeItemEntity entity, StageWrapper wrapper, boolean skippable, boolean allowRetry)
          throws AmbariException {

    List<RequestResourceFilter> filters = new ArrayList<RequestResourceFilter>();

    for (TaskWrapper tw : wrapper.getTasks()) {
      filters.add(new RequestResourceFilter(tw.getService(), "", Collections.<String> emptyList()));
    }

    Cluster cluster = context.getCluster();

    Map<String, String> commandParams = getNewParameterMap();
    commandParams.put(COMMAND_PARAM_VERSION, context.getVersion());
    commandParams.put(COMMAND_PARAM_DIRECTION, context.getDirection().name().toLowerCase());
    commandParams.put(COMMAND_PARAM_ORIGINAL_STACK, context.getOriginalStackId().getStackId());
    commandParams.put(COMMAND_PARAM_TARGET_STACK, context.getTargetStackId().getStackId());
    commandParams.put(COMMAND_DOWNGRADE_FROM_VERSION, context.getDowngradeFromVersion());

    ActionExecutionContext actionContext = new ActionExecutionContext(cluster.getClusterName(),
        "SERVICE_CHECK", filters, commandParams);

    actionContext.setTimeout(Short.valueOf(s_configuration.getDefaultAgentTaskTimeout(false)));
    actionContext.setIgnoreMaintenance(true);
    actionContext.setRetryAllowed(allowRetry);
    actionContext.setAutoSkipFailures(context.isServiceCheckFailureAutoSkipped());

    ExecuteCommandJson jsons = s_commandExecutionHelper.get().getCommandJson(actionContext,
        cluster);

    Stage stage = s_stageFactory.get().createNew(request.getId().longValue(), "/tmp/ambari",
        cluster.getClusterName(), cluster.getClusterId(), entity.getText(),
        jsons.getClusterHostInfo(), jsons.getCommandParamsForStage(),
        jsons.getHostParamsForStage());

    stage.setSkippable(skippable);

    long stageId = request.getLastStageId() + 1;
    if (0L == stageId) {
      stageId = 1L;
    }

    stage.setStageId(stageId);
    entity.setStageId(Long.valueOf(stageId));

    Map<String, String> requestParams = getNewParameterMap();
    s_commandExecutionHelper.get().addExecutionCommandsToStage(actionContext, stage, requestParams);

    request.addStages(Collections.singletonList(stage));
  }

  private void makeServerSideStage(UpgradeContext context, RequestStageContainer request,
      UpgradeItemEntity entity, ServerSideActionTask task, boolean skippable, boolean allowRetry)
          throws AmbariException {

    Cluster cluster = context.getCluster();

    Map<String, String> commandParams = getNewParameterMap();
    commandParams.put(COMMAND_PARAM_CLUSTER_NAME, cluster.getClusterName());
    commandParams.put(COMMAND_PARAM_VERSION, context.getVersion());
    commandParams.put(COMMAND_PARAM_DIRECTION, context.getDirection().name().toLowerCase());
    commandParams.put(COMMAND_PARAM_ORIGINAL_STACK, context.getOriginalStackId().getStackId());
    commandParams.put(COMMAND_PARAM_TARGET_STACK, context.getTargetStackId().getStackId());
    commandParams.put(COMMAND_DOWNGRADE_FROM_VERSION, context.getDowngradeFromVersion());

    String itemDetail = entity.getText();
    String stageText = StringUtils.abbreviate(entity.getText(), 255);

    switch (task.getType()) {
      case MANUAL: {
        ManualTask mt = (ManualTask) task;
        itemDetail = mt.message;
        if (null != mt.summary) {
          stageText = mt.summary;
        }
        entity.setText(itemDetail);

        if (null != mt.structuredOut) {
          commandParams.put(COMMAND_PARAM_STRUCT_OUT, mt.structuredOut);
        }

        break;
      }
      case CONFIGURE: {
        ConfigureTask ct = (ConfigureTask) task;
        Map<String, String> configurationChanges = ct.getConfigurationChanges(cluster);

        // add all configuration changes to the command params
        commandParams.putAll(configurationChanges);

        // extract the config type to build the summary
        String configType = configurationChanges.get(ConfigureTask.PARAMETER_CONFIG_TYPE);
        if (null != configType) {
          itemDetail = String.format("Updating configuration %s", configType);
        } else {
          itemDetail = "Skipping Configuration Task";
        }

        entity.setText(itemDetail);

        if (null != ct.summary) {
          stageText = ct.summary;
        } else {
          stageText = itemDetail;
        }

        break;
      }
      default:
        break;
    }

    ActionExecutionContext actionContext = new ActionExecutionContext(cluster.getClusterName(),
        Role.AMBARI_SERVER_ACTION.toString(), Collections.<RequestResourceFilter> emptyList(),
        commandParams);

    actionContext.setTimeout(Short.valueOf((short) -1));
    actionContext.setIgnoreMaintenance(true);
    actionContext.setRetryAllowed(allowRetry);
    actionContext.setAutoSkipFailures(context.isComponentFailureAutoSkipped());

    ExecuteCommandJson jsons = s_commandExecutionHelper.get().getCommandJson(actionContext,
        cluster);

    Stage stage = s_stageFactory.get().createNew(request.getId().longValue(), "/tmp/ambari",
        cluster.getClusterName(), cluster.getClusterId(), stageText, jsons.getClusterHostInfo(),
        jsons.getCommandParamsForStage(), jsons.getHostParamsForStage());

    stage.setSkippable(skippable);

    long stageId = request.getLastStageId() + 1;
    if (0L == stageId) {
      stageId = 1L;
    }

    stage.setStageId(stageId);
    entity.setStageId(Long.valueOf(stageId));

    stage.addServerActionCommand(task.getImplementationClass(),
        getManagementController().getAuthName(), Role.AMBARI_SERVER_ACTION, RoleCommand.EXECUTE,
        cluster.getClusterName(),
        new ServiceComponentHostServerActionEvent(null, System.currentTimeMillis()), commandParams,
        itemDetail, null, Integer.valueOf(1200), allowRetry,
        context.isComponentFailureAutoSkipped());

    request.addStages(Collections.singletonList(stage));
  }

  /**
   * Gets a map initialized with parameters required for rolling uprgades to
   * work. The following properties are already set:
   * <ul>
   * <li>{@link KeyNames#REFRESH_CONFIG_TAGS_BEFORE_EXECUTION} - necessary in
   * order to have the commands contain the correct configurations. Otherwise,
   * they will contain the configurations that were available at the time the
   * command was created. For upgrades, this is problematic since the commands
   * are all created ahead of time, but the upgrade may change configs as part
   * of the upgrade pack.</li>
   * <ul>
   *
   * @return
   */
  private Map<String, String> getNewParameterMap() {
    Map<String, String> parameters = new HashMap<String, String>();
    parameters.put(KeyNames.REFRESH_CONFIG_TAGS_BEFORE_EXECUTION, "*");
    return parameters;
  }
}
