// Copyright (C) 2012 jOVAL.org.  All rights reserved.
// This software is licensed under the AGPL 3.0 license available at http://www.joval.org/agpl_v3.txt

package org.joval.scap.xccdf.engine;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.security.KeyStore;
import java.text.MessageFormat;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.PropertyResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.joval.intf.plugin.IPlugin;
import org.joval.intf.scap.IScapContext;
import org.joval.intf.scap.arf.IReport;
import org.joval.intf.scap.datastream.IDatastream;
import org.joval.intf.scap.ocil.IChecklist;
import org.joval.intf.scap.xccdf.SystemEnumeration;
import org.joval.intf.scap.xccdf.IEngine.OcilMessageArgument;
import org.joval.intf.util.IObserver;
import org.joval.intf.util.IProducer;
import org.joval.plugin.PluginFactory;
import org.joval.plugin.PluginConfigurationException;
import org.joval.scap.ScapException;
import org.joval.scap.cpe.CpeException;
import org.joval.scap.datastream.DatastreamCollection;
import org.joval.scap.ocil.Checklist;
import org.joval.scap.ocil.OcilException;
import org.joval.scap.oval.OvalException;
import org.joval.scap.xccdf.Benchmark;
import org.joval.scap.xccdf.Bundle;
import org.joval.scap.xccdf.XccdfException;
import org.joval.util.JOVALSystem;
import org.joval.util.LogFormatter;
import org.joval.xml.SignatureValidator;

/**
 * XCCDF Processing Engine and Reporting Tool (XPERT) main class.
 *
 * @author David A. Solin
 * @version %I% %G%
 */
public class XPERT {
    private static File CWD = new File(".");
    private static File BASE_DIR = CWD;
    private static PropertyResourceBundle resources;
    static {
	String s = System.getProperty("xpert.baseDir");
	if (s != null) {
	    BASE_DIR = new File(s);
	}
	try {
	    ClassLoader cl = Thread.currentThread().getContextClassLoader();
	    Locale locale = Locale.getDefault();
	    URL url = cl.getResource("xpert.resources_" + locale.toString() + ".properties");
	    if (url == null) {
		url = cl.getResource("xpert.resources_" + locale.getLanguage() + ".properties");
	    }
	    if (url == null) {
		url = cl.getResource("xpert.resources.properties");
	    }
	    resources = new PropertyResourceBundle(url.openStream());
	    JOVALSystem.setSystemProperty(JOVALSystem.SYSTEM_PROP_PRODUCT, getMessage("product.name"));
	} catch (IOException e) {
	    e.printStackTrace();
	    System.exit(-1);
	}
    }

    static Logger logger;

    static void printHeader(IPlugin plugin) {
	PrintStream console = System.out;
	console.println(getMessage("divider"));
	console.println(getMessage("product.name"));
	console.println(getMessage("description"));
	console.println(getMessage("message.version", JOVALSystem.getSystemProperty(JOVALSystem.SYSTEM_PROP_VERSION)));
	console.println(getMessage("message.buildDate", JOVALSystem.getSystemProperty(JOVALSystem.SYSTEM_PROP_BUILD_DATE)));
	console.println(getMessage("copyright"));
	if (plugin != null) {
	    console.println("");
	    console.println(getMessage("message.plugin.name", plugin.getProperty(IPlugin.PROP_DESCRIPTION)));
	    console.println(getMessage("message.plugin.version", plugin.getProperty(IPlugin.PROP_VERSION)));
	    console.println(getMessage("message.plugin.copyright", plugin.getProperty(IPlugin.PROP_COPYRIGHT)));
	}
	console.println(getMessage("divider"));
	console.println("");
    }

    static void printHelp(IPlugin plugin) {
	System.out.println(getMessage("helpText"));
	if (plugin != null) {
	    System.out.println(plugin.getProperty(IPlugin.PROP_HELPTEXT));
	}
    }

