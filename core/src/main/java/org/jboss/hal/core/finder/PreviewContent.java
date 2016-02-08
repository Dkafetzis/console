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
package org.jboss.hal.core.finder;

import com.google.gwt.safehtml.shared.SafeHtml;
import elemental.dom.Element;
import org.jboss.gwt.elemento.core.Elements;
import org.jboss.hal.meta.security.SecurityContext;
import org.jboss.hal.meta.security.SecurityContextAware;

/**
 * Wrapper for the preview content which consists of a header and either a safe html string or one or more elements.
 *
 * @author Harald Pehl
 */
public class PreviewContent implements SecurityContextAware {

    private Elements.Builder builder;

    public PreviewContent(final String header, final SafeHtml content) {
        builder = new Elements.Builder().h(1).innerText(header).end();
        builder.section().innerHtml(content).end();
    }

    public PreviewContent(final String header, final Element first, final Element... rest) {
        builder = new Elements.Builder().h(1).innerText(header).end();
        builder.section().add(first);
        if (rest != null) {
            for (Element element : rest) {
                builder.add(element);
            }
        }
        builder.end();
    }

    public Iterable<Element> elements() {
        return builder.elements();
    }

    @Override
    public void onSecurityContextChange(final SecurityContext securityContext) {

    }
}
