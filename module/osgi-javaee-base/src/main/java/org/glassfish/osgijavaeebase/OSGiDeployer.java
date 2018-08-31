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

package org.glassfish.osgijavaeebase;

import com.sun.enterprise.deploy.shared.ArchiveFactory;
import org.glassfish.api.ActionReport;
import org.glassfish.internal.deployment.Deployment;
import org.glassfish.server.ServerEnvironmentImpl;
import org.osgi.framework.Bundle;

/**
 * @author Sanjeeb.Sahoo@Sun.COM
 */
public interface OSGiDeployer {

    OSGiApplicationInfo deploy(Bundle b) throws DeploymentException;

    void undeploy(OSGiApplicationInfo osgiAppInfo) throws DeploymentException;

    boolean handles(Bundle bundle);
}