// Copyright (C) 2012 jOVAL.org.  All rights reserved.
// This software is licensed under the AGPL 3.0 license available at http://www.joval.org/agpl_v3.txt

package org.joval.scap.oval.adapter.independent;

import java.io.IOException;
import java.io.InputStream;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.StringTokenizer;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import jsaf.intf.io.IFile;
import jsaf.intf.io.IFileEx;
import jsaf.intf.system.ISession;
import jsaf.intf.unix.io.IUnixFileInfo;
import jsaf.intf.unix.system.IUnixSession;
import jsaf.intf.windows.io.IWindowsFileInfo;
import jsaf.intf.windows.system.IWindowsSession;
import jsaf.io.LittleEndian;
import jsaf.io.StreamTool;
import jsaf.util.Base64;
import jsaf.util.SafeCLI;

import scap.oval.common.MessageLevelEnumeration;
import scap.oval.common.MessageType;
import scap.oval.common.OperationEnumeration;
import scap.oval.common.SimpleDatatypeEnumeration;
import scap.oval.definitions.core.ObjectType;
import scap.oval.definitions.core.EntityObjectStringType;
import scap.oval.definitions.independent.EntityObjectHashTypeType;
import scap.oval.definitions.independent.Filehash58Object;
import scap.oval.systemcharacteristics.core.EntityItemStringType;
import scap.oval.systemcharacteristics.core.FlagEnumeration;
import scap.oval.systemcharacteristics.core.ItemType;
import scap.oval.systemcharacteristics.independent.EntityItemHashTypeType;
import scap.oval.systemcharacteristics.independent.Filehash58Item;

import org.joval.intf.plugin.IAdapter;
import org.joval.scap.oval.CollectException;
import org.joval.scap.oval.Factories;
import org.joval.util.JOVALMsg;

/**
 * Collects items for filehash58 OVAL objects.
 *
 * @author David A. Solin
 * @version %I% %G%
 */
public class Filehash58Adapter extends BaseFileAdapter<Filehash58Item> {
    enum Algorithm {
	MD5("MD5", "md5"),
	SHA1("SHA-1", "sha1"),
	SHA224("SHA-224", "sha224"),
	SHA256("SHA-256", "sha256"),
	SHA384("SHA-384", "sha384"),
	SHA512("SHA-512", "sha512");

	String ovalId, osId;

	Algorithm(String ovalId, String osId) {
	    this.ovalId = ovalId;
	    this.osId = osId;
	}

	static Algorithm fromOval(String id) throws IllegalArgumentException {
	    for (Algorithm alg : values()) {
		if (id.equals(alg.ovalId)) {
		    return alg;
		}
	    }
	    throw new IllegalArgumentException(id);
	}
    }

    // Implement IAdapter

    public Collection<Class> init(ISession session) {
	Collection<Class> classes = new Vector<Class>();
	try {
	    baseInit(session);
	    classes.add(Filehash58Object.class);
	} catch (UnsupportedOperationException e) {
	    // doesn't support ISession.getFilesystem
	}
	return classes;
    }

    // Protected

    protected Class getItemClass() {
	return Filehash58Item.class;
    }

