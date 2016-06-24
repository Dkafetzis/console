/*
 * Copyright 2015-2016 Red Hat, Inc, and individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.hal.core.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.google.common.collect.Lists;
import org.jboss.gwt.flow.Control;
import org.jboss.gwt.flow.Function;
import org.jboss.gwt.flow.FunctionContext;
import org.jboss.hal.config.Environment;
import org.jboss.hal.core.runtime.group.ServerGroup;
import org.jboss.hal.core.runtime.host.Host;
import org.jboss.hal.core.runtime.server.Server;
import org.jboss.hal.core.runtime.server.ServerConfigStatus;
import org.jboss.hal.dmr.ModelNode;
import org.jboss.hal.dmr.Property;
import org.jboss.hal.dmr.dispatch.Dispatcher;
import org.jboss.hal.dmr.model.Composite;
import org.jboss.hal.dmr.model.CompositeResult;
import org.jboss.hal.dmr.model.Operation;
import org.jboss.hal.dmr.model.ResourceAddress;

import static java.util.Collections.emptyList;
import static java.util.Comparator.comparing;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.jboss.hal.dmr.ModelDescriptionConstants.*;
import static org.jboss.hal.dmr.model.ResourceAddress.ROOT;

/**
 * Set of functions to read runtime data like running server of a specific server group.
 *
 * @author Harald Pehl
 */
public class TopologyFunctions {

    public static final String SERVER_GROUPS = "topologyFunctions.serverGroups";
    public static final String HOSTS = "topologyFunctions.hosts";
    public static final String SERVERS = "topologyFunctions.servers";
    public static final String RUNNING_SERVERS = "topologyFunctions.runningServers";

    private static final ResourceAddress ALL_SERVER_CONFIGS = new ResourceAddress()
            .add(HOST, "*")
            .add(SERVER_CONFIG, "*");
    private static final ResourceAddress ALL_SERVERS = new ResourceAddress()
            .add(HOST, "*")
            .add(SERVER, "*");
    private static final Operation HOST_OPERATION = new Operation.Builder(READ_CHILDREN_RESOURCES_OPERATION, ROOT)
            .param(CHILD_TYPE, HOST)
            .param(INCLUDE_RUNTIME, true)
            .build();
    private static final Operation SERVER_GROUP_OPERATION = new Operation.Builder(READ_CHILDREN_RESOURCES_OPERATION,
            ROOT)
            .param(CHILD_TYPE, SERVER_GROUP)
            .param(INCLUDE_RUNTIME, true)
            .build();


    /**
     * Returns a list of running servers which belong to the specified profile. Stores the list in the context under
     * the key {@link TopologyFunctions#RUNNING_SERVERS}. Stores an empty list when running in standalone mode.
     */
    public static class RunningServersOfProfile implements Function<FunctionContext> {

        private final Environment environment;
        private final Dispatcher dispatcher;
        private final String profile;

        public RunningServersOfProfile(final Environment environment, final Dispatcher dispatcher,
                final String profile) {
            this.environment = environment;
            this.dispatcher = dispatcher;
            this.profile = profile;
        }

        @Override
        @SuppressWarnings("HardCodedStringLiteral")
        public void execute(final Control<FunctionContext> control) {
            if (environment.isStandalone()) {
                List<Server> servers = Collections.emptyList();
                control.getContext().set(RUNNING_SERVERS, servers);
                control.proceed();

            } else {
                ModelNode select = new ModelNode().add(HOST).add(LAUNCH_TYPE).add(NAME).add(PROFILE_NAME)
                        .add(RUNNING_MODE).add(SERVER_GROUP).add(SERVER_STATE).add(SUSPEND_STATE).add("uuid");

                Operation operation = new Operation.Builder(QUERY, ALL_SERVERS)
                        .param(SELECT, select)
                        .param(WHERE, new ModelNode().set(PROFILE_NAME, profile))
                        .build();

                dispatcher.executeInFunction(control, operation, result -> {
                    List<Server> servers = result.asList().stream()
                            .filter(modelNode -> !modelNode.isFailure())
                            .map(modelNode -> {
                                ResourceAddress adr = new ResourceAddress(modelNode.get(ADDRESS));
                                String host = adr.getParent().lastValue();
                                return new Server(host, modelNode.get(RESULT));
                            })
                            .collect(toList());
                    control.getContext().set(RUNNING_SERVERS, servers);
                    control.proceed();
                });
            }
        }
    }


