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
package org.apache.ambari.server.topology.addservice;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.actionmanager.ActionManager;
import org.apache.ambari.server.actionmanager.RequestFactory;
import org.apache.ambari.server.controller.AddServiceRequest;
import org.apache.ambari.server.controller.AmbariManagementController;
import org.apache.ambari.server.controller.KerberosHelper;
import org.apache.ambari.server.controller.RequestStatusResponse;
import org.apache.ambari.server.serveraction.kerberos.KerberosInvalidConfigurationException;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.SecurityType;
import org.apache.ambari.server.state.Service;
import org.apache.ambari.server.state.State;
import org.apache.ambari.server.topology.Configuration;
import org.apache.ambari.server.utils.StageUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

@Singleton
public class AddServiceOrchestrator {

  private static final Logger LOG = LoggerFactory.getLogger(AddServiceOrchestrator.class);

  @Inject
  private ResourceProviderAdapter resourceProviders;

  @Inject
  private AmbariManagementController controller;

  @Inject
  private ActionManager actionManager;

  @Inject
  private RequestFactory requestFactory;

  @Inject
  private RequestValidatorFactory requestValidatorFactory;

  @Inject
  private StackAdvisorAdapter stackAdvisorAdapter;

  public RequestStatusResponse processAddServiceRequest(Cluster cluster, AddServiceRequest request) {
    LOG.info("Received {} request for {}: {}", request.getOperationType(), cluster.getClusterName(), request);

    AddServiceInfo validatedRequest = validate(cluster, request);
    AddServiceInfo requestWithLayout = recommendLayout(validatedRequest);
    AddServiceInfo requestWithConfig = recommendConfiguration(requestWithLayout);

    createResources(cluster, requestWithConfig);
    createHostTasks(requestWithConfig);

    return requestWithConfig.getStages().getRequestStatusResponse();
  }

  /**
   * Performs basic validation of the request and
   * fills in details about the requested services and components.
   *
   * @return validated information about the requested services
   */
  private AddServiceInfo validate(Cluster cluster, AddServiceRequest request) {
    LOG.info("Validating {}", request);

    RequestValidator validator = requestValidatorFactory.create(request, cluster);
    validator.validate();

    return validator.createValidServiceInfo(actionManager, requestFactory);
  }

  /**
   * Requests layout recommendation from the stack advisor.
   * @return new request, updated based on the recommended layout
   * @throws IllegalArgumentException if the request cannot be satisfied
   */
  private AddServiceInfo recommendLayout(AddServiceInfo request) {
    LOG.info("Recommending layout for {}", request);
    return stackAdvisorAdapter.recommendLayout(request);
  }

  /**
   * Requests config recommendation from the stack advisor.
   * @return new request, updated with the recommended config
   * @throws IllegalArgumentException if the request cannot be satisfied
   */
  private AddServiceInfo recommendConfiguration(AddServiceInfo request) {
    LOG.info("Recommending configuration for {}", request);
    // TODO implement
    return request;
  }

  /**
   * Creates the service, component and host component resources for the request.
   */
  private void createResources(Cluster cluster, AddServiceInfo request) {
    LOG.info("Creating resources for {}", request);

    Set<String> existingServices = cluster.getServices().keySet();

    resourceProviders.createCredentials(request);

    resourceProviders.createServices(request);
    resourceProviders.createComponents(request);

    resourceProviders.updateServiceDesiredState(request, State.INSTALLED);
    if (!request.getRequest().getProvisionAction().skipStart()) {
      resourceProviders.updateServiceDesiredState(request, State.STARTED);
    }
    resourceProviders.createHostComponents(request);

    configureKerberos(request, cluster, existingServices);
    resourceProviders.updateExistingConfigs(request, existingServices);
    resourceProviders.createConfigs(request);
  }

  private void configureKerberos(AddServiceInfo request, Cluster cluster, Set<String> existingServices) {
    if (cluster.getSecurityType() == SecurityType.KERBEROS) {
      LOG.info("Configuring Kerberos for {}", request);

      Configuration stackDefaultConfig = request.getStack().getValidDefaultConfig();
      Set<String> newServices = request.newServices().keySet();
      Set<String> services = ImmutableSet.copyOf(Sets.union(newServices, existingServices));
      Map<String, Map<String, String>> existingConfigurations = request.getConfig().getFullProperties();
      existingConfigurations.put(KerberosHelper.CLUSTER_HOST_INFO, createComponentHostMap(cluster));

      try {
        KerberosHelper kerberosHelper = controller.getKerberosHelper();
        kerberosHelper.ensureHeadlessIdentities(cluster, existingConfigurations, services);
        request.getConfig().applyUpdatesToStackDefaultProperties(stackDefaultConfig, existingConfigurations,
          kerberosHelper.getServiceConfigurationUpdates(
            cluster, existingConfigurations, createServiceComponentMap(cluster), null, existingServices, true, true
          )
        );
      } catch (AmbariException | KerberosInvalidConfigurationException e) {
        LOG.error("Error configuring Kerberos: {}", e, e);
        throw new RuntimeException(e);
      }
    }
  }

  private void createHostTasks(AddServiceInfo request) {
    LOG.info("Creating host tasks for {}: {}", request, request.getRequest().getProvisionAction());

    resourceProviders.updateHostComponentDesiredState(request, State.INSTALLED);
    if (!request.getRequest().getProvisionAction().skipStart()) {
      resourceProviders.updateHostComponentDesiredState(request, State.STARTED);
    }

    try {
      request.getStages().persist();
    } catch (AmbariException e) {
      String msg = String.format("Error creating host tasks for %s", request);
      LOG.error(msg, e);
      throw new IllegalStateException(msg, e);
    }
  }

  private static Map<String, String> createComponentHostMap(Cluster cluster) {
    return StageUtils.createComponentHostMap(
      cluster.getServices().keySet(),
      service -> getComponentsForService(cluster, service),
      (service, component) -> getHostsForServiceComponent(cluster, service, component)
    );
  }

  private static Set<String> getHostsForServiceComponent(Cluster cluster, String service, String component) {
    try {
      return cluster.getService(service).getServiceComponent(component).getServiceComponentsHosts();
    } catch (AmbariException e) {
      LOG.error("Error getting components of service {}: {}", service, e, e);
      throw new RuntimeException(e);
    }
  }

  private static Set<String> getComponentsForService(Cluster cluster, String service) {
    try {
      return cluster.getService(service).getServiceComponents().keySet();
    } catch (AmbariException e) {
      LOG.error("Error getting components of service {}: {}", service, e, e);
      throw new RuntimeException(e);
    }
  }

  private static Map<String, Set<String>> createServiceComponentMap(Cluster cluster) {
    Map<String, Set<String>> serviceComponentMap = new HashMap<>();
    for (Map.Entry<String, Service> e : cluster.getServices().entrySet()) {
      serviceComponentMap.put(e.getKey(), ImmutableSet.copyOf(e.getValue().getServiceComponents().keySet()));
    }
    return serviceComponentMap;
  }

}
