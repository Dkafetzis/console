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
package org.jboss.hal.client.runtime.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Provider;

import com.google.common.base.Joiner;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.shared.proxy.PlaceRequest;
import elemental.client.Browser;
import elemental.dom.Element;
import org.jboss.gwt.flow.Async;
import org.jboss.gwt.flow.Function;
import org.jboss.gwt.flow.FunctionContext;
import org.jboss.gwt.flow.Outcome;
import org.jboss.gwt.flow.Progress;
import org.jboss.hal.core.finder.ColumnActionFactory;
import org.jboss.hal.core.finder.Finder;
import org.jboss.hal.core.finder.FinderColumn;
import org.jboss.hal.core.finder.FinderContext;
import org.jboss.hal.core.finder.FinderPath;
import org.jboss.hal.core.finder.FinderSegment;
import org.jboss.hal.core.finder.ItemAction;
import org.jboss.hal.core.finder.ItemActionFactory;
import org.jboss.hal.core.finder.ItemDisplay;
import org.jboss.hal.core.finder.ItemMonitor;
import org.jboss.hal.core.runtime.host.HostSelectionEvent;
import org.jboss.hal.core.runtime.server.Server;
import org.jboss.hal.core.runtime.server.ServerActionEvent;
import org.jboss.hal.core.runtime.server.ServerActionEvent.ServerActionHandler;
import org.jboss.hal.core.runtime.server.ServerActions;
import org.jboss.hal.core.runtime.server.ServerResultEvent;
import org.jboss.hal.core.runtime.server.ServerResultEvent.ServerResultHandler;
import org.jboss.hal.core.runtime.server.ServerSelectionEvent;
import org.jboss.hal.dmr.ModelNode;
import org.jboss.hal.dmr.ModelNodeHelper;
import org.jboss.hal.dmr.dispatch.Dispatcher;
import org.jboss.hal.dmr.model.Composite;
import org.jboss.hal.dmr.model.CompositeResult;
import org.jboss.hal.dmr.model.Operation;
import org.jboss.hal.dmr.model.ResourceAddress;
import org.jboss.hal.meta.AddressTemplate;
import org.jboss.hal.meta.StatementContext;
import org.jboss.hal.meta.token.NameTokens;
import org.jboss.hal.resources.Icons;
import org.jboss.hal.resources.IdBuilder;
import org.jboss.hal.resources.Ids;
import org.jboss.hal.resources.Names;
import org.jboss.hal.resources.Resources;
import org.jboss.hal.spi.Column;
import org.jboss.hal.spi.Footer;
import org.jboss.hal.spi.Requires;

import static java.util.Collections.emptyList;
import static java.util.Comparator.comparing;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.jboss.hal.core.finder.FinderColumn.RefreshMode.RESTORE_SELECTION;
import static org.jboss.hal.dmr.ModelDescriptionConstants.*;

/**
 * @author Harald Pehl
 */
@Column(SERVER)
@Requires(value = {"/host=*/server-config=*", "/host=*/server=*"}, recursive = false)
public class ServerColumn extends FinderColumn<Server> implements ServerActionHandler, ServerResultHandler {

    public static boolean browseByHosts(FinderContext context) {
        FinderSegment firstSegment = context.getPath().iterator().next();
        return firstSegment.getValue().equals(IdBuilder.asId(Names.HOSTS));
    }

    public static boolean browseByServerGroups(FinderContext context) {
        if (!context.getPath().isEmpty()) {
            FinderSegment firstSegment = context.getPath().iterator().next();
            return firstSegment.getValue().equals(IdBuilder.asId(Names.SERVER_GROUPS));
        }
        return false;
    }

    private final Finder finder;

