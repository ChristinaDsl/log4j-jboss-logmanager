/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat Inc., and individual contributors
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

package org.apache.log4j;

import org.apache.log4j.helpers.LogLog;
import org.apache.log4j.or.ObjectRenderer;
import org.apache.log4j.or.RendererMap;
import org.apache.log4j.spi.*;
import org.jboss.logmanager.LogContext;

import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;

import static org.jboss.logmanager.Logger.AttachmentKey;

/**
 * Our replacement for the log4j {@code Hierarchy} class.  We redirect management of the hierarchy
 * completely to the logmanager's log context.
 */
public class Hierarchy implements LoggerRepository, RendererSupport, ThrowableRendererSupport {

    private final AttachmentKey<Logger> loggerKey = new AttachmentKey<Logger>();

    private LoggerFactory defaultFactory;
    boolean emittedNoAppenderWarning = false;
    private Set<HierarchyEventListener> listeners;
    private final RendererMap rendererMap;
    private volatile Logger root;
    private Level thresholdLevel;
    private int thresholdInt;
    private ThrowableRenderer throwableRenderer = null;

    /**
     * Create a new logger hierarchy.
     *
     * @param root The root of the new hierarchy.
     */
    public Hierarchy(Logger root) {
        listeners = new CopyOnWriteArraySet<HierarchyEventListener>();
        this.root = root;
        AppenderHandler.createAndAttach(root);
        this.root.setHierarchy(this);
        defaultFactory = new DefaultCategoryFactory();
        rendererMap = new RendererMap();
    }

    public void addRenderer(Class classToRender, ObjectRenderer or) {
        rendererMap.put(classToRender, or);
    }

    @Override
    public void addHierarchyEventListener(HierarchyEventListener listener) {
        if (!listeners.add(listener)) {
            LogLog.warn("Ignoring attempt to add an existent listener.");
        }
    }

    public void clear() {
        // No-op should
    }

    @Override
    public void emitNoAppenderWarning(Category cat) {
        // No appenders in hierarchy, warn user only once.
        if(!emittedNoAppenderWarning) {
            LogLog.warn("No appenders could be found for logger (" + cat.getName() + ").");
            LogLog.warn("Please initialize the log4j system properly.");
            LogLog.warn("See http://logging.apache.org/log4j/1.2/faq.html#noconfig for more info.");
            emittedNoAppenderWarning = true;
        }
    }

    @Override
    public Logger exists(String name) {
        final org.jboss.logmanager.Logger logger = LogContext.getLogContext().getLoggerIfExists(name);
        return logger == null ? null : logger.getAttachment(loggerKey);
    }

    @Override
    public void setThreshold(String levelStr) {
        Level l = Level.toLevel(levelStr, null);
        if (l != null) {
            setThreshold(l);
        } else {
            LogLog.warn("Could not convert [" + levelStr + "] to Level.");
        }
    }

    @Override
    public void setThreshold(Level l) {
        if (l != null) {
            thresholdInt = l.level;
            thresholdLevel = l;
        }
    }

    @Override
    public void fireAddAppenderEvent(Category logger, Appender appender) {
        for (HierarchyEventListener listener : listeners) {
            listener.addAppenderEvent(logger, appender);
        }
    }

    void fireRemoveAppenderEvent(Category logger, Appender appender) {
        for (HierarchyEventListener listener : listeners) {
            listener.removeAppenderEvent(logger, appender);
        }
    }

    @Override
    public Level getThreshold() {
        return thresholdLevel;
    }

    @Override
    public Logger getLogger(String name) {
        return getLogger(name, defaultFactory);
    }

    @Override
    public Logger getLogger(final String name, LoggerFactory factory) {
        final org.jboss.logmanager.Logger lmLogger = LogContext.getLogContext().getLogger(name);
        Logger logger = lmLogger.getAttachment(loggerKey);
        if (logger != null) {
            return logger;
        }
        logger = factory.makeNewLoggerInstance(name);
        logger.setHierarchy(this);
        final Logger appearingLogger = lmLogger.attachIfAbsent(loggerKey, logger);
        if (appearingLogger != null) {
            updateParents(appearingLogger);
            return appearingLogger;
        }
        updateParents(logger);
        return logger;
    }

    @Override
    public Enumeration getCurrentLoggers() {
        return Collections.enumeration(getLoggers());
    }

    @Override
    public Enumeration getCurrentCategories() {
        return getCurrentLoggers();
    }

    @Override
    public RendererMap getRendererMap() {
        return rendererMap;
    }

    @Override
    public Logger getRootLogger() {
        return root;
    }

    @Override
    public boolean isDisabled(int level) {
        return thresholdInt > level;
    }

    @Deprecated
    public void overrideAsNeeded(String override) {
        // Only logs a warning in log4j implementation
    }

    @Override
    public void resetConfiguration() {
        root.setLevel(Level.DEBUG);
        root.setResourceBundle(null);
        setThreshold(Level.ALL);
        shutdown();
        final Collection<Logger> loggers = getLoggers();
        for (Logger logger : loggers) {
            logger.setLevel(null);
            logger.setAdditivity(true);
            logger.setResourceBundle(null);
        }
        rendererMap.clear();
        throwableRenderer = null;
    }

    @Deprecated
    public void setDisableOverride(String override) {
        // Only logs a warning in log4j implementation
    }

    @Override
    public void setRenderer(Class renderedClass, ObjectRenderer renderer) {
        rendererMap.put(renderedClass, renderer);
    }

    @Override
    public void setThrowableRenderer(final ThrowableRenderer renderer) {
        this.throwableRenderer = renderer;
    }

    @Override
    public ThrowableRenderer getThrowableRenderer() {
        return throwableRenderer;
    }

    @Override
    public void shutdown() {
        final Logger root = getRootLogger();

        // begin by closing nested appenders
        root.closeNestedAppenders();
        final Collection<Logger> loggers = getLoggers();
        for (Logger logger : loggers) {
            logger.closeNestedAppenders();
        }
        root.removeAllAppenders();
        for (Logger logger : loggers) {
            logger.removeAllAppenders();
        }
    }

    private void updateParents(final Logger cat) {
        String name = cat.name;
        int length = name.length();
        boolean parentFound = false;

        // if name = "w.x.y.z", loop thourgh "w.x.y", "w.x" and "w", but not "w.x.y.z"
        for (int i = name.lastIndexOf('.', length - 1); i >= 0; i = name.lastIndexOf('.', i - 1)) {
            String substr = name.substring(0, i);
            final org.jboss.logmanager.Logger lmLogger = LogContext.getLogContext().getLogger(substr);
            cat.parent = lmLogger.getAttachment(loggerKey);
            if (cat.parent != null) {
                parentFound = true;
                break;
            }
        }
        // If we could not find any existing parents, then link with root.
        if (!parentFound) cat.parent = root;
    }

    private Collection<Logger> getLoggers() {
        final LogContext context = LogContext.getLogContext();
        final List<String> currentLoggerNames = context.getLoggingMXBean().getLoggerNames();
        final List<Logger> currentLoggers = new ArrayList<Logger>(currentLoggerNames.size());
        for (String name : currentLoggerNames) {
            final org.jboss.logmanager.Logger lmLogger = context.getLoggerIfExists(name);
            if (lmLogger != null) {
                final Logger logger = lmLogger.getAttachment(loggerKey);
                if (logger != null) {
                    currentLoggers.add(logger);
                }
            }
        }
        return currentLoggers;
    }
}