    /**
     * Retrieve a message using its key.
     */
    static String getMessage(String key, Object... arguments) {
	return MessageFormat.format(resources.getString(key), arguments);
    }

    /**
     * Run from the command-line.
     */
    public static void main(String[] argv) {
	boolean help = false;
	File source = new File(CWD, "scap-datastream.xml");
	String streamId = null;
	String benchmarkId = null;
	String profileId = null;
	Map<String, IChecklist> checklists = new HashMap<String, IChecklist>();
	File xmlDir = new File(BASE_DIR, "xml");
	File reportFile = new File(CWD, "xpert-arf.xml");
	File transformFile = new File(xmlDir, "xccdf_results_to_html.xsl");
	File reportHTML = new File("xpert-report.html");
	File ocilDir = new File("ocil-export");

	Level level = Level.INFO;
	boolean query = false, verify = true, verbose = false;
	File logFile = new File(CWD, "xpert.log");
	String pluginName = "default";
	File configFile = new File(CWD, IPlugin.DEFAULT_FILE);
	File ksFile = new File(new File(BASE_DIR, "security"), "cacerts.jks");
	String ksPass = "jOVAL s3cure";

	for (int i=0; i < argv.length; i++) {
	    if (argv[i].equals("-h")) {
		help = true;
	    } else if (argv[i].equals("-q")) {
		query = true;
	    } else if (argv[i].equals("-s")) {
		verify = false;
	    } else if (argv[i].equals("-v")) {
		verbose = true;
	    } else if (argv[i].equals("-n")) {
		checklists.put("", Checklist.EMPTY);
	    } else if ((i + 1) < argv.length) {
		if (argv[i].equals("-d")) {
		    source = new File(argv[++i]);
		} else if (argv[i].equals("-i")) {
		    streamId = argv[++i];
		} else if (argv[i].equals("-b")) {
		    benchmarkId = argv[++i];
		} else if (argv[i].equals("-p")) {
		    profileId = argv[++i];
		} else if (argv[i].equals("-o")) {
		    String pair = argv[++i];
		    int ptr = pair.indexOf("=");
		    String key, val;
		    if (ptr == -1) {
			key = "";
			val = pair;
		    } else {
			key = pair.substring(0,ptr);
			val = pair.substring(ptr+1);
		    }
		    if (checklists.containsKey(key)) {
			System.out.println(getMessage("warning.ocil.href", key));
		    }
		    try {
			checklists.put(key, new Checklist(new File(val)));
		    } catch (OcilException e) {
			logger.severe(e.getMessage());
			System.exit(1);
		    }
		} else if (argv[i].equals("-r")) {
		    reportFile = new File(argv[++i]);
		} else if (argv[i].equals("-l")) {
		    try {
			switch(Integer.parseInt(argv[++i])) {
			  case 4:
			    level = Level.SEVERE;
			    break;
			  case 3:
			    level = Level.WARNING;
			    break;
			  case 2:
			    level = Level.INFO;
			    break;
			  case 1:
			    level = Level.FINEST;
			    break;
			  default:
			    System.out.println(getMessage("warning.log.level.range", argv[i]));
			    break;
			}
		    } catch (NumberFormatException e) {
			System.out.println(getMessage("warning.log.level.value", argv[i]));
		    }
		} else if (argv[i].equals("-y")) {
		    logFile = new File(argv[++i]);
		} else if (argv[i].equals("-e")) {
		    ocilDir = new File(argv[++i]);
		} else if (argv[i].equals("-t")) {
		    transformFile = new File(argv[++i]);
		} else if (argv[i].equals("-x")) {
		    reportHTML = new File(argv[++i]);
		} else if (argv[i].equals("-k")) {
		    ksFile = new File(argv[++i]);
		} else if (argv[i].equals("-w")) {
		    ksPass = argv[++i];
		} else if (argv[i].equals("-plugin")) {
		    pluginName = argv[++i];
		} else if (argv[i].equals("-config")) {
		    configFile = new File(argv[++i]);
		} else {
		    System.out.println(getMessage("warning.cli.arg", argv[i]));
		}
	    } else {
		System.out.println(getMessage("warning.cli.arg", argv[i]));
	    }
	}

	int exitCode = 1;
	IPlugin plugin = null;
	try {
	    plugin = PluginFactory.newInstance(new File(BASE_DIR, "plugin")).createPlugin(pluginName);
	    exitCode = 0;
	} catch (IllegalArgumentException e) {
	    System.out.println("Not a directory: " + e.getMessage());
	} catch (NoSuchElementException e) {
	    System.out.println("Plugin not found: " + e.getMessage());
	} catch (PluginConfigurationException e) {
	    System.out.println(LogFormatter.toString(e));
	}

	printHeader(plugin);
	if (help || plugin == null) {
	    printHelp(plugin);
	} else {
	    exitCode = 1;
	    try {
		logger = LogFormatter.createDuplex(logFile, level);
		IDatastream ds = null;
		String sourceName = source.getName();
		if (source.isFile() && source.getName().toLowerCase().endsWith(".xml")) {
		    //
		    // Process a datastream
		    //
		    DatastreamCollection dsc = null;
		    if (verify) {
			logger.info(getMessage("message.signature.validating", source.toString()));
			KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
			keyStore.load(new FileInputStream(ksFile), ksPass.toCharArray());
			SignatureValidator validator = new SignatureValidator(source, keyStore);
			if (!validator.containsSignature()) {
			    throw new XPERTException(getMessage("error.signature.failed"));
			} else if (validator.validate()) {
			    logger.info(getMessage("message.signature.valid"));
			    logger.info(getMessage("message.stream.loading"));
			    dsc = new DatastreamCollection(validator.getSource());
			} else {
			    throw new XPERTException(getMessage("error.signature.missing"));
			}
		    } else {
			logger.info(getMessage("message.stream.loading"));
			dsc = new DatastreamCollection(source);
		    }
		    if (query) {
			logger.info(getMessage("message.stream.query", source.toString()));
			for (String sId : dsc.getStreamIds()) {
			    logger.info("Stream ID=\"" + sId + "\"");
			    ds = dsc.getDatastream(sId);
			    for (String bId : ds.getBenchmarkIds()) {
				logger.info("  Benchmark ID=\"" + bId + "\"");
				for (String pId : ds.getProfileIds(bId)) {
				    logger.info("    Profile ID=\"" + pId + "\"");
				}
			    }
			}
		    } else {
			//
			// Determine the singleton Stream ID, if none was specified
			//
			if (streamId == null) {
			    if (dsc.getStreamIds().size() == 1) {
				streamId = dsc.getStreamIds().iterator().next();
				logger.info(getMessage("message.stream.autoselect", streamId));
			    } else {
				throw new XPERTException(getMessage("error.stream"));
			    }
			}
			sourceName = new StringBuffer(sourceName).append(", stream ").append(streamId).toString();
			try {
			    ds = dsc.getDatastream(streamId);
			} catch (NoSuchElementException e) {
			    throw new XPERTException(getMessage("error.stream.id", streamId));
			}
		    }
		} else if (source.isDirectory() || source.getName().toLowerCase().endsWith(".zip")) {
		    //
		    // Process a bundle
		    //
		    ds = new Bundle(source);
		    if (query) {
			logger.info(getMessage("message.bundle.query", source.toString()));
			for (String bId : ds.getBenchmarkIds()) {
			    logger.info("  Benchmark ID=\"" + bId + "\"");
			    for (String pId : ds.getProfileIds(bId)) {
				logger.info("    Profile ID=\"" + pId + "\"");
			    }
			}
		    }
		} else {
		    throw new XPERTException(getMessage("error.source", source.toString()));
		}
		if (!query) {
		    if (benchmarkId == null) {
			//
			// Determine whether there's a singleton benchmark in the datastream
			//
			if (ds.getBenchmarkIds().size() == 1) {
			    benchmarkId = ds.getBenchmarkIds().iterator().next();
			    logger.info(getMessage("message.benchmark.autoselect", benchmarkId));
			} else {
			    throw new XPERTException(getMessage("error.benchmark", sourceName));
			}
		    }
		    if (profileId == null) {
			//
			// Determine whether there's a singleton profile in the benchmark
			//
			Collection<String> profiles = ds.getProfileIds(benchmarkId);
			if (profiles.size() == 1) {
			    profileId = profiles.iterator().next();
			    logger.info(getMessage("message.profile.autoselect", profileId));
			} else if (profiles.size() > 1 && ds.getContext(benchmarkId, null).getSelectedRules().size() == 0) {
			    //
			    // If no rules are selected by default, then require that the user select a profile
			    //
			    StringBuffer sb = new StringBuffer();
			    for (String id : profiles) {
				sb.append(LogFormatter.LF).append("  ").append(id);
			    }
			    throw new XPERTException(getMessage("error.profile", sb.toString()));
			}
		    }
		    IScapContext ctx = ds.getContext(benchmarkId, profileId);
		    logger.info(getMessage("message.start", new Date()));
		    try {
			//
			// Configure the jOVAL plugin
			//
			Properties config = new Properties();
			if (configFile.isFile()) {
			    config.load(new FileInputStream(configFile));
			}
			plugin.configure(config);
		    } catch (Exception e) {
			throw new XPERTException(getMessage("error.plugin", e.getMessage(), logFile));
		    }

		    try {
			Engine engine = new Engine(plugin);
			engine.setContext(ctx);
			for (Map.Entry<String, IChecklist> entry : checklists.entrySet()) {
			    engine.addChecklist(entry.getKey(), entry.getValue());
			}
			XccdfObserver observer = new XccdfObserver(ocilDir);
			engine.getNotificationProducer().addObserver(observer);
			engine.run();
			plugin.dispose();
			engine.getNotificationProducer().removeObserver(observer);
			switch(engine.getResult()) {
			  case OK:
			    IReport report = engine.getReport(verbose ? SystemEnumeration.ANY : SystemEnumeration.XCCDF);
			    if (report == null) {
				logger.info(getMessage("message.report.none"));
			    } else if (report.getAssetReportCollection().isSetReports()) {
				logger.info(getMessage("message.report.save", reportFile));
				report.writeXML(reportFile);
				logger.info(getMessage("message.transform", reportHTML));
				ctx.getBenchmark().writeTransform(transformFile, reportHTML);
			    }
			    logger.info(getMessage("message.benchmark.processed"));
			    exitCode = 0;
			    break;

			  case ERR:
			    throw engine.getError();
			}
		    } catch (OcilException e) {
			logger.severe(getMessage("error.ocil", e.getMessage()));
			logger.info(getMessage("message.ocil.export", ocilDir));
		    } catch (UnknownHostException e) {
			logger.severe(getMessage("error.host.unknown", e.getMessage()));
		    } catch (ConnectException e) {
			logger.severe(getMessage("error.host.connect", e.getMessage()));
		    }
		}
	    } catch (IOException e) {
		logger.severe(LogFormatter.toString(e));
	    } catch (ScapException e) {
		logger.warning(LogFormatter.toString(e));
		exitCode = 2;
	    } catch (XPERTException e) {
		logger.severe(e.getMessage());
		exitCode = 1;
	    } catch (Exception e) {
		logger.severe(LogFormatter.toString(e));
		exitCode = 3;
	    }
	}
	System.exit(exitCode);
    }

