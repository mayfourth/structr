/*
 *  Copyright (C) 2012 Axel Morgner
 * 
 *  This file is part of structr <http://structr.org>.
 * 
 *  structr is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 * 
 *  structr is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 * 
 *  You should have received a copy of the GNU General Public License
 *  along with structr.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.structr.server;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.Map.Entry;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.DispatcherType;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.NCSARequestLog;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.RequestLogHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.server.ssl.SslSelectChannelConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlets.GzipFilter;
import org.eclipse.jetty.util.resource.JarResource;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.webapp.WebAppContext;
import org.structr.context.ApplicationContextListener;
import org.structr.core.auth.Authenticator;
import org.structr.rest.ResourceProvider;
import org.structr.rest.servlet.JsonRestServlet;
import org.tuckey.web.filters.urlrewrite.UrlRewriteFilter;

/**
 *
 * @author Christian Morgner
 */
public class Structr {
	
	private static final Logger logger          = Logger.getLogger(Structr.class.getName());
	
	private String applicationName              = "structr server";
	private String restUrl                      = "/structr/rest";
	
	private String host                         = null;
	private String keyStorePath                 = null;
	private String keyStorePassword             = null;
	private String contextPath                  = System.getProperty("contextPath", "/");
	private String basePath                     = "";

	private int restPort                        = -1;
	private int httpsPort                       = -1;
	
	private int maxIdleTime                     = Integer.parseInt(System.getProperty("maxIdleTime", "30000"));
	private int requestHeaderSize               = Integer.parseInt(System.getProperty("requestHeaderSize", "8192"));

	private Map<String, ServletHolder> servlets = new LinkedHashMap<String, ServletHolder>();

	private boolean enableRewriteFilter         = false;
	private boolean quiet                       = false;
	private boolean enableHttps                 = false;
	private boolean enableGzipCompression       = true;
	
	private Class<? extends StructrServer> app  = null;
	private Class resourceProvider              = null;
	private Class authenticator                 = null;
	
	//~--- methods --------------------------------------------------------

	private Structr(Class<? extends StructrServer> applicationClass, String applicationName, int httpPort, int httpsPort) {
		this.app = applicationClass;
		this.applicationName = applicationName;
		this.httpsPort = httpsPort;
		this.restPort = httpPort;
	}
	
	private Structr(Class<? extends StructrServer> applicationClass, String applicationName, int httpPort) {
		this.app = applicationClass;
		this.applicationName = applicationName;
		this.restPort = httpPort;
	}
	
	private Structr(Class<? extends StructrServer> applicationClass, String applicationName) {
		this.app = applicationClass;
		this.applicationName = applicationName;
	}
	
	private Structr(Class<? extends StructrServer> applicationClass) {
		this.app = applicationClass;
	}
	
	public static Structr createServer(Class<? extends StructrServer> applicationClass, String applicationName, int httpPort, int httpsPort) {
		return new Structr(applicationClass, applicationName, httpPort, httpsPort);
	}
	
	public static Structr createServer(Class<? extends StructrServer> applicationClass, String applicationName, int httpPort) {
		return new Structr(applicationClass, applicationName, httpPort);
	}
	
	public static Structr createServer(Class<? extends StructrServer> applicationClass, String applicationName) {
		return new Structr(applicationClass, applicationName);
	}
	
	public static Structr createServer(Class<? extends StructrServer> applicationClass) {
		return new Structr(applicationClass);
	}

	// ----- builder methods -----
	public Structr host(String host) {
		this.host = host;
		return this;
	}
	
	public Structr restUrl(String restUrl) {
		this.restUrl = restUrl;
		return this;
	}
	
	public Structr keyStorePath(String keyStorePath) {
		this.keyStorePath = keyStorePath;
		return this;
	}
	
	public Structr keyStorePassword(String keyStorePassword) {
		this.keyStorePassword = keyStorePassword;
		return this;
	}
	
	public Structr contextPath(String contextPath) {
		this.contextPath = contextPath;
		return this;
	}
	
	public Structr basePath(String basePath) {
		this.basePath = basePath;
		return this;
	}
	
	public Structr httpPort(int httpPort) {
		this.restPort = httpPort;
		return this;
	}
	
	public Structr httpsPort(int httpsPort) {
		this.httpsPort = httpsPort;
		return this;
	}
	
