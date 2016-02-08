/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.hal.client.configuration;

import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.annotations.NameToken;
import com.gwtplatform.mvp.client.annotations.ProxyStandard;
import com.gwtplatform.mvp.client.proxy.PlaceManager;
import com.gwtplatform.mvp.client.proxy.ProxyPlace;
import com.gwtplatform.mvp.shared.proxy.PlaceRequest;
import org.jboss.hal.core.finder.ColumnRegistry;
import org.jboss.hal.core.finder.CustomItem;
import org.jboss.hal.core.finder.CustomItemColumn;
import org.jboss.hal.core.finder.Finder;
import org.jboss.hal.core.finder.FinderPath;
import org.jboss.hal.core.finder.HasFinder;
import org.jboss.hal.core.finder.PreviewContent;
import org.jboss.hal.core.mvp.PatternFlyView;
import org.jboss.hal.core.mvp.TopLevelPresenter;
import org.jboss.hal.meta.token.NameTokens;
import org.jboss.hal.resources.Ids;
import org.jboss.hal.resources.Names;
import org.jboss.hal.resources.Resources;

import javax.inject.Inject;

import static com.google.gwt.safehtml.shared.SafeHtmlUtils.fromSafeConstant;
import static java.util.Arrays.asList;

/**
 * @author Harald Pehl
 */
public class ConfigurationPresenter
        extends TopLevelPresenter<ConfigurationPresenter.MyView, ConfigurationPresenter.MyProxy> {

    // @formatter:off
    @ProxyStandard
    @NameToken(NameTokens.CONFIGURATION)
    public interface MyProxy extends ProxyPlace<ConfigurationPresenter> {}

    public interface MyView extends PatternFlyView, HasFinder {}
    // @formatter:on


    private final Finder finder;
    private final CustomItemColumn initialColumn;
    private final PreviewContent initialPreview;
    private String path;

    @Inject
    public ConfigurationPresenter(final EventBus eventBus,
            final MyView view,
            final MyProxy proxy,
            final Finder finder,
            final PlaceManager placeManager,
            final Resources resources,
            final ColumnRegistry columnRegistry,
            final SubsystemColumn subsystemColumn,
            final InterfaceColumn interfaceColumn,
            final SocketBindingColumn socketBindingColumn) {
        super(eventBus, view, proxy);
        this.finder = finder;

        initialColumn = new CustomItemColumn(finder, Ids.CONFIGURATION_COLUMN, Names.CONFIGURATION);
        initialColumn.setItems(asList(
                new CustomItem.Builder(Names.SUBSYSTEMS)
                        .folder()
                        .onPreview(new PreviewContent(Names.SUBSYSTEMS,
                                fromSafeConstant(resources.previews().subsystems().getText())))
                        .build(),

                new CustomItem.Builder(Names.INTERFACES)
                        .folder()
                        .onPreview(new PreviewContent(Names.INTERFACES,
                                fromSafeConstant(resources.previews().interfaces().getText())))
                        .build(),

                new CustomItem.Builder(Names.SOCKET_BINDINGS)
                        .folder()
                        .onPreview(new PreviewContent(Names.SOCKET_BINDINGS,
                                fromSafeConstant(resources.previews().socketBindings().getText())))
                        .build(),

                new CustomItem.Builder(Names.PATHS)
                        .tokenAction(resources.constants().view(), placeManager, NameTokens.PATH)
                        .onPreview(new PreviewContent(Names.PATHS, fromSafeConstant(resources.previews().paths().getText())))
                        .build(),

                new CustomItem.Builder(Names.SYSTEM_PROPERTIES)
                        .tokenAction(resources.constants().view(), placeManager, NameTokens.SYSTEM_PROPERTIES)
                        .onPreview(new PreviewContent(Names.SYSTEM_PROPERTIES,
                                fromSafeConstant(resources.previews().systemProperties().getText())))
                        .build()
        ));
        columnRegistry.registerColumn(initialColumn, null);
        initialPreview = new PreviewContent(Names.CONFIGURATION,
                fromSafeConstant(resources.previews().standaloneConfiguration().getText()));
    }

    @Override
    protected void onBind() {
        super.onBind();
        getView().setFinder(finder);
    }

    @Override
    public void prepareFromRequest(final PlaceRequest request) {
        path = request.getParameter("path", null);
    }

    @Override
    protected void onReset() {
        super.onReset();
        if (path != null) {
            finder.select(FinderPath.of(path));
        } else {
            finder.reset(getProxy().getNameToken(), initialColumn, initialPreview);
        }
    }
}
