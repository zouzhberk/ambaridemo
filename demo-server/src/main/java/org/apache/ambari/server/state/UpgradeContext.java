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
package org.apache.ambari.server.state;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ambari.server.api.services.AmbariMetaInfo;
import org.apache.ambari.server.stack.MasterHostResolver;
import org.apache.ambari.server.state.stack.upgrade.Direction;

/**
 * Used to hold various helper objects required to process an upgrade pack.
 */
public class UpgradeContext {

  private String m_version;

  /**
   * The original "current" stack of the cluster before the upgrade started.
   * This is the same regardless of whether the current direction is
   * {@link Direction#UPGRADE} or {@link Direction#DOWNGRADE}.
   */
  private StackId m_originalStackId;

  /**
   * The target upgrade stack before the upgrade started. This is the same
   * regardless of whether the current direction is {@link Direction#UPGRADE} or
   * {@link Direction#DOWNGRADE}.
   */
  private StackId m_targetStackId;

  private Direction m_direction;
  private MasterHostResolver m_resolver;
  private AmbariMetaInfo m_metaInfo;
  private List<ServiceComponentHost> m_unhealthy = new ArrayList<ServiceComponentHost>();
  private Map<String, String> m_serviceNames = new HashMap<String, String>();
  private Map<String, String> m_componentNames = new HashMap<String, String>();
  private String m_downgradeFromVersion = null;

  /**
   * {@code true} if slave/client component failures should be automatically
   * skipped. This will only automatically skip the failure if the task is
   * skippable to begin with.
   */
  private boolean m_autoSkipComponentFailures = false;

  /**
   * {@code true} if service check failures should be automatically skipped.
   * This will only automatically skip the failure if the task is skippable to
   * begin with.
   */
  private boolean m_autoSkipServiceCheckFailures = false;

  /**
   * Constructor.
   *
   * @param resolver
   *          the resolver that also references the required cluster
   * @param sourceStackId
   *          the original "current" stack of the cluster before the upgrade
   *          started. This is the same regardless of whether the current
   *          direction is {@link Direction#UPGRADE} or
   *          {@link Direction#DOWNGRADE} (not {@code null}).
   * @param targetStackId
   *          The target upgrade stack before the upgrade started. This is the
   *          same regardless of whether the current direction is
   *          {@link Direction#UPGRADE} or {@link Direction#DOWNGRADE} (not
   *          {@code null}).
   * @param version
   *          the target version to upgrade to
   * @param direction
   *          the direction for the upgrade
   */
  public UpgradeContext(MasterHostResolver resolver, StackId sourceStackId,
      StackId targetStackId, String version,
      Direction direction) {
    m_version = version;
    m_originalStackId = sourceStackId;
    m_targetStackId = targetStackId;
    m_direction = direction;
    m_resolver = resolver;
  }

  /**
   * @return the cluster from the {@link MasterHostResolver}
   */
  public Cluster getCluster() {
    return m_resolver.getCluster();
  }

  /**
   * @return the target version for the upgrade
   */
  public String getVersion() {
    return m_version;
  }

  /**
   * @return the direction of the upgrade
   */
  public Direction getDirection() {
    return m_direction;
  }

  /**
   * @return the resolver
   */
  public MasterHostResolver getResolver() {
    return m_resolver;
  }

  /**
   * @return the metainfo for access to service definitions
   */
  public AmbariMetaInfo getAmbariMetaInfo() {
    return m_metaInfo;
  }

  /**
   * @param metaInfo the metainfo for access to service definitions
   */
  public void setAmbariMetaInfo(AmbariMetaInfo metaInfo) {
    m_metaInfo = metaInfo;
  }

  /**
   * @param unhealthy a list of unhealthy host components
   */
  public void addUnhealthy(List<ServiceComponentHost> unhealthy) {
    m_unhealthy.addAll(unhealthy);
  }

  /**
   * @return the originalStackId
   */
  public StackId getOriginalStackId() {
    return m_originalStackId;
  }

  /**
   * @param originalStackId
   *          the originalStackId to set
   */
  public void setOriginalStackId(StackId originalStackId) {
    m_originalStackId = originalStackId;
  }

  /**
   * @return the targetStackId
   */
  public StackId getTargetStackId() {
    return m_targetStackId;
  }

  /**
   * @param targetStackId
   *          the targetStackId to set
   */
  public void setTargetStackId(StackId targetStackId) {
    m_targetStackId = targetStackId;
  }

  /**
   * @return a map of host to list of components.
   */
  public Map<String, List<String>> getUnhealthy() {
    Map<String, List<String>> results = new HashMap<String, List<String>>();

    for (ServiceComponentHost sch : m_unhealthy) {
      if (!results.containsKey(sch.getHostName())) {
        results.put(sch.getHostName(), new ArrayList<String>());
      }
      results.get(sch.getHostName()).add(sch.getServiceComponentName());
    }

    return results;
  }

  /**
   * @return the service display name, or the service name if not set
   */
  public String getServiceDisplay(String service) {
    if (m_serviceNames.containsKey(service)) {
      return m_serviceNames.get(service);
    }

    return service;
  }

  /**
   * @return the component display name, or the component name if not set
   */
  public String getComponentDisplay(String service, String component) {
    String key = service + ":" + component;
    if (m_componentNames.containsKey(key)) {
      return m_componentNames.get(key);
    }

    return component;
  }

  /**
   * @param service     the service name
   * @param displayName the display name for the service
   */
  public void setServiceDisplay(String service, String displayName) {
    m_serviceNames.put(service, (displayName == null) ? service : displayName);
  }

  /**
   * @param service     the service name that owns the component
   * @param component   the component name
   * @param displayName the display name for the component
   */
  public void setComponentDisplay(String service, String component, String displayName) {
    String key = service + ":" + component;
    m_componentNames.put(key, displayName);
  }

  /**
   * This method returns the non-finalized version we are downgrading from.
   *
   * @return version cluster is downgrading from
   */
  public String getDowngradeFromVersion() {
    return m_downgradeFromVersion;
  }

  /**
   * Set the HDP stack version we are downgrading from.
   *
   * @param downgradeFromVersion
   */
  public void setDowngradeFromVersion(String downgradeFromVersion) {
    m_downgradeFromVersion = downgradeFromVersion;
  }

  /**
   * Gets whether skippable components that failed are automatically skipped.
   *
   * @return the skipComponentFailures
   */
  public boolean isComponentFailureAutoSkipped() {
    return m_autoSkipComponentFailures;
  }

  /**
   * Sets whether skippable components that failed are automatically skipped.
   *
   * @param skipComponentFailures
   *          {@code true} to automatically skip component failures which are
   *          marked as skippable.
   */
  public void setAutoSkipComponentFailures(boolean autoSkipComponentFailures) {
    m_autoSkipComponentFailures = autoSkipComponentFailures;
  }

  /**
   * Gets whether skippable service checks that failed are automatically
   * skipped.
   *
   * @return the skipServiceCheckFailures
   */
  public boolean isServiceCheckFailureAutoSkipped() {
    return m_autoSkipServiceCheckFailures;
  }

  /**
   * Sets whether skippable service checks that failed are automatically
   * skipped.
   *
   * @param autoSkipServiceCheckFailures
   *          {@code true} to automatically skip service check failures which
   *          are marked as being skippable.
   */
  public void setAutoSkipServiceCheckFailures(boolean autoSkipServiceCheckFailures) {
    m_autoSkipServiceCheckFailures = autoSkipServiceCheckFailures;
  }

}
