/*
 *
 *
 *  Copyright 2013-2015 Netflix, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 * /
 */
package com.netflix.zuul.jetty;


import com.google.inject.AbstractModule;
import com.google.inject.CreationException;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.spi.Message;
import com.google.inject.util.Providers;
import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.config.ConfigurationManager;
import com.netflix.config.DeploymentContext;
import com.netflix.config.DynamicPropertyFactory;
import com.netflix.config.DynamicStringProperty;
import com.netflix.governator.guice.BootstrapBinder;
import com.netflix.governator.guice.BootstrapModule;
import com.netflix.governator.guice.LifecycleInjector;
import com.netflix.governator.lifecycle.LifecycleManager;
import com.netflix.zuul.FilterProcessor;
import com.netflix.zuul.context.ServletSessionContextFactory;
import com.netflix.zuul.context.SessionContextDecorator;
import com.netflix.zuul.context.SessionContextFactory;
import com.netflix.zuul.init.ZuulFiltersModule;
import com.netflix.zuul.jetty.context.SampleSessionContextDecorator;
import com.netflix.zuul.metrics.OriginStatsFactory;
import com.netflix.zuul.monitoring.CounterFactory;
import com.netflix.zuul.monitoring.TracerFactory;
import com.netflix.zuul.origins.OriginManager;
import com.netflix.zuul.plugins.Counter;
import com.netflix.zuul.plugins.MetricPoller;
import com.netflix.zuul.plugins.ServoMonitor;
import com.netflix.zuul.plugins.Tracer;
import com.netflix.zuul.ribbon.RibbonOriginManager;
import com.netflix.zuul.stats.BasicRequestMetricsPublisher;
import com.netflix.zuul.stats.RequestMetricsPublisher;
import com.netflix.zuul.stats.monitoring.MonitorRegistry;
import org.eclipse.jetty.alpn.ALPN;
import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.http2.HTTP2Cipher;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * User: Mike Smith
 * Date: 3/15/15
 * Time: 5:22 PM
 */
public class JettySampleStartServer
{
    private final Logger log = LoggerFactory.getLogger(this.getClass());
    private final static String DEFAULT_APP_NAME = "zuul";

    private JettyServer server;

    public JettySampleStartServer() {
        log.debug("JettySampleStartServer()");
    }

    public void init() throws Exception
    {
        int port = Integer.parseInt(System.getProperty("zuul.port.http", "7001"));
        int httpsPort = Integer.parseInt(System.getProperty("zuul.port.https", "7002"));
        init(port, httpsPort);
    }

    public void init(int port, int httpsPort) throws Exception
    {
        System.out.println("Starting up Zuul...");
        System.out.printf("Using server port=%s\n", port);

        loadProperties();

        Injector injector = LifecycleInjector.bootstrap(JettyServer.class,
                new ZuulBootstrapModule()
        );

        initPlugins();

        server = injector.getInstance(JettyServer.class);
        server.init(port, httpsPort);
        server.start();
    }

    public void shutdown()
    {
        server.shutdown();
    }

    public void loadProperties()
    {
        DeploymentContext deploymentContext = ConfigurationManager.getDeploymentContext();
        if (deploymentContext.getApplicationId() == null) {
            deploymentContext.setApplicationId(DEFAULT_APP_NAME);
        }

        String infoStr = String.format("env=%s, region=%s, appId=%s, stack=%s",
                deploymentContext.getDeploymentEnvironment(), deploymentContext.getDeploymentRegion(),
                deploymentContext.getApplicationId(), deploymentContext.getDeploymentStack());

        System.out.printf("Using deployment context: %s\n", infoStr);

        try {
            ConfigurationManager.loadCascadedPropertiesFromResources(deploymentContext.getApplicationId());
        } catch (Exception e) {
            log.error(String.format("Failed to load properties file: %s.", infoStr), e);
            throw new RuntimeException(e);
        }

    }


    private void initPlugins()
    {
        MonitorRegistry.getInstance().setPublisher(new ServoMonitor());
        MetricPoller.startPoller();
        TracerFactory.initialize(new Tracer());
        CounterFactory.initialize(new Counter());
    }

    public static void main(String[] args)
    {
        try {
            new JettySampleStartServer().init();
        }
        catch (CreationException e) {
            System.err.println("Injection error while starting StartServer. Messages follow:");
            for (Message msg : e.getErrorMessages()) {
                System.err.printf("ErrorMessage: %s, Causes: %s\n", msg.getMessage(), getErrorCauseMessages(e.getCause(), 4));
                if (msg.getCause() != null) {
                    msg.getCause().printStackTrace();
                }
            }
            System.exit(-1);
        } catch (Exception e) {
            System.err.println("Error while starting StartServer. msg=" + e.getMessage());
            e.printStackTrace();
            System.exit(-1);
        }

        // In case we have non-daemon threads running
        System.exit(0);
    }

    private static String getErrorCauseMessages(Throwable error, int depth) {
        String fullMessage = "";
        Throwable cause = error;
        for (int i=0; i<depth; i++) {
            if (cause == null) {
                break;
            }
            fullMessage = fullMessage + String.format("cause%s=\"%s\", ", i, cause.getMessage());
            cause = cause.getCause();
        }
        return fullMessage;
    }


