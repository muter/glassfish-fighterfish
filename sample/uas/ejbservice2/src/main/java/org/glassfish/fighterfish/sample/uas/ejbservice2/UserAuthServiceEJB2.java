/*
 * Copyright (c) 2011, 2018 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0, which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.glassfish.fighterfish.sample.uas.ejbservice2;

import org.glassfish.fighterfish.sample.uas.api.UserAuthService;
import org.glassfish.fighterfish.sample.uas.entities.LoginAttempt;
import org.glassfish.fighterfish.sample.uas.entities.UserCredential;
import org.glassfish.osgicdi.OSGiService;

import javax.ejb.Local;
import javax.ejb.Stateless;
import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import java.util.List;

/**
 * Session Bean implementation class UserAuthServiceEJB2
 */
@Stateless
@Local({UserAuthService.class})
public class UserAuthServiceEJB2 implements UserAuthService {

    @Inject
    @OSGiService(dynamic = true, serviceCriteria = "(persistence-unit=sample.uas.entities)")
    private EntityManagerFactory emf;

    /**
     * Default constructor.
     */
    public UserAuthServiceEJB2() {
        // TODO Auto-generated constructor stub
    }

    @Override
    public boolean login(String name, String password) {
        log("Logging in (" + name + ", " + password + ")");
        EntityManager em = emf.createEntityManager();
        try {
            UserCredential uc = em.find(UserCredential.class, name);
            boolean result = (uc != null && password.equals(uc.getPassword()));
            if (uc != null) {
                // create LoginAttempt only for existing users.
                LoginAttempt attempt = new LoginAttempt();
                attempt.setSuccessful(result);
                attempt.setUserCredential(uc);
                // set both sides of relationships because stupid JPA providers don't even update their second level cache
                // with relationships in database.
                uc.getLoginAttempts().add(attempt);
                em.persist(attempt);
            }
            return result;
        } finally {
            em.close();
        }
    }

    @Override
    public boolean register(String name, String password) {
        log("Registering (" + name + ", " + password + ")");
        EntityManager em = emf.createEntityManager();
        try {
            UserCredential uc = em.find(UserCredential.class, name);
            if (uc != null) return false;
            uc = new UserCredential();
            uc.setName(name);
            uc.setPassword(password);
            em.persist(uc);
            return true;
        } finally {
            em.close();
        }
    }

    @Override
    public boolean unregister(String name) {
        log("Unregistering (" + name + ")");
        EntityManager em = emf.createEntityManager();
        try {
            UserCredential uc = em.find(UserCredential.class, name);
            if (uc == null) return false;
            em.remove(uc);
            return true;
        } finally {
            em.close();
        }
    }

    @Override
    public String getReport() {
        EntityManager em = emf.createEntityManager();
        try {
            List<LoginAttempt> attempts = em.createNamedQuery("LoginAttempt.findAll").getResultList();
            log("Number of entries found: " + attempts.size());
            StringBuilder report = new StringBuilder("Login Attempt Report:\n");
            for (LoginAttempt attempt : attempts) {
                report.append(attempt).append("\n");
            }
            return report.toString();
        } finally {
            em.close();
        }
    }

    private void log(String msg) {
        System.out.println("UserAuthServiceEJB2: " + msg);
    }
}