    // Internal

    /**
     * Given a file path and password, load a JKS file.
     */
    static KeyStore loadKeyStore(String fname, String password) throws Exception {
	KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
	File cacerts = new File(fname);
	if (cacerts.exists()) {
	    ks.load(new FileInputStream(cacerts), password.toCharArray());
	    return ks;
	} else {
	    throw new FileNotFoundException(fname);
	}
    }

    static class XPERTException extends Exception {
	XPERTException(String message) {
	    super(message);
	}
    }

    static class XccdfObserver implements IObserver<org.joval.intf.scap.xccdf.IEngine.Message> {
	File ocilDir;

	XccdfObserver(File ocilDir) {
	    this.ocilDir = ocilDir;
	}

	public void notify(IProducer<org.joval.intf.scap.xccdf.IEngine.Message> sender,
			   org.joval.intf.scap.xccdf.IEngine.Message msg, Object arg) {
	    switch(msg) {
	      case PLATFORM_PHASE_START:
		logger.info("Beginning platform applicability scan");
		break;

	      case PLATFORM_CPE:
		logger.info("Testing platform " + (String)arg);
		break;

	      case PLATFORM_PHASE_END:
		if (((Boolean)arg).booleanValue()) {
		    logger.info("Host is applicable");
		} else {
		    logger.info("Host is not applicable");
		}
		break;

	      case RULES_PHASE_START:
		logger.info("Starting rule processing");
		break;

	      case RULES_PHASE_END:
		logger.info("Rule processing complete");
		break;

	      case OVAL_ENGINE:
		logger.info("Executing OVAL tests");
		org.joval.intf.scap.oval.IEngine oe = (org.joval.intf.scap.oval.IEngine)arg;
		oe.getNotificationProducer().addObserver(new OvalObserver(oe.getNotificationProducer()));
		break;

	      case OCIL_MISSING:
		OcilMessageArgument oma = (OcilMessageArgument)arg;
		String base = oma.getHref();
		if (base.endsWith(".xml")) {
		    base = base.substring(0, base.length() - 4);
		}
		try {
		    if (!ocilDir.exists()) {
			ocilDir.mkdirs();
		    }
		    File checklist = new File(ocilDir, base + ".xml");
		    logger.warning("Exporting OCIL checklist to file: " + checklist.toString());
		    oma.getChecklist().writeXML(checklist);
		    File variables = new File(ocilDir, base + "-variables.xml");
		    logger.warning("Exporting OCIL variables to file: " + variables.toString());
		    oma.getVariables().writeXML(variables);
		} catch (IOException e) {
		    e.printStackTrace();
		}
		break;

	      case SCE_SCRIPT:
		logger.info("Running SCE script " + (String)arg);
		break;
	    }
	}
    }

    static class OvalObserver implements IObserver<org.joval.intf.scap.oval.IEngine.Message> {
	private IProducer<org.joval.intf.scap.oval.IEngine.Message> producer;

	OvalObserver(IProducer<org.joval.intf.scap.oval.IEngine.Message> producer) {
	    this.producer = producer;
	}

	public void notify(IProducer<org.joval.intf.scap.oval.IEngine.Message> sender,
			   org.joval.intf.scap.oval.IEngine.Message msg, Object arg) {
	    switch(msg) {
	      case OBJECT_PHASE_START:
		logger.info("Beginning scan");
		break;

	      case OBJECT:
		logger.info("Scanning " + (String)arg);
		break;

	      case OBJECT_PHASE_END:
		logger.info("Scan complete");
		break;

	      case DEFINITION_PHASE_START:
		logger.info("Evaluating OVAL definitions");
		break;

	      case DEFINITION:
		logger.info("Evaluating " + (String)arg);
		break;

	      case DEFINITION_PHASE_END:
		logger.info("Finished evaluating OVAL definitions");
		producer.removeObserver(this);
		break;

	      case SYSTEMCHARACTERISTICS:
		// no-op
		break;
	    }
	}
    }
}