	public Structr resourceProvider(Class<? extends ResourceProvider> resourceProviderClass) {
		this.resourceProvider = resourceProviderClass;
		return this;
	}
	
	public Structr authenticator(Class<? extends Authenticator> authenticatorClass) {
		this.authenticator = authenticatorClass;
		return this;
	}

	public Structr addServlet(String servletMapping, ServletHolder servletHolder) {
		servlets.put(servletMapping, servletHolder);
		return this;
	}
	
	/**
	 * Enable url rewrite filter (disabled by default). Put urlrewrite.xml in the
	 * root of your JAR file.
	 * 
	 * @return 
	 */
	public Structr enableRewriteFilter() {
		this.enableRewriteFilter = true;
		return this;
	}
	
	/**
	 * Disable GZIP compression for this structr server (enabled by default).
	 * @return 
	 */
	public Structr disableGzipCompression() {
		this.enableGzipCompression = false;
		return this;
	}
	
	/**
	 * Start the structr server with the previously specified configuration.
	 * 
	 * @throws IOException
	 * @throws InterruptedException
	 * @throws Exception 
	 */
	public Server start(boolean waitForExit) throws IOException, InterruptedException, Exception {
		
		String sourceJarName                 = app.getProtectionDomain().getCodeSource().getLocation().toString();
		File baseDir                         = new File(System.getProperty("home", basePath));
		String basePath                      = baseDir.getAbsolutePath();
		File confFile                        = checkStructrConf(basePath, sourceJarName);
		Properties configuration             = getConfiguration(confFile);

		checkPrerequisites(configuration);
		
		Server server                        = new Server(restPort);
		List<Connector> connectors           = new LinkedList<Connector>();
		HandlerCollection handlerCollection  = new HandlerCollection();
		boolean startingFromWARFile          = false;
		ServletContextHandler servletContext = null;

		// support for WAR files and JARs
		if (sourceJarName.endsWith(".war")) {

			WebAppContext webAppContext = new WebAppContext(server, basePath + "/" + sourceJarName, contextPath);
			// webAppContext.setDescriptor(webAppContext + "/WEB-INF/web.xml");
			webAppContext.setWar(basePath + sourceJarName);
			
			servletContext = webAppContext;
			
			startingFromWARFile = true;
			
		} else {
			
			servletContext = new ServletContextHandler(server, contextPath, ServletContextHandler.SESSIONS);
		}
			
		// create resource collection from base path & source JAR
		servletContext.setBaseResource(new ResourceCollection(Resource.newResource(basePath), JarResource.newJarResource(Resource.newResource(sourceJarName))));
		servletContext.setInitParameter("configfile.path", confFile.getAbsolutePath());

		if (enableGzipCompression) {

			FilterHolder gzipFilter = servletContext.addFilter(GzipFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST, DispatcherType.FORWARD));
			gzipFilter.setInitParameter("mimeTypes", "text/html,text/plain,text/css,text/javascript");

		}
		
		if (enableRewriteFilter) {
			
			FilterHolder rewriteFilter = servletContext.addFilter(UrlRewriteFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST, DispatcherType.FORWARD));
			
