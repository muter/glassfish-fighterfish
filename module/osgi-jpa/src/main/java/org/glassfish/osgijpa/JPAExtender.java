/*
 * Copyright (c) 2009, 2018 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.osgijpa;

import org.glassfish.osgijavaeebase.Extender;
import org.osgi.framework.*;
import org.osgi.service.packageadmin.PackageAdmin;
import org.osgi.util.tracker.BundleTracker;
import org.osgi.util.tracker.ServiceTracker;

import java.io.*;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * An extender that listens for Persistence bundle's life cycle events
 * and takes appropriate actions.
 *
 * @author Sanjeeb.Sahoo@Sun.COM
 */
public class JPAExtender implements Extender, SynchronousBundleListener {
    private Logger logger = Logger.getLogger(JPAExtender.class.getPackage().getName());
    private BundleContext context;
    private final ServiceTracker tracker;
    private Map<Long, JPABundleProcessor> bundlesToBeEnhanced =
            Collections.synchronizedMap(new HashMap<Long, JPABundleProcessor>());
    ExecutorService executorService;
    private static final String PERSISTENT_STATE = "jpa-extender-state";

    /**
     * Whether enhancement happens in the synchronous bundle listener thread or not.
     * Sometimes, we may run into potential locking issues if we synchronously enhance
     * , as enhancement involves changing bundle state either from INSTALLED to RESOLVED.
     */
    private enum EnhancerPolicy {
        SYNCHRONOUS, // enhancement happens in same thread as bundle listener
        ASYNCHRONOUS // enhancement happens in a separate thread than then bundle listener
    }

    private EnhancerPolicy enhancerPolicy = EnhancerPolicy.SYNCHRONOUS;

    private static final String ENHANCER_POLICY_KEY = "org.glassfish.osgijpa.enhancerPolicy";

    public JPAExtender(BundleContext context) {
        this.context = context;
        this.tracker = new ServiceTracker(context, PackageAdmin.class.getName(), null);
        tracker.open();
    }

    public void start() {
        String value = context.getProperty(ENHANCER_POLICY_KEY);
        if (value != null) {
            enhancerPolicy = EnhancerPolicy.valueOf(value);
        }
        context.addBundleListener(this);
        executorService = Executors.newSingleThreadExecutor();
        restoreState();
        logger.logp(Level.FINE, "JPAExtender", "start", " JPAExtender started", new Object[]{});
    }

    public void stop() {
        context.removeBundleListener(this);
        executorService.shutdownNow();
        saveState();
        logger.logp(Level.FINE, "JPAExtender", "stop", " JPAExtender stopped", new Object[]{});
    }

    public void bundleChanged(BundleEvent event) {
        final Bundle bundle = event.getBundle();
        switch (event.getType()) {
            case BundleEvent.INSTALLED:
            case BundleEvent.UPDATED: {
                final JPABundleProcessor bi = new JPABundleProcessor(bundle);
                if (!bi.isEnhanced() && bi.isJPABundle()) {
                    logger.logp(Level.INFO, "JPAExtender", "bundleChanged", "Bundle having id {0} is a JPA bundle",
                            new Object[]{bundle.getBundleId()});
                    final Runnable runnable = new Runnable() {
                        public void run() {
                            if (tryResolve(bundle)) {
                                // don't refreshPackages. See GLASSFISH-16754 for kind of ripple effect that
                                // can occur because of refreshPackages even when there are no other dependencies
                                // for a bundle. More over, since we are enhacing at installation time,
                                // I don't see how any other bundle would have used our packages unless user installs
                                // bundles using multiple threads.
                                // In such a case, they can always call refreshPackages themselves after installing
                                // a jpa bundle.
                                enhance(bi, false);
                            } else {
                                logger.log(Level.INFO, "Bundle having id {0} can't be resolved now, " +
                                        "so adding to a list so that we can enhance it when it gets resolved in future",
                                        new Object[]{bundle.getBundleId()});
                                bundlesToBeEnhanced.put(bi.getBundleId(), bi);
                            }
                        }
                    };
                    executeTask(runnable, enhancerPolicy);
                }
                break;
            }
            case BundleEvent.STARTED: {
                long id = bundle.getBundleId();
                final JPABundleProcessor bi = bundlesToBeEnhanced.remove(id);
                if (bi != null) {
                    final Runnable runnable = new Runnable() {
                        public void run() {
                            enhance(bi, false); // see issue 15189 to know why we pass false
                        }
                    };
                    // Always do it asynchronously since the bundle is already started.
                    executeTask(runnable, EnhancerPolicy.ASYNCHRONOUS);
                }
                break;
            }
            case BundleEvent.UNINSTALLED: {
                long id = bundle.getBundleId();
                bundlesToBeEnhanced.remove(id);
                break;
            }
            default:
                break;
        }
    }