    /**
     * Reads the topology (hosts, server groups and servers). Populates the context with three collections
     * <ul>
     * <li>{@link #HOSTS}: An ordered list of hosts with the domain controller as first element. Each host contains its
     * servers which are returned by {@link Host#getServers(ServerConfigStatus, ServerConfigStatus...)}</li>
     * <li>{@link #SERVER_GROUPS}: An ordered list of server groups. Each server group contains its servers which are
     * returned by {@link ServerGroup#getServers(ServerConfigStatus, ServerConfigStatus...)}</li>
     * <li>{@link #SERVERS}: An unordered list of all servers in the domain. The servers contain only selected
     * attributes from the {@code server-config} and {@code server} resources.
     * </ul>
     */
    public static class Topology implements Function<FunctionContext> {

        private final Environment environment;
        private final Dispatcher dispatcher;

        public Topology(final Environment environment, final Dispatcher dispatcher) {
            this.environment = environment;
            this.dispatcher = dispatcher;
        }

        @Override
        public void execute(final Control<FunctionContext> control) {
            if (environment.isStandalone()) {
                List<Host> hosts = Collections.emptyList();
                List<ServerGroup> serverGroups = Collections.emptyList();
                List<Server> servers = Collections.emptyList();
                control.getContext().set(HOSTS, hosts);
                control.getContext().set(SERVER_GROUPS, serverGroups);
                control.getContext().set(SERVERS, servers);
                control.proceed();

            } else {
                Composite composite = new Composite(HOST_OPERATION, SERVER_GROUP_OPERATION,
                        serverConfigOperation(NAME, GROUP, STATUS, AUTO_START, SOCKET_BINDING_PORT_OFFSET),
                        serverOperation(NAME, HOST, GROUP, PROFILE_NAME, AUTO_START, SOCKET_BINDING_PORT_OFFSET, STATUS,
                                RUNNING_MODE, SERVER_STATE, SUSPEND_STATE)); // keep this in sync w/ topology preview
                dispatcher.executeInFunction(control, composite,
                        (CompositeResult result) -> {

                            List<Host> hosts = orderedHostWithDomainControllerAsFirstElement(
                                    result.step(0).get(RESULT).asPropertyList());
                            control.getContext().set(HOSTS, hosts);

                            List<ServerGroup> serverGroups = result.step(1).get(RESULT).asPropertyList().stream()
                                    .map(ServerGroup::new)
                                    .sorted(comparing(ServerGroup::getName))
                                    .collect(toList());
                            control.getContext().set(SERVER_GROUPS, serverGroups);

                            Map<String, Server> serverConfigsByName = serverConfigsByName(result.step(2).get(RESULT).asList());
                            addServerAttributes(serverConfigsByName, result.step(3).get(RESULT).asList());
                            control.getContext().set(SERVERS, Lists.newArrayList(serverConfigsByName.values()));

                            addServersToHosts(serverConfigsByName.values(), hosts);
                            addServersToServerGroups(serverConfigsByName.values(), serverGroups);

                            control.proceed();
                        });
            }
        }
    }


    /**
     * Reads the hosts as order list with the domain controller as first element. Each host contains its
     * servers which are returned by {@link Host#getServers(ServerConfigStatus, ServerConfigStatus...)}
     * <p>
     * The list of hosts is available in the context under the key {@link #HOSTS}.
     */
    public static class HostsWithServers implements Function<FunctionContext> {

        private final Environment environment;
        private final Dispatcher dispatcher;

        public HostsWithServers(final Environment environment, final Dispatcher dispatcher) {
            this.environment = environment;
            this.dispatcher = dispatcher;
        }

        @Override
        public void execute(final Control<FunctionContext> control) {
            if (environment.isStandalone()) {
                control.proceed();
            } else {
                Composite composite = new Composite(HOST_OPERATION,
                        serverConfigOperation(NAME, GROUP, STATUS),
                        serverOperation(NAME, RUNNING_MODE, SERVER_STATE, SUSPEND_STATE));
                dispatcher.executeInFunction(control, composite, (CompositeResult result) -> {

                    List<Host> hosts = orderedHostWithDomainControllerAsFirstElement(
                            result.step(0).get(RESULT).asPropertyList());

                    Map<String, Server> serverConfigsByName = serverConfigsByName(result.step(1).get(RESULT).asList());
                    addServerAttributes(serverConfigsByName, result.step(2).get(RESULT).asList());
                    addServersToHosts(serverConfigsByName.values(), hosts);

                    control.getContext().set(HOSTS, hosts);
                    control.proceed();
                });
            }
        }
    }


    /**
     * Reads the server groups as order list. Each server group contains its servers which are available by {@link
     * ServerGroup#getServers(ServerConfigStatus, ServerConfigStatus...)}.
     * <p>
     * The list of server groups is available in the context under the key {@link #SERVER_GROUPS}.
     */
    public static class ServerGroupsWithServers implements Function<FunctionContext> {