    static class ZuulModule extends AbstractModule
    {
        @Override
        protected void configure()
        {
            bind(ApplicationInfoManager.class).asEagerSingleton();

            // Configure the factory that will create and initialise each requests' SessionContext.
            bind(SessionContextFactory.class).to(ServletSessionContextFactory.class);
            bind(SessionContextDecorator.class).to(SampleSessionContextDecorator.class);
            bind(FilterProcessor.class).asEagerSingleton();

            // Configure the OriginManager and LoadBalancer.
            bind(OriginManager.class).to(RibbonOriginManager.class);
            bind(OriginStatsFactory.class).toProvider(Providers.of(null));


            bind(RequestMetricsPublisher.class).to(BasicRequestMetricsPublisher.class);
        }
    }

    static class ZuulBootstrapModule implements BootstrapModule
    {
        @Override
        public void configure(BootstrapBinder binder)
        {
            binder.include(
                    ZuulModule.class,
                    ZuulFiltersModule.class);
        }
    }

    static class JettyServer
    {
        private final static DynamicStringProperty NETTY_WIRE_LOGLEVEL = DynamicPropertyFactory.getInstance()
                .getStringProperty("zuul.netty.wire.loglevel", "ERROR");

        private final Logger log = LoggerFactory.getLogger(this.getClass());

        private LifecycleManager lifecycleManager;
        private Server server;
        private ApplicationInfoManager applicationInfoManager;

        @Inject
        public JettyServer(LifecycleManager lifecycleManager,
                                         ApplicationInfoManager applicationInfoManager)
        {
            this.lifecycleManager = lifecycleManager;
            this.applicationInfoManager = applicationInfoManager;
        }

        public void init(int port, int httpsPort)
        {
            server = new Server();

            ServletContextHandler context = new ServletContextHandler(server, "/",ServletContextHandler.SESSIONS);
            context.setResourceBase("");
            //context.addFilter(PushCacheFilter.class,"/*", EnumSet.of(DispatcherType.REQUEST));
            context.addServlet(new ServletHolder(new TestServlet()), "/test/*");
            context.addServlet(DefaultServlet.class, "/").setInitParameter("maxCacheSize","81920");
            server.setHandler(context);

            // HTTP Configuration
            HttpConfiguration http_config = new HttpConfiguration();
            http_config.setSecureScheme("https");
            http_config.setSecurePort(httpsPort);
            http_config.setSendXPoweredBy(true);
            http_config.setSendServerVersion(true);

            // HTTP Connector
            ServerConnector http = new ServerConnector(server,new HttpConnectionFactory(http_config));
            http.setPort(port);
            server.addConnector(http);

            // SSL Context Factory for HTTPS and HTTP/2
            SslContextFactory sslContextFactory = new SslContextFactory();
            sslContextFactory.setKeyStorePath("/Users/michaels/Projects/kerumai/zuul/zuul-jetty-sample/conf/keystore.jks");
            sslContextFactory.setKeyStorePassword("netflix");
            sslContextFactory.setKeyManagerPassword("netflix");

            sslContextFactory.setCipherComparator(new HTTP2Cipher.CipherComparator());
            sslContextFactory.setUseCipherSuitesOrder(true);

            // NOTE - Adding this cipher as allowed, because this is what Chrome is negotiating, but is otherwise blacklisted by jetty when used in conjunction with TLSv1.2.
            // See https://dev.eclipse.org/mhonarc/lists/jetty-dev/msg02377.html for some context.
            sslContextFactory.setIncludeCipherSuites("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");

            // HTTPS Configuration
            HttpConfiguration https_config = new HttpConfiguration(http_config);
            https_config.addCustomizer(new SecureRequestCustomizer());

            // HTTP/2 Connection Factory
            HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory(https_config);

            NegotiatingServerConnectionFactory.checkProtocolNegotiationAvailable();

            String defaultProtocol = "h2";
            ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory(
                    defaultProtocol,
                    "h2", "h2-17", "h2-16", "h2-15", "h2-14", "http/1.1", "http/1.0"
            );
            alpn.setDefaultProtocol(defaultProtocol);

            // SSL Connection Factory
            SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory,alpn.getProtocol());

            // HTTP/2 Connector
            ServerConnector http2Connector =
                    new ServerConnector(server,ssl,alpn,h2,new HttpConnectionFactory(https_config));
            http2Connector.setPort(httpsPort);
            server.addConnector(http2Connector);

            ALPN.debug=true;
        }

        public final void start()
        {
            try {
                this.startLifecycleManager();

                server.start();
                server.dumpStdErr();
                server.join();
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }

            // Indicate status is UP. Without explicitly calling this, it gets stuck in STARTING status.
            // TODO - ask platform team what doing wrong.
            applicationInfoManager.setInstanceStatus(InstanceInfo.InstanceStatus.UP);
        }

        protected void startLifecycleManager()
        {
            try {
                this.lifecycleManager.start();
            } catch (Exception var2) {
                throw new RuntimeException(var2);
            }
        }

        public void shutdown()
        {
            if(this.lifecycleManager != null) {
                this.lifecycleManager.close();
            }

            try {
                server.stop();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}

class TestServlet extends HttpServlet
{
    private static final long serialVersionUID = 1L;

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        // Push the css file.
        RequestDispatcher dispatcher = request.getServletContext().getRequestDispatcher("/web/test.css");
        ((Dispatcher)dispatcher).push(request);

        // Forward to the html file.
        RequestDispatcher dispatcherHtml = request.getServletContext().getRequestDispatcher("/web/test.html");
        dispatcherHtml.forward(request, response);
    }
};