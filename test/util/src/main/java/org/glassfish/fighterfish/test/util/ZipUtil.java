/*
 * Copyright (c) 2011, 2018 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.fighterfish.test.util;

import org.osgi.framework.BundleContext;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A simple utility to extract a zip input stream. This is used to install GlassFish when user does not have
 * an installation.
 *
 * @author sanjeeb.sahoo@oracle.com
 */
public class ZipUtil {

    private static Logger logger = Logger.getLogger(ZipUtil.class.getPackage().getName());

    private static boolean needToExplode(File dest) throws Exception {
        if (new File(dest, "glassfish3").isDirectory()) {
            return false;
        }
        if (dest.isFile()) {
            throw new Exception(dest.getAbsolutePath() + " is a file");
        }
        return true;
    }

    public static void explode(URI in, File out) throws Exception {
        assert (in != null);
        logger.entering("ZipUtil", "explode", new Object[]{in, out});
        if (!needToExplode(out)) {
            logger.logp(Level.FINE, "ZipUtil", "explode", "Skipping exploding at {0}", new Object[]{out});
        }
        if (in != null) {
            ZipInputStream zis = new ZipInputStream(in.toURL().openStream());
            try {
                extractZip(zis, out);
            } finally {
                zis.close();
            }
        }
    }

    public static void extractZip(ZipInputStream zis, File destDir) throws IOException {
        logger.logp(Level.FINE, "ZipUtil", "extractZip", "destDir = {0}", new Object[]{destDir});
        ZipEntry ze;
        int n = 0;
        int size = 0;
        while ((ze = zis.getNextEntry()) != null) {
            logger.logp(Level.FINER, "ZipUtil", "extractZip", "ZipEntry name = {0}, size = {1}", new Object[]{ze.getName(), ze.getSize()});
            java.io.File f = new java.io.File(destDir + java.io.File.separator + ze.getName());
            if (ze.isDirectory()) {
                if (!f.exists()) {
                    if (!f.mkdirs()) {
                        throw new IOException("Unable to create dir " + f.getAbsolutePath());
                    }
                } else if (f.isFile()) { // exists, but not a file. not sure how this can happen
                    throw new IOException("Unable to create dir " + f.getAbsolutePath() + " because it already exists as a file.");
                }
                continue;
            } else if (f.exists()) {
                continue;
            }
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(f);
                int totalcount = 0;
                int count = 0;
                byte[] buffer = new byte[8192];
                while ((count = zis.read(buffer, 0, buffer.length)) != -1) {
                    fos.write(buffer, 0, count);
                    totalcount += count;
                }
                logger.logp(Level.FINER, "ZipUtil", "extractZip", "totalcount for this zip entry = {0}", new Object[]{totalcount});
            } finally {
                try {
                    if (fos != null) {
                        fos.close();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            zis.closeEntry();
            n++;
            size += ze.getSize();
        }
        logger.logp(Level.INFO, "ZipUtil", "extractZip", "Extracted {0} of entries of total size {1} bytes to {2}.",
                new Object[]{n, size, destDir.getAbsolutePath()});
    }
}