        private final Environment environment;
        private final Dispatcher dispatcher;

        public ServerGroupsWithServers(final Environment environment, final Dispatcher dispatcher) {
            this.environment = environment;
            this.dispatcher = dispatcher;
        }

        @Override
        public void execute(final Control<FunctionContext> control) {
            if (environment.isStandalone()) {
                control.proceed();
            } else {
                Composite composite = new Composite(SERVER_GROUP_OPERATION,
                        serverConfigOperation(NAME, GROUP, STATUS),
                        serverOperation(NAME, RUNNING_MODE, SERVER_STATE, SUSPEND_STATE));
                dispatcher.executeInFunction(control, composite, (CompositeResult result) -> {

                    List<ServerGroup> serverGroups = result.step(0).get(RESULT).asPropertyList().stream()
                            .map(ServerGroup::new)
                            .sorted(comparing(ServerGroup::getName))
                            .collect(toList());

                    Map<String, Server> serverConfigsByName = serverConfigsByName(result.step(1).get(RESULT).asList());
                    addServerAttributes(serverConfigsByName, result.step(2).get(RESULT).asList());
                    addServersToServerGroups(serverConfigsByName.values(), serverGroups);

                    control.getContext().set(SERVER_GROUPS, serverGroups);
                    control.proceed();
                });
            }
        }
    }


    // ------------------------------------------------------ helper methods

    private static Operation serverConfigOperation(String first, String... rest) {
        ModelNode select = new ModelNode().add(first);
        if (rest != null) {
            for (String attribute : rest) {
                select.add(attribute);
            }
        }
        return new Operation.Builder(QUERY, ALL_SERVER_CONFIGS)
                .param(SELECT, select)
                .build();
    }

    private static Operation serverOperation(String first, String... rest) {
        ModelNode select = new ModelNode().add(first);
        if (rest != null) {
            for (String attribute : rest) {
                select.add(attribute);
            }
        }
        return new Operation.Builder(QUERY, ALL_SERVERS)
                .param(SELECT, select)
                .build();
    }

    private static List<Host> orderedHostWithDomainControllerAsFirstElement(List<Property> properties) {
        // first collect all hosts, sort them by name and finally
        // remove the domain controller to add it as first element
        List<Host> allHosts = properties.stream()
                .map(Host::new)
                .sorted(comparing(Host::getName))
                .collect(toList());
        Host domainController = null;
        List<Host> hosts = new ArrayList<>(allHosts);
        for (Iterator<Host> iterator = hosts.iterator();
                iterator.hasNext() && domainController == null; ) {
            Host host = iterator.next();
            if (host.isDomainController()) {
                domainController = host;
                iterator.remove();
            }
        }
        if (domainController != null) {
            hosts.add(0, domainController);
        }
        return hosts;
    }

    private static void addServersToHosts(final Collection<Server> servers, final List<Host> hosts) {
        Map<String, List<Server>> serversByHost = servers.stream()
                .collect(groupingBy(Server::getHost));
        hosts.forEach(host -> {
            List<Server> serversOfHost = serversByHost.getOrDefault(host.getName(), emptyList());
            serversOfHost.forEach(host::addServer);
        });
    }

    private static void addServersToServerGroups(final Collection<Server> servers,
            final List<ServerGroup> serverGroups) {
        Map<String, List<Server>> serversByServerGroup = servers.stream()
                .collect(groupingBy(Server::getServerGroup));
        serverGroups.forEach(serverGroup -> {
            List<Server> serversOfServerGroup = serversByServerGroup
                    .getOrDefault(serverGroup.getName(), emptyList());
            serversOfServerGroup.forEach(serverGroup::addServer);
        });
    }

    private static Map<String, Server> serverConfigsByName(List<ModelNode> modelNodes) {
        return modelNodes.stream()
                .filter(modelNode -> !modelNode.isFailure())
                .map(modelNode -> {
                    ResourceAddress address = new ResourceAddress(modelNode.get(ADDRESS));
                    String host = address.getParent().lastValue();
                    return new Server(host, modelNode.get(RESULT));
                })
                .collect(toMap(Server::getName, identity()));
    }

    private static void addServerAttributes(Map<String, Server> serverConfigsByName, List<ModelNode> modelNodes) {
        modelNodes.stream()
                .filter(modelNode -> !modelNode.isFailure())
                .forEach(modelNode -> {
                    ResourceAddress address = new ResourceAddress(modelNode.get(ADDRESS));
                    Server serverConfig = serverConfigsByName.get(address.lastValue());
                    if (serverConfig != null) {
                        serverConfig.addServerAttributes(modelNode.get(RESULT));
                    }
                });
    }
}