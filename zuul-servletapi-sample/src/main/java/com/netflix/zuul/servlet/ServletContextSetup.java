package com.netflix.zuul.servlet;

import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import java.util.Set;

/**
 * User: Mike Smith
 * Date: 12/12/15
 * Time: 3:49 PM
 */
public class ServletContextSetup implements ServletContainerInitializer
{
    @Override
    public void onStartup(Set<Class<?>> set, ServletContext sc) throws ServletException
    {
        // Register the fiber-blocking Jersey servlet
        javax.servlet.ServletRegistration.Dynamic fiber = sc.addServlet("zuul",
                co.paralleluniverse.fibers.jersey.ServletContainer.class);

//        // Add Jersey configuration class
//        fiber.setInitParameter("javax.ws.rs.Application", "testgrp.JerseyApplication");
//
//        // Set packages to be scanned for resources
//        fiber.setInitParameter("jersey.config.server.provider.packages", "testgrp");

        // Don't lazy-load (fail-fast)
        fiber.setLoadOnStartup(1);

        // Support async (needed by fiber-blocking)
        fiber.setAsyncSupported(true);

        // Mapping
        fiber.addMapping("/*");
    }
}
