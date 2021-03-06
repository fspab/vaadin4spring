/*
 * Copyright 2014 The original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.vaadin.spring.internal;

import com.vaadin.server.*;
import com.vaadin.ui.UI;
import com.vaadin.util.CurrentInstance;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;
import org.vaadin.spring.VaadinUI;

import javax.servlet.ServletException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Subclass of the standard {@link com.vaadin.server.VaadinServlet Vaadin servlet} that registers information
 * about the current Vaadin {@link com.vaadin.ui.UI} in a thread-local
 * for the custom {@link org.vaadin.spring.internal.VaadinUIScope scope}.
 *
 * @author Petter Holmström (petter@vaadin.com)
 * @author Josh Long (josh@joshlong.com)
 */
public class SpringAwareVaadinServlet extends VaadinServlet {

    @Override
    protected void servletInitialized() throws ServletException {
        getService().addSessionInitListener(new SessionInitListener() {
            @Override
            public void sessionInit(SessionInitEvent sessionInitEvent) throws ServiceException {
                WebApplicationContext webApplicationContext = WebApplicationContextUtils.getWebApplicationContext(getServletContext());
                UIScopeAwareUIProvider uiProvider = new UIScopeAwareUIProvider(webApplicationContext);
                sessionInitEvent.getSession().addUIProvider(uiProvider);
            }
        });
    }

    /**
     * Vaadin {@link UIProvider} that looks up UI classes from the application context. The UI
     * classes must be annotated with {@link VaadinUI}.
     */
    static class UIScopeAwareUIProvider extends UIProvider {

        private final Log logger = LogFactory.getLog(getClass());
        private final WebApplicationContext webApplicationContext;
        private final Map<String, Class<? extends UI>> pathToUIMap = new ConcurrentHashMap<>();

        public UIScopeAwareUIProvider(WebApplicationContext webApplicationContext) {
            this.webApplicationContext = webApplicationContext;
            detectUIs();
        }

        private void detectUIs() {
            logger.info("Checking the application context for Vaadin UIs");
            final String[] uiBeanNames = webApplicationContext.getBeanNamesForAnnotation(VaadinUI.class);
            for (String uiBeanName : uiBeanNames) {
                Class<?> beanType = webApplicationContext.getType(uiBeanName);
                if (UI.class.isAssignableFrom(beanType)) {
                    logger.info(String.format("Found Vaadin UI [%s]", beanType.getCanonicalName()));
                    final String path = beanType.getAnnotation(VaadinUI.class).path();
                    Class<? extends UI> existingBeanType = pathToUIMap.get(path);
                    if (existingBeanType != null) {
                        throw new IllegalStateException(String.format("[%s] is already mapped to the path [%s]", existingBeanType.getCanonicalName(), path));
                    }
                    logger.debug("Mapping Vaadin UI [" + beanType.getCanonicalName() + "] to path [" + path + "]");
                    pathToUIMap.put(path, (Class<? extends UI>) beanType);
                }
            }
            if (pathToUIMap.isEmpty()) {
                logger.warn("Found no Vaadin UIs in the application context");
            }
        }

        @Override
        public Class<? extends UI> getUIClass(UIClassSelectionEvent uiClassSelectionEvent) {
            final String path = extractUIPathFromPathInfo(uiClassSelectionEvent.getRequest().getPathInfo());
            return pathToUIMap.get(path);
        }

        private String extractUIPathFromPathInfo(String pathInfo) {
            if (pathInfo != null && pathInfo.length() > 1) {
                String path = pathInfo;
                final int indexOfBang = path.indexOf('!');
                if (indexOfBang > -1) {
                    path = path.substring(0, indexOfBang - 1);
                }

                if (path.endsWith("/")) {
                    path = path.substring(0, path.length() - 1);
                }
                return path;
            }
            return "";
        }

        @Override
        public UI createInstance(UICreateEvent event) {
            final Class<VaadinUIIdentifier> key = VaadinUIIdentifier.class;
            final VaadinUIIdentifier identifier = new VaadinUIIdentifier(event);
            CurrentInstance.set(key, identifier);
            try {
                logger.debug(String.format("Creating a new UI bean of class [%s] with identifier [%s]", event.getUIClass().getCanonicalName(), identifier));
                return webApplicationContext.getBean(event.getUIClass());
            } finally {
                CurrentInstance.set(key, null);
            }
        }
    }
}