    private PackageAdmin getPackageAdmin() {
        return PackageAdmin.class.cast(tracker.getService());
    }

    private void enhance(JPABundleProcessor bi, boolean refreshPackage) {
        try {
            Bundle bundle = bi.getBundle();
            InputStream enhancedStream = bi.enhance();
            updateBundle(bundle, enhancedStream);
            if (refreshPackage) {
                getPackageAdmin().refreshPackages(new Bundle[]{bundle});
            } else {
                logger.logp(Level.INFO, "JPAExtender", "enhance",
                        "Deferring refresh to framework restart, " +
                                "so enhanced bytes won't come into effect until then for bundle " + bi.getBundleId() +
                                " if there are existing wires to this bundle.");
            }
        } catch (Exception e) {
            logger.logp(Level.WARNING, "JPAExtender", "enhance", "Failed to enhance bundle having id " + bi.getBundleId(), e);
        }
    }

    private void updateBundle(final Bundle bundle, InputStream enhancedStream) throws BundleException {
        try {
            bundle.update(enhancedStream);
        } finally {
            try {
                enhancedStream.close();
            } catch (IOException e) {
            }
        }
    }

    private void executeTask(Runnable runnable, EnhancerPolicy enhancerPolicy) {
        switch(enhancerPolicy) {
            case SYNCHRONOUS:
                runnable.run();
                break;
            case ASYNCHRONOUS:
                executorService.submit(runnable);
        }
    }

    private boolean tryResolve(Bundle bundle) {
        return getPackageAdmin().resolveBundles(new Bundle[]{bundle});
    }

    private void restoreState() {
        File baseDir = context.getDataFile("");
        if (baseDir == null) return;
        File state = new File(baseDir, PERSISTENT_STATE);
        if (!state.exists()) {
            return;
        }
        ObjectInputStream stream = null;
        try {
            stream = new ObjectInputStream(new BufferedInputStream(new FileInputStream(state)));
            bundlesToBeEnhanced = (Map<Long, JPABundleProcessor>) stream.readObject();
            logger.logp(Level.INFO, "JPAExtender", "restoreState", "Restored state from {0} and " +
                    "following bundles are yet to be enhanced: {1} ", new Object[]{state.getAbsolutePath(), printBundleIds()});
        } catch (Exception e) {
            logger.logp(Level.WARNING, "JPAExtender", "restoreState", "Unable to read stored data. " +
                    "Will continue with an empty initial state. If you have bundles that were installed earlier and " +
                    "have not been enhanced yet, please update those bundles.", e);
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e) {
                }
            }
        }
    }

    private void saveState() {
        if (bundlesToBeEnhanced.isEmpty()) return;
        File baseDir = context.getDataFile("");
        if (baseDir == null) return;
        File state = new File(baseDir, PERSISTENT_STATE);
        if (state.exists()) state.delete();
        ObjectOutputStream stream = null;
        try {
            stream = new ObjectOutputStream(new BufferedOutputStream(new FileOutputStream(state)));
            stream.writeObject(bundlesToBeEnhanced);
            logger.logp(Level.INFO, "JPAExtender", "saveState", "Saved state to {0} and " +
                    "following bundles are yet to be enhanced: {1} ", new Object[]{state.getAbsolutePath(), printBundleIds()});
        } catch (Exception e) {
            logger.logp(Level.WARNING, "JPAExtender", "saveState", "Unable to store data. " +
                    "If you have intalled bundles that are yet to be enhanced, they won't be enhanced" +
                    " next time when server starts unless you update those bundles.", e);

        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e) {
                }
            }
        }
    }

    private StringBuilder printBundleIds() {
        StringBuilder sb = new StringBuilder();
        for (long id : bundlesToBeEnhanced.keySet()) {
            sb.append(id).append(" ");
        }
        return sb;
    }

}
