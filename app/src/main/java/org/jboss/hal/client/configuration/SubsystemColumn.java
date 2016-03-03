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

import com.google.gwt.resources.client.ExternalTextResource;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.gwtplatform.mvp.client.proxy.PlaceManager;
import com.gwtplatform.mvp.shared.proxy.PlaceRequest;
import elemental.dom.Element;
import org.jboss.gwt.elemento.core.Elements;
import org.jboss.hal.ballroom.LabelBuilder;
import org.jboss.hal.client.tools.ModelBrowserPresenter;
import org.jboss.hal.core.finder.Finder;
import org.jboss.hal.core.finder.FinderColumn;
import org.jboss.hal.core.finder.ItemAction;
import org.jboss.hal.core.finder.ItemDisplay;
import org.jboss.hal.core.finder.PreviewContent;
import org.jboss.hal.dmr.ModelDescriptionConstants;
import org.jboss.hal.dmr.ModelNode;
import org.jboss.hal.dmr.dispatch.Dispatcher;
import org.jboss.hal.dmr.model.Operation;
import org.jboss.hal.dmr.model.ResourceAddress;
import org.jboss.hal.meta.AddressTemplate;
import org.jboss.hal.meta.StatementContext;
import org.jboss.hal.meta.subsystem.SubsystemMetadata;
import org.jboss.hal.meta.subsystem.Subsystems;
import org.jboss.hal.meta.token.NameTokens;
import org.jboss.hal.resources.Ids;
import org.jboss.hal.resources.Names;
import org.jboss.hal.resources.Resources;
import org.jboss.hal.spi.Column;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.google.common.base.CaseFormat.LOWER_CAMEL;
import static com.google.common.base.CaseFormat.LOWER_HYPHEN;
import static org.jboss.hal.dmr.ModelDescriptionConstants.*;
import static org.jboss.hal.resources.CSS.itemText;
import static org.jboss.hal.resources.CSS.subtitle;

/**
 * TODO Implement domain mode
 *
 * @author Harald Pehl
 */
@Column(Ids.SUBSYSTEM_COLUMN)
public class SubsystemColumn extends FinderColumn<SubsystemMetadata> {

    private static class ResourceDescriptionPreview extends PreviewContent {

        public ResourceDescriptionPreview(final String header, final Dispatcher dispatcher, final Operation operation) {
            super(header);
            builder.section().rememberAs(CONTENT_ELEMENT).end();
            Element content = builder.referenceFor(CONTENT_ELEMENT);
            dispatcher.execute(operation, result -> {
                if (result.hasDefined(DESCRIPTION)) {
                    SafeHtml html = SafeHtmlUtils.fromSafeConstant(result.get(DESCRIPTION).asString());
                    content.setInnerHTML(html.asString());
                }
            });
        }
    }


    private static final AddressTemplate SUBSYSTEM_TEMPLATE = AddressTemplate.of("{selected.profile}/subsystem=*");

    @Inject
    public SubsystemColumn(final Finder finder,
            final Dispatcher dispatcher,
            final PlaceManager placeManager,
            final StatementContext statementContext,
            final Subsystems subsystems,
            final Resources resources) {

        super(new Builder<SubsystemMetadata>(finder, Ids.SUBSYSTEM_COLUMN, Names.SUBSYSTEM)
                .itemRenderer(item -> new ItemDisplay<SubsystemMetadata>() {

                    @Override
                    public Element asElement() {
                        return item.getSubtitle() != null
                                ? new Elements.Builder()
                                .span().css(itemText)
                                .span().textContent(item.getTitle()).end()
                                .start("small").css(subtitle).textContent(item.getSubtitle()).end()
                                .end().build()
                                : null;
                    }

                    @Override
                    public String getTitle() {
                        return item.getTitle();
                    }

                    @Override
                    public String getFilterData() {
                        return item.getSubtitle() != null
                                ? item.getTitle() + " " + item.getSubtitle()
                                : item.getTitle();
                    }

                    @Override
                    public String nextColumn() {
                        return item.getNextColumn();
                    }

                    @Override
                    public List<ItemAction<SubsystemMetadata>> actions() {
                        PlaceRequest placeRequest;
                        if (item.isBuiltIn() && item.getToken() != null) {
                            placeRequest = new PlaceRequest.Builder().nameToken(item.getToken()).build();
                            return Collections.singletonList(new ItemAction<>(resources.constants().view(),
                                    item -> placeManager.revealPlace(placeRequest)));

                        } else if (!item.isBuiltIn()) {
                            ResourceAddress address = SUBSYSTEM_TEMPLATE.resolve(statementContext, item.getName());
                            placeRequest = new PlaceRequest.Builder()
                                    .nameToken(NameTokens.MODEL_BROWSER)
                                    .with(ModelBrowserPresenter.ADDRESS_PARAM, address.toString())
                                    .build();
                            return Collections.singletonList(new ItemAction<>(resources.constants().view(),
                                    item -> placeManager.revealPlace(placeRequest)));

                        } else {
                            return ItemDisplay.super.actions();
                        }
                    }
                })
                .showCount()
                .withFilter()

                .itemsProvider((context, callback) -> {
                    ResourceAddress address = AddressTemplate.of("/{selected.profile}").resolve(statementContext);
                    Operation subsystemOp = new Operation.Builder(READ_CHILDREN_NAMES_OPERATION, address)
                            .param(CHILD_TYPE, ModelDescriptionConstants.SUBSYSTEM).build();
                    dispatcher.execute(subsystemOp, result -> {

                        List<SubsystemMetadata> combined = new ArrayList<>();
                        for (ModelNode modelNode : result.asList()) {
                            String name = modelNode.asString();
                            if (subsystems.isBuiltIn(name)) {
                                combined.add(subsystems.getSubsystem(name));

                            } else {
                                String title = new LabelBuilder().label(name);
                                SubsystemMetadata subsystem = new SubsystemMetadata(name, title, null, null, null,
                                        false);
                                combined.add(subsystem);
                            }
                        }
                        callback.onSuccess(combined);
                    });
                })

                .onPreview(item -> {
                    String camelCase = LOWER_HYPHEN.to(LOWER_CAMEL, item.getName());
                    ExternalTextResource resource = resources.preview(camelCase);
                    if (resource != null) {
                        return new PreviewContent(item.getTitle(), resource);

                    } else {
                        ResourceAddress address = SUBSYSTEM_TEMPLATE.resolve(statementContext, item.getName());
                        Operation operation = new Operation.Builder(READ_RESOURCE_DESCRIPTION_OPERATION, address)
                                .build();
                        return new ResourceDescriptionPreview(item.getTitle(), dispatcher, operation);
                    }
                }));
    }
}