			if (startingFromWARFile) {
				
				rewriteFilter.setInitParameter("confPath", "/WEB-INF/urlrewrite.xml");
				
			} else {
				
				rewriteFilter.setInitParameter("confPath", "/urlrewrite.xml");
			}
		}
		
		// enable request logging
		if ("true".equals(configuration.getProperty("log.requests", "false"))) {

			String logFile                      = configuration.getProperty("log.name", "structr-yyyy_mm_dd.request.log");
			RequestLogHandler requestLogHandler = new RequestLogHandler();
			String logPath                      = basePath + "/logs";
			File logDir                         = new File(logPath);

			// Create logs directory if not existing
			if (!logDir.exists()) {

				logDir.mkdir();

			}

			logPath = logDir.getAbsolutePath();

			NCSARequestLog requestLog = new NCSARequestLog(logPath + "/" + logFile);

			requestLog.setRetainDays(90);
			requestLog.setAppend(true);
			requestLog.setExtended(false);
			requestLog.setLogTimeZone("GMT");
			requestLogHandler.setRequestLog(requestLog);
			
			// add log handler
			handlerCollection.addHandler(requestLogHandler);
		}

		// configure JSON REST servlet
		JsonRestServlet structrRestServlet     = new JsonRestServlet();
		ServletHolder structrRestServletHolder = new ServletHolder(structrRestServlet);
		Map<String, String> structrRestParams  = new HashMap<String, String>();

		structrRestParams.put("PropertyFormat", "FlatNameValue");
		structrRestParams.put("ResourceProvider", resourceProvider.getName());
		structrRestParams.put("Authenticator", authenticator.getName());
		structrRestParams.put("IdProperty", "uuid");

		structrRestServletHolder.setInitParameters(structrRestParams);
		structrRestServletHolder.setInitOrder(0);

		// add to servlets
		servlets.put(restUrl + "/*", structrRestServletHolder);

		// add servlet elements
		int position = 1;
		for (Entry<String, ServletHolder> servlet : servlets.entrySet()) {
			
			String path                 = servlet.getKey();
			ServletHolder servletHolder = servlet.getValue();
			
			servletHolder.setInitOrder(position++);
			
			logger.log(Level.INFO, "Adding servlet {0} for {1}", new Object[] { servletHolder, path } );
			
			servletContext.addServlet(servletHolder, path);
		}
		
		// register structr application context listener
		servletContext.addEventListener(new ApplicationContextListener());
		handlerCollection.addHandler(servletContext);
		
		server.setHandler(handlerCollection);
		
		// HTTPs can be disabled
		if (enableHttps) {
			
			if (httpsPort > -1 && keyStorePath != null && !keyStorePath.isEmpty() && keyStorePassword != null) {

				// setup HTTP connector
				SslSelectChannelConnector httpsConnector = null;
				SslContextFactory factory = new SslContextFactory(keyStorePath);

				factory.setKeyStorePassword(keyStorePassword);

				httpsConnector = new SslSelectChannelConnector(factory);

				httpsConnector.setHost(host);

				httpsConnector.setPort(httpsPort);
				httpsConnector.setMaxIdleTime(maxIdleTime);
				httpsConnector.setRequestHeaderSize(requestHeaderSize);

				connectors.add(httpsConnector);

			} else {

				logger.log(Level.WARNING, "Unable to configure SSL, please make sure that application.https.port, application.keystore.path and application.keystore.password are set correctly in structr.conf.");
			}
		}

		if (host != null && !host.isEmpty() && restPort > -1) {

			SelectChannelConnector httpConnector = new SelectChannelConnector();

			httpConnector.setHost(host);
			httpConnector.setPort(restPort);
			httpConnector.setMaxIdleTime(maxIdleTime);
			httpConnector.setRequestHeaderSize(requestHeaderSize);

			connectors.add(httpConnector);

		} else {

			logger.log(Level.WARNING, "Unable to configure REST port, please make sure that application.host, application.rest.port and application.rest.path are set correctly in structr.conf.");
		}
		
		if (!connectors.isEmpty()) {

			server.setConnectors(connectors.toArray(new Connector[0]));
			
		} else {
			
			logger.log(Level.SEVERE, "No connectors configured, aborting.");
			System.exit(0);
		}
		
		server.setGracefulShutdown(1000);
		server.setStopAtShutdown(true);

		if (!quiet) {
			
			System.out.println();
			System.out.println("Starting " + applicationName + " (host=" + host + ":" + restPort + ", maxIdleTime=" + maxIdleTime + ", requestHeaderSize=" + requestHeaderSize + ")");
			System.out.println("Base path " + basePath);
			System.out.println();
			System.out.println(applicationName + " started:        http://" + host + ":" + restPort + restUrl);
			System.out.println();
		}
		
		server.start();

		// The jsp directory is created by the container, but we don't need it
		removeDir(basePath, "jsp");
		
		if (waitForExit) {
			
			server.join();
		
			if (!quiet) {

				System.out.println();
				System.out.println(applicationName + " stopped.");
				System.out.println();
			}
		}
		
		return server;
	}

	private File checkStructrConf(String basePath, String sourceJarName) throws IOException {

		// create and register config file
		String confPath = basePath + "/structr.conf";
		File confFile   = new File(confPath);

		// Create structr.conf if not existing
		if (!confFile.exists()) {

			// synthesize a config file
			List<String> config = new LinkedList<String>();

			config.add("##################################");
			config.add("# structr global config file     #");
			config.add("##################################");
			config.add("");
			
			if (sourceJarName.endsWith(".jar") || sourceJarName.endsWith(".war")) {
				
				config.add("# resources");
				config.add("resources = " + sourceJarName);
				config.add("");
			}
			
			config.add("# JSON output nesting depth");
			config.add("json.depth = 1");
			config.add("");
			config.add("# base directory");
			config.add("base.path = " + basePath);
			config.add("");
			config.add("# temp files directory");
			config.add("tmp.path = /tmp");
			config.add("");
			config.add("# database files directory");
			config.add("database.path = " + basePath + "/db");
			config.add("");
			config.add("# binary files directory");
			config.add("files.path = " + basePath + "/files");
			config.add("");
			config.add("# REST server settings");
			config.add("application.host = 0.0.0.0");
			config.add("application.rest.port = 8082");
			config.add("application.rest.path = /structr/rest");
			config.add("");
			config.add("application.https.enabled = false");
			config.add("application.https.port = ");
			config.add("application.keystore.path = ");
			config.add("application.keystore.password = ");
			config.add("");
			config.add("# SMPT settings");
			config.add("smtp.host = localhost");
			config.add("smtp.port = 25");
			config.add("");
			config.add("superuser.username = superadmin");
			config.add("superuser.password = " + RandomStringUtils.randomAlphanumeric(12));    // Intentionally, no default password here
			config.add("");
			config.add("# services");
			config.add("configured.services = ModuleService NodeService AgentService");
			config.add("");
			config.add("log.requests = false");
			config.add("log.name = structr-yyyy_mm_dd.request.log");

			confFile.createNewFile();
			FileUtils.writeLines(confFile, "UTF-8", config);
		}
		
		return confFile;
	}

	private Properties getConfiguration(File confFile)  {
		
		Properties props    = new Properties();
		FileInputStream fis = null;

		try {
			fis = new FileInputStream(confFile);
			props.load(fis);
			
		} catch(IOException ioex) {
			
			logger.log(Level.WARNING, "Unable to load settings from structr.conf");
			
		} finally {
			
			if (fis != null) {
				
				try { fis.close(); } catch(Throwable t) {}
			}
		}

		
		return props;
	}

	private void checkPrerequisites(Properties configuration) {
		
		host             = configuration.getProperty("application.host", "0.0.0.0");
		restUrl          = configuration.getProperty("application.rest.path", "/structr/rest");
		restPort         = parseInt(configuration.getProperty("application.rest.port", "8082"), -1);
		httpsPort        = parseInt(configuration.getProperty("application.https.port", "-1"), -1);
		enableHttps      = parseBoolean(configuration.getProperty("application.https.enabled", "false"), false);
		
		keyStorePath     = configuration.getProperty("application.keystore.path", "");
		keyStorePassword = configuration.getProperty("application.keystore.password", "");
		
		if (authenticator == null) {
			
			logger.log(Level.WARNING, "Using default authenticator.");
			
			authenticator = DefaultAuthenticator.class;
		}
		
		if (resourceProvider == null) {
			
			logger.log(Level.WARNING, "Using default resource provider.");
			
			resourceProvider = DefaultResourceProvider.class;
		}
		
		if (!restUrl.startsWith("/")) {
			
			logger.log(Level.WARNING, "Prepending missing '/' to rest URL");
			
			restUrl = "/".concat(restUrl);
		}
	}
	
	private void removeDir(final String basePath, final String directoryName) throws IOException {

		String strippedBasePath = StringUtils.stripEnd(basePath, "/");
		
		File file = new File(strippedBasePath + "/" + directoryName);

		if (file.isDirectory()) {

			FileUtils.deleteDirectory(file);
			
		} else {

			file.delete();
		}
	}
	
	private int parseInt(Object source, int defaultValue) {
		
		try { return Integer.parseInt(source.toString()); } catch(Throwable ignore) {}
		
		return defaultValue;
	}
	
	private boolean parseBoolean(Object source, boolean defaultValue) {
		
		try { return Boolean.parseBoolean(source.toString()); } catch(Throwable ignore) {}
		
		return defaultValue;
	}
}