    /**
     * Parse the file as specified by the Object, and decorate the Item.
     */
    protected Collection<Filehash58Item> getItems(ObjectType obj, ItemType base, IFile f, IRequestContext rc)
		throws IOException, CollectException {

	//
	// First, determine the appropriate set of checksum algorithms to use
	//
	HashSet<Algorithm> algorithms = new HashSet<Algorithm>();
	EntityObjectHashTypeType hashType = ((Filehash58Object)obj).getHashType();
	String hash = (String)hashType.getValue();
	OperationEnumeration op = hashType.getOperation();
	switch(op) {
	  case EQUALS:
	    try {
		algorithms.add(Algorithm.fromOval(hash));
	    } catch (IllegalArgumentException e) {
		String message = JOVALMsg.getMessage(JOVALMsg.ERROR_CHECKSUM_ALGORITHM, hashType);
		throw new CollectException(message, FlagEnumeration.ERROR);
	    }
	    break;

	  case NOT_EQUAL:
	    for (Algorithm alg : Algorithm.values()) {
		if (!hash.equals(alg.ovalId)) {
		    algorithms.add(alg);
		}
	    }
	    break;

	  case PATTERN_MATCH:
	    try {
		Pattern p = Pattern.compile(hash);
		for (Algorithm alg : Algorithm.values()) {
		    if (p.matcher(alg.ovalId).find()) {
			algorithms.add(alg);
		    }
		}
	    } catch (PatternSyntaxException e) {
		MessageType msg = Factories.common.createMessageType();
		msg.setLevel(MessageLevelEnumeration.ERROR);
		msg.setValue(JOVALMsg.getMessage(JOVALMsg.ERROR_PATTERN, e.getMessage()));
		rc.addMessage(msg);
		session.getLogger().warn(JOVALMsg.getMessage(JOVALMsg.ERROR_EXCEPTION), e);
	    }
	    break;

	  default:
	    String msg = JOVALMsg.getMessage(JOVALMsg.ERROR_UNSUPPORTED_OPERATION, op);
	    throw new CollectException(msg, FlagEnumeration.NOT_COLLECTED);
	}

	Filehash58Item baseItem = (Filehash58Item)base;
	Filehash58Object fObj = (Filehash58Object)obj;
	IWindowsSession.View view = getView(fObj.getBehaviors());
	Collection<Filehash58Item> items = new Vector<Filehash58Item>();
	for (Algorithm alg : algorithms) {
	    try {
		items.add(getItem(baseItem, alg, computeChecksum(f, alg, view)));
	    } catch (NoSuchAlgorithmException e) {
		MessageType msg = Factories.common.createMessageType();
		msg.setLevel(MessageLevelEnumeration.WARNING);
		msg.setValue(JOVALMsg.getMessage(JOVALMsg.ERROR_CHECKSUM_ALGORITHM, e.getMessage()));
		rc.addMessage(msg);
	    } catch (Exception e) {
		MessageType msg = Factories.common.createMessageType();
		msg.setLevel(MessageLevelEnumeration.ERROR);
		msg.setValue(e.getMessage());
		rc.addMessage(msg);
		session.getLogger().warn(JOVALMsg.getMessage(JOVALMsg.ERROR_EXCEPTION), e);
	    }
	}
	return items;
    }

    @Override
    protected List<InputStream> getPowershellModules() {
	return Arrays.asList(getClass().getResourceAsStream("Filehash.psm1"));
    }

    // Internal

    protected Filehash58Item getItem(Filehash58Item baseItem, Algorithm alg, String checksum) {
	Filehash58Item item = Factories.sc.independent.createFilehash58Item();
	item.setPath(baseItem.getPath());
	item.setFilename(baseItem.getFilename());
	item.setFilepath(baseItem.getFilepath());
	item.setWindowsView(baseItem.getWindowsView());

	EntityItemHashTypeType hashType = Factories.sc.independent.createEntityItemHashTypeType();
	hashType.setValue(alg.ovalId);
	item.setHashType(hashType);

	EntityItemStringType hash = Factories.sc.core.createEntityItemStringType();
	hash.setValue(checksum);
	item.setHash(hash);

	return item;
    }

    private String computeChecksum(IFile f, Algorithm alg, IWindowsSession.View view) throws Exception {
	IFileEx ext = f.getExtended();
	boolean typecheck = false;
	if (ext instanceof IWindowsFileInfo) {
	    typecheck = ((IWindowsFileInfo)ext).getWindowsFileType() == IWindowsFileInfo.FILE_TYPE_DISK;
	} else if (ext instanceof IUnixFileInfo) {
	    typecheck = ((IUnixFileInfo)ext).getUnixFileType().equals(IUnixFileInfo.FILE_TYPE_REGULAR);
	}
	if (!typecheck) {
	    throw new IllegalArgumentException(f.getPath());
	}
	String checksum = null;
	switch(session.getType()) {
	  case UNIX:
	    IUnixSession us = (IUnixSession)session;
	    switch(us.getFlavor()) {
	      case AIX:
	      case LINUX:
	      case MACOSX: {
		String cmd = "openssl dgst -hex -" + alg.osId + " " + f.getPath();
		String temp = SafeCLI.exec(cmd, session, IUnixSession.Timeout.M);
		int ptr = temp.indexOf("= ");
		if (ptr > 0) {
		    checksum = temp.substring(ptr+2).trim();
		}
		break;
	      }

	      case SOLARIS: {
		String cmd = "digest -a " + alg.osId + " " + f.getPath();
		checksum = SafeCLI.exec(cmd, session, IUnixSession.Timeout.M);
		break;
	      }
	    }
	    break;

	  case WINDOWS: {
	    if (alg == Algorithm.SHA224) {
		throw new NoSuchAlgorithmException(alg.osId);
	    }
	    String enc = getRunspace(view).invoke("Get-FileHash -Algorithm " + alg.osId + " -Path \"" + f.getPath() + "\"");
	    checksum = LittleEndian.toHexString(Base64.decode(enc));
	    break;
	  }
	}
	return checksum;
    }
}