    @Inject
    public ServerColumn(final Finder finder,
            final Dispatcher dispatcher,
            final EventBus eventBus,
            final @Footer Provider<Progress> progress,
            final StatementContext statementContext,
            final ColumnActionFactory columnActionFactory,
            final ItemActionFactory itemActionFactory,
            final ServerActions serverActions,
            final Resources resources) {
        super(new Builder<Server>(finder, SERVER, Names.SERVER)
                .onItemSelect(server -> {
                    if (browseByServerGroups(finder.getContext())) {
                        // if we browse by server groups we still need to have a valid {selected.host}
                        eventBus.fireEvent(new HostSelectionEvent(server.getHost()));
                    }
                    eventBus.fireEvent(new ServerSelectionEvent(server.getName()));
                })
                .pinnable()
                .showCount()
                .useFirstActionAsBreadcrumbHandler()
                .withFilter()
                .onPreview(item -> new ServerPreview(serverActions, item, resources))
        );
        this.finder = finder;

        addColumnAction(columnActionFactory.add(IdBuilder.build(SERVER, "add"), Names.SERVER,
                column -> addServer(browseByHosts(finder.getContext()))));
        addColumnAction(columnActionFactory.refresh(IdBuilder.build(SERVER, "refresh")));

        setItemsProvider((context, callback) -> {
            Function<FunctionContext> serverConfigsFn;
            Function<FunctionContext> startedServersFn;
            boolean browseByHosts = browseByHosts(context);

            if (browseByHosts) {
                serverConfigsFn = control -> {
                    ResourceAddress address = AddressTemplate.of("/{selected.host}").resolve(statementContext);
                    Operation operation = new Operation.Builder(READ_CHILDREN_RESOURCES_OPERATION, address)
                            .param(CHILD_TYPE, SERVER_CONFIG)
                            .param(INCLUDE_RUNTIME, true)
                            .build();
                    dispatcher.executeInFunction(control, operation, result -> {
                        List<Server> servers = result.asPropertyList().stream()
                                .map(property -> new Server(statementContext.selectedHost(), property))
                                .collect(toList());
                        control.getContext().push(servers);
                        control.proceed();
                    });
                };

            } else {
                serverConfigsFn = control -> {
                    ResourceAddress serverConfigAddress = AddressTemplate.of("/host=*/server-config=*")
                            .resolve(statementContext);
                    Operation operation = new Operation.Builder(QUERY, serverConfigAddress)
                            .param(WHERE, new ModelNode().set(GROUP, statementContext.selectedServerGroup()))
                            .build();
                    dispatcher.executeInFunction(control, operation, result -> {
                        List<Server> servers = result.asList().stream()
                                .filter(modelNode -> !modelNode.isFailure())
                                .map(modelNode -> {
                                    ResourceAddress address = new ResourceAddress(modelNode.get(ADDRESS));
                                    String host = address.getParent().lastValue();
                                    return new Server(host, modelNode.get(RESULT));
                                })
                                .collect(toList());
                        control.getContext().push(servers);
                        control.proceed();
                    });
                };
            }

            startedServersFn = control -> {
                List<Server> servers = control.getContext().pop();
                if (servers != null) {
                    Composite composite = new Composite(servers.stream()
                            .filter(Server::isStarted)
                            .map(server -> new Operation.Builder(READ_RESOURCE_OPERATION,
                                    server.getServerAddress())
                                    .param(ATTRIBUTES_ONLY, true)
                                    .param(INCLUDE_RUNTIME, true)
                                    .build())
                            .collect(toList()));
                    if (!composite.isEmpty()) {
                        Map<String, Server> serverConfigsByName = servers.stream()
                                .collect(toMap(Server::getName, identity()));
                        dispatcher.executeInFunction(control, composite, (CompositeResult result) -> {
                            result.stream().forEach(step -> {
                                ModelNode payload = step.get(RESULT);
                                String name = payload.get(NAME).asString();
                                Server server = serverConfigsByName.get(name);
                                if (server != null) {
                                    server.addServerAttributes(payload);
                                }
                            });
                            control.getContext().push(servers);
                            control.proceed();
                        });
                    } else {
                        control.getContext().push(servers);
                        control.proceed();
                    }
                } else {
                    control.getContext().push(emptyList());
                    control.proceed();
                }
            };

            new Async<FunctionContext>(progress.get()).waterfall(new FunctionContext(),
                    new Outcome<FunctionContext>() {
                        @Override
                        public void onFailure(final FunctionContext context) {
                            callback.onFailure(context.getError());
                        }

                        @Override
                        public void onSuccess(final FunctionContext context) {
                            List<Server> servers = context.emptyStack() ? emptyList() : context.pop();
                            callback.onSuccess(servers.stream().sorted(comparing(Server::getName))
                                    .collect(toList()));

                            // Restore pending servers visualization
                            servers.stream()
                                    .filter(serverActions::isPending)
                                    .forEach(server -> ItemMonitor.startProgress(Server.id(server.getName())));
                        }
                    },
                    serverConfigsFn, startedServersFn);
        });

        setItemRenderer(item -> new ItemDisplay<Server>() {
            @Override
            public String getId() {
                return Server.id(item.getName());
            }

            @Override
            public String getTitle() {
                return item.getName();
            }

            @Override
            public String getFilterData() {
                List<String> data = new ArrayList<>();
                data.add(item.getName());
                data.add(ModelNodeHelper.asAttributeValue(item.getServerConfigStatus()));
                if (item.isStarted()) {
                    data.add(ModelNodeHelper.asAttributeValue(item.getServerState()));
                    data.add(ModelNodeHelper.asAttributeValue(item.getSuspendState()));
                } else {
                    data.add("stopped");
                }
                return Joiner.on(' ').join(data);
            }

            @Override
            public String getTooltip() {
                if (serverActions.isPending(item)) {
                    return resources.constants().pending();
                }else if (item.isAdminMode()) {
                    return resources.constants().adminOnly();
                } else if (item.isStarting()) {
                    return resources.constants().starting();
                } else if (item.isSuspended()) {
                    return resources.constants().suspended();
                } else if (item.needsReload()) {
                    return resources.constants().needsReload();
                } else if (item.needsRestart()) {
                    return resources.constants().needsRestart();
                } else if (item.isRunning()) {
                    return resources.constants().running();
                } else if (item.isFailed()) {
                    return resources.constants().failed();
                } else if (item.isStopped()) {
                    return resources.constants().stopped();
                } else {
                    return resources.constants().unknownState();
                }
            }

            @Override
            public Element getIcon() {
                if (serverActions.isPending(item)) {
                    return Icons.unknown();
                } else if (item.isAdminMode() || item.isStarting()) {
                    return Icons.disabled();
                } else if (item.isSuspended() || item.needsReload() || item.needsRestart()) {
                    return Icons.warning();
                } else if (item.isRunning()) {
                    return Icons.ok();
                } else if (item.isFailed()) {
                    return Icons.error();
                } else if (item.isStopped()) {
                    return Icons.stopped();
                } else {
                    return Icons.unknown();
                }
            }

            @Override
            public List<ItemAction<Server>> actions() {
                PlaceRequest placeRequest = new PlaceRequest.Builder().nameToken(NameTokens.SERVER_CONFIGURATION)
                        .with(HOST, item.getHost())
                        .with(SERVER_CONFIG, item.getName())
                        .build();
                List<ItemAction<Server>> actions = new ArrayList<>();
                actions.add(itemActionFactory.viewAndMonitor(Server.id(item.getName()), placeRequest));
                if (!serverActions.isPending(item)) {
                    if (!item.isStarted()) {
                        AddressTemplate template = AddressTemplate
                                .of("/host=" + item.getHost() + "/item-config=" + item.getName());
                        actions.add(itemActionFactory.remove(Names.SERVER, item.getName(), template, ServerColumn.this));
                    }
                    actions.add(new ItemAction<>(resources.constants().copy(),
                            itm -> copyServer(itm, browseByHosts(finder.getContext()))));
                    if (!item.isStarted()) {
                        actions.add(new ItemAction<>(resources.constants().start(), serverActions::start));
                    } else {
                        // Order is: reload, restart, (resume | suspend), stop
                        actions.add(new ItemAction<>(resources.constants().reload(), serverActions::reload));
                        actions.add(new ItemAction<>(resources.constants().restart(), serverActions::restart));
                        if (item.isSuspended()) {
                            actions.add(new ItemAction<>(resources.constants().resume(), serverActions::resume));
                        } else {
                            actions.add(new ItemAction<>(resources.constants().suspend(), serverActions::suspend));
                        }
                        actions.add(new ItemAction<>(resources.constants().stop(), serverActions::stop));
                    }
                }
                return actions;
            }

            @Override
            public String nextColumn() {
                return item.isStarted() ? Ids.SERVER_MONITOR_COLUMN : null;
            }
        });

        eventBus.addHandler(ServerActionEvent.getType(), this);
        eventBus.addHandler(ServerResultEvent.getType(), this);
    }

    private void addServer(boolean browseByHost) {
        Browser.getWindow().alert(Names.NYI);
    }

    private void copyServer(Server server, boolean browseByHost) {
        Browser.getWindow().alert(Names.NYI);
    }

    @Override
    public void onServerAction(final ServerActionEvent event) {
        if (isVisible()) {
            ItemMonitor.startProgress(Server.id(event.getServer().getName()));
            refresh(RESTORE_SELECTION);
        }
    }

    @Override
    public void onServerResult(final ServerResultEvent event) {
        if (isVisible()) {
            Server server = event.getServer();
            String itemId = Server.id(server.getName());
            ItemMonitor.stopProgress(itemId);

            // Remove the 'Browse By' segment
            FinderPath refreshPath = new FinderPath();
            for (FinderSegment segment : finder.getContext().getPath()) {
                if (segment.getKey().equals(Ids.DOMAIN_BROWSE_BY_COLUMN)) {
                    continue;
                }
                refreshPath.append(segment.getKey(), segment.getValue());
            }
            finder.refresh(refreshPath);
        }
    }
}
