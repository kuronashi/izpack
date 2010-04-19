/*
 * IzPack - Copyright 2001-2008 Julien Ponge, All Rights Reserved.
 *
 * http://izpack.org/
 * http://izpack.codehaus.org/
 *
 * Copyright 2007 Dennis Reil
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.izforge.izpack.installer.unpacker;

import com.izforge.izpack.api.data.*;
import com.izforge.izpack.api.event.InstallerListener;
import com.izforge.izpack.api.handler.AbstractUIProgressHandler;
import com.izforge.izpack.api.rules.RulesEngine;
import com.izforge.izpack.api.substitutor.SubstitutionType;
import com.izforge.izpack.api.substitutor.VariableSubstitutor;
import com.izforge.izpack.api.unpacker.IDiscardInterruptable;
import com.izforge.izpack.data.UpdateCheck;
import com.izforge.izpack.installer.data.UninstallData;
import com.izforge.izpack.util.Debug;
import com.izforge.izpack.util.OsVersion;
import org.apache.regexp.RE;
import org.apache.regexp.RECompiler;
import org.apache.regexp.RESyntaxException;

import java.io.*;
import java.net.URI;
import java.util.*;

/**
 * Abstract base class for all unpacker implementations.
 *
 * @author Dennis Reil, <izpack@reil-online.de>
 */
public abstract class UnpackerBase implements IUnpacker, IDiscardInterruptable
{
    /**
     * The installdata.
     */
    protected AutomatedInstallData idata;

    /**
     * The installer listener.
     */
    protected AbstractUIProgressHandler handler;

    /**
     * The uninstallation data.
     */
    protected UninstallData udata;

    /**
     * The absolute path of the installation. (NOT the canonical!)
     */
    protected File absolute_installpath;

    /**
     * The absolute path of the source installation jar.
     */
    private File absolutInstallSource;

    /**
     * The result of the operation.
     */
    protected boolean result = true;

    /**
     * The instances of the unpacker objects.
     */
    protected static HashMap<Object, String> instances = new HashMap<Object, String>();

    /**
     * Interrupt flag if global interrupt is desired.
     */
    protected static boolean interruptDesired = false;

    /**
     * Do not perform a interrupt call.
     */
    protected static boolean discardInterrupt = false;

    /**
     * The name of the XML file that specifies the panel langpack
     */
    protected static final String LANG_FILE_NAME = "packsLang.xml";

    public static final String ALIVE = "alive";

    public static final String INTERRUPT = "doInterrupt";

    public static final String INTERRUPTED = "interruppted";

    protected RulesEngine rules;

    protected ResourceManager resourceManager;
    protected VariableSubstitutor variableSubstitutor;

    /**
     * The constructor.
     *
     * @param idata               The installation data.
     * @param handler             The installation progress handler.
     * @param rules
     * @param variableSubstitutor
     * @param udata
     */
    public UnpackerBase(AutomatedInstallData idata, AbstractUIProgressHandler handler, ResourceManager resourceManager, RulesEngine rules, VariableSubstitutor variableSubstitutor, UninstallData udata)
    {
        this.idata = idata;
        this.handler = handler;
        this.resourceManager = resourceManager;
        this.rules = rules;
        // Initialize the variable substitutor
        this.variableSubstitutor = variableSubstitutor;
        this.udata = udata;
    }

    public void setRules(RulesEngine rules)
    {
        this.rules = rules;
    }

    /**
     * Returns a copy of the active unpacker instances.
     *
     * @return a copy of active unpacker instances
     */
    public static HashMap getRunningInstances()
    {
        synchronized (instances)
        { // Return a shallow copy to prevent a
            // ConcurrentModificationException.
            return (HashMap) (instances.clone());
        }
    }

    /**
     * Adds this to the map of all existent instances of Unpacker.
     */
    protected void addToInstances()
    {
        synchronized (instances)
        {
            instances.put(this, ALIVE);
        }
    }

    /**
     * Removes this from the map of all existent instances of Unpacker.
     */
    protected void removeFromInstances()
    {
        synchronized (instances)
        {
            instances.remove(this);
        }
    }

    /**
     * Initiate interrupt of all alive Unpacker. This method does not interrupt the Unpacker objects
     * else it sets only the interrupt flag for the Unpacker objects. The dispatching of interrupt
     * will be performed by the Unpacker objects self.
     */
    private static void setInterruptAll()
    {
        synchronized (instances)
        {
            for (Object key : instances.keySet())
            {
                if (instances.get(key).equals(ALIVE))
                {
                    instances.put(key, INTERRUPT);
                }
            }
            // Set global flag to allow detection of it in other classes.
            // Do not set it to thread because an exec will then be stoped.
            setInterruptDesired(true);
        }
    }

    /**
     * Initiate interrupt of all alive Unpacker and waits until all Unpacker are interrupted or the
     * wait time has arrived. If the doNotInterrupt flag in InstallerListener is set to true, the
     * interrupt will be discarded.
     *
     * @param waitTime wait time in millisecounds
     * @return true if the interrupt will be performed, false if the interrupt will be discarded
     */
    public static boolean interruptAll(long waitTime)
    {
        long t0 = System.currentTimeMillis();
        if (isDiscardInterrupt())
        {
            return (false);
        }
        setInterruptAll();
        while (!isInterruptReady())
        {
            if (System.currentTimeMillis() - t0 > waitTime)
            {
                return (true);
            }
            try
            {
                Thread.sleep(100);
            }
            catch (InterruptedException e)
            {
            }
        }
        return (true);
    }

    private static boolean isInterruptReady()
    {
        synchronized (instances)
        {
            for (Object key : instances.keySet())
            {
                if (!instances.get(key).equals(INTERRUPTED))
                {
                    return (false);
                }
            }
            return (true);
        }

    }

    /**
     * Sets the interrupt flag for this Unpacker to INTERRUPTED if the previos state was INTERRUPT
     * or INTERRUPTED and returns whether interrupt was initiate or not.
     *
     * @return whether interrupt was initiate or not
     */
    protected boolean performInterrupted()
    {
        synchronized (instances)
        {
            Object doIt = instances.get(this);
            if (doIt != null && (doIt.equals(INTERRUPT) || doIt.equals(INTERRUPTED)))
            {
                instances.put(this, INTERRUPTED);
                this.result = false;
                return (true);
            }
            return (false);
        }
    }

    /**
     * Returns whether interrupt was initiate or not for this Unpacker.
     *
     * @return whether interrupt was initiate or not
     */
    private boolean shouldInterrupt()
    {
        synchronized (instances)
        {
            Object doIt = instances.get(this);
            if (doIt != null && (doIt.equals(INTERRUPT) || doIt.equals(INTERRUPTED)))
            {
                return (true);
            }
            return (false);
        }

    }

    /**
     * Return the state of the operation.
     *
     * @return true if the operation was successful, false otherwise.
     */
    public boolean getResult()
    {
        return this.result;
    }

    /**
     * @param filename
     * @param patterns
     * @return true if the file matched one pattern, false if it did not
     */
    private boolean fileMatchesOnePattern(String filename, ArrayList<RE> patterns)
    {
        // first check whether any include matches
        for (RE pattern : patterns)
        {
            if (pattern.match(filename))
            {
                return true;
            }
        }

        return false;
    }

    /**
     * @param fileNamePatterns A list of file name patterns (in ant fileset syntax)
     * @param recompiler       The regular expression compiler (used to speed up RE compiling).
     * @return List of org.apache.regexp.RE
     */
    private List<RE> preparePatterns(ArrayList<String> fileNamePatterns, RECompiler recompiler)
    {
        ArrayList<RE> result = new ArrayList<RE>();

        for (String element : fileNamePatterns)
        {
            if ((element != null) && (element.length() > 0))
            {
                // substitute variables in the pattern
                element = variableSubstitutor.substitute(element, SubstitutionType.TYPE_PLAIN);

                // check whether the pattern is absolute or relative
                File file = new File(element);

                // if it is relative, make it absolute and prepend the
                // installation path
                // (this is a bit dangerous...)
                if (!file.isAbsolute())
                {
                    element = new File(this.absolute_installpath, element).toString();
                }

                // now parse the element and construct a regular expression from
                // it
                // (we have to parse it one character after the next because
                // every
                // character should only be processed once - it's not possible
                // to get this
                // correct using regular expression replacing)
                StringBuffer element_re = new StringBuffer();

                int lookahead = -1;

                int pos = 0;

                while (pos < element.length())
                {
                    char c;

                    if (lookahead != -1)
                    {
                        c = (char) lookahead;
                        lookahead = -1;
                    }
                    else
                    {
                        c = element.charAt(pos++);
                    }

                    switch (c)
                    {
                        case '/':
                        {
                            element_re.append(File.separator);
                            break;
                        }
                        // escape backslash and dot
                        case '\\':
                        case '.':
                        {
                            element_re.append("\\");
                            element_re.append(c);
                            break;
                        }
                        case '*':
                        {
                            if (pos == element.length())
                            {
                                element_re.append("[^").append(File.separator).append("]*");
                                break;
                            }

                            lookahead = element.charAt(pos++);

                            // check for "**"
                            if (lookahead == '*')
                            {
                                element_re.append(".*");
                                // consume second star
                                lookahead = -1;
                            }
                            else
                            {
                                element_re.append("[^").append(File.separator).append("]*");
                                // lookahead stays there
                            }
                            break;
                        }
                        default:
                        {
                            element_re.append(c);
                            break;
                        }
                    } // switch

                }

                // make sure that the whole expression is matched
                element_re.append('$');

                // replace \ by \\ and create a RE from the result
                try
                {
                    result.add(new RE(recompiler.compile(element_re.toString())));
                }
                catch (RESyntaxException e)
                {
                    this.handler.emitNotification("internal error: pattern \"" + element
                            + "\" produced invalid RE \"" + file.getPath() + "\"");
                }

            }
        }

        return result;
    }

    // CUSTOM ACTION STUFF -------------- start -----------------

    /**
     * Informs all listeners which would be informed at the given action type.
     *
     * @param customActions             array of lists with the custom action objects
     * @param action                    identifier for which callback should be called
     * @param file                      first parameter for the call
     * @param packFile                  second parameter for the call
     * @param abstractUIProgressHandler third parameter for the call
     */
    protected void informListeners(List<InstallerListener> customActions, int action, File file,
                                   PackFile packFile, AbstractUIProgressHandler abstractUIProgressHandler) throws Exception
    {
        // Iterate the action list.
        for (InstallerListener installerListener : customActions)
        {
            if (shouldInterrupt())
            {
                return;
            }
            switch (action)
            {
                case InstallerListener.BEFORE_FILE:
                    installerListener.beforeFile(file, packFile);
                    break;
                case InstallerListener.AFTER_FILE:
                    installerListener.afterFile(file, packFile);
                    break;
                case InstallerListener.BEFORE_DIR:
                    installerListener.beforeDir(file, packFile);
                    break;
                case InstallerListener.AFTER_DIR:
                    installerListener.afterDir(file, packFile);
                    break;
            }
        }
    }

    protected void informListeners(List<InstallerListener> customActions, int action, Pack pack,
                                   Integer integer, AbstractUIProgressHandler abstractUIProgressHandler) throws Exception
    {
        for (InstallerListener customAction : customActions)
        {
            switch (action)
            {
                case InstallerListener.BEFORE_PACK:
                    customAction.beforePack(pack, integer,
                            abstractUIProgressHandler);
                    break;
                case InstallerListener.AFTER_PACK:
                    customAction.afterPack(pack, integer,
                            abstractUIProgressHandler);
                    break;
            }
        }
    }

    protected void informListeners(List<InstallerListener> customActions, int action, AutomatedInstallData pack,
                                   Integer integer, AbstractUIProgressHandler abstractUIProgressHandler) throws Exception
    {
        for (InstallerListener customAction : customActions)
        {
            switch (action)
            {
                case InstallerListener.BEFORE_PACKS:
                    customAction.beforePacks(pack, integer, abstractUIProgressHandler);
                    break;
                case InstallerListener.AFTER_PACKS:
                    customAction.afterPacks(pack, abstractUIProgressHandler);
                    break;
            }
        }
    }

    /**
     * Creates the given directory recursive and calls the method "afterDir" of each listener with
     * the current file object and the pack file object. On error an exception is raised.
     *
     * @param dest          the directory which should be created
     * @param pf            current pack file object
     * @param customActions all defined custom actions
     * @return false on error, true else
     * @throws Exception
     */
    protected boolean mkDirsWithEnhancement(File dest, PackFile pf, List<InstallerListener> customActions)
            throws Exception
    {
        String path = "unknown";
        if (dest != null)
        {
            path = dest.getAbsolutePath();
        }
        if (dest != null && !dest.exists() && dest.getParentFile() != null)
        {
            if (dest.getParentFile().exists())
            {
                informListeners(customActions, InstallerListener.BEFORE_DIR, dest, pf, null);
            }
            if (!dest.mkdir())
            {
                mkDirsWithEnhancement(dest.getParentFile(), pf, customActions);
                if (!dest.mkdir())
                {
                    dest = null;
                }
            }
            informListeners(customActions, InstallerListener.AFTER_DIR, dest, pf, null);
        }
        if (dest == null)
        {
            handler.emitError("Error creating directories", "Could not create directory\n" + path);
            handler.stopAction();
            return (false);
        }
        return (true);
    }

    // CUSTOM ACTION STUFF -------------- end -----------------

    /**
     * Returns whether an interrupt request should be discarded or not.
     *
     * @return Returns the discard interrupt flag
     */
    public static synchronized boolean isDiscardInterrupt()
    {
        return discardInterrupt;
    }

    /**
     * Sets the discard interrupt flag.
     *
     * @param di the discard interrupt flag to set
     */
    public synchronized void setDiscardInterrupt(boolean di)
    {
        discardInterrupt = di;
        setInterruptDesired(false);
    }

    /**
     * Returns the interrupt desired state.
     *
     * @return the interrupt desired state
     */
    public static boolean isInterruptDesired()
    {
        return interruptDesired;
    }

    /**
     * @param interruptDesired The interrupt desired flag to set
     */
    private static void setInterruptDesired(boolean interruptDesired)
    {
        UnpackerBase.interruptDesired = interruptDesired;
    }

    public abstract void run();

    /**
     * @param updatechecks
     */
    protected void performUpdateChecks(ArrayList<UpdateCheck> updatechecks)
    {
        ArrayList<RE> include_patterns = new ArrayList<RE>();
        ArrayList<RE> exclude_patterns = new ArrayList<RE>();

        RECompiler recompiler = new RECompiler();

        this.absolute_installpath = new File(idata.getInstallPath()).getAbsoluteFile();

        // at first, collect all patterns
        for (UpdateCheck updateCheck : updatechecks)
        {
            if (updateCheck.includesList != null)
            {
                include_patterns.addAll(preparePatterns(updateCheck.includesList, recompiler));
            }

            if (updateCheck.excludesList != null)
            {
                exclude_patterns.addAll(preparePatterns(updateCheck.excludesList, recompiler));
            }
        }

        // do nothing if no update checks were specified
        if (include_patterns.size() == 0)
        {
            return;
        }

        // now collect all files in the installation directory and figure
        // out files to check for deletion

        // use a treeset for fast access
        TreeSet<String> installed_files = new TreeSet<String>();

        for (String installedFileName : this.udata.getInstalledFilesList())
        {
            File file = new File(installedFileName);

            if (!file.isAbsolute())
            {
                file = new File(this.absolute_installpath, installedFileName);
            }

            installed_files.add(file.getAbsolutePath());
        }

        // now scan installation directory (breadth first), contains Files of
        // directories to scan
        // (note: we'll recurse infinitely if there are circular links or
        // similar nasty things)
        Stack<File> scanstack = new Stack<File>();

        // contains File objects determined for deletion
        ArrayList<File> files_to_delete = new ArrayList<File>();

        try
        {
            scanstack.add(absolute_installpath);

            while (!scanstack.empty())
            {
                File dirToScan = scanstack.pop();

                File[] files = dirToScan.listFiles();

                if (files == null)
                {
                    throw new IOException(dirToScan.getPath() + "is not a directory!");
                }

                for (File subFile : files)
                {
                    String subFileName = subFile.getPath();

                    // skip files we just installed
                    if (installed_files.contains(subFileName))
                    {
                        continue;
                    }

                    if (fileMatchesOnePattern(subFileName, include_patterns)
                            && (!fileMatchesOnePattern(subFileName, exclude_patterns)))
                    {
                        files_to_delete.add(subFile);
                    }

                    if (subFile.isDirectory() && !fileMatchesOnePattern(subFileName, exclude_patterns))
                    {
                        scanstack.push(subFile);
                    }

                }
            }
        }
        catch (IOException e)
        {
            this.handler.emitError("error while performing update checks", e.toString());
        }

        for (File file : files_to_delete)
        {
            if (!file.isDirectory())
            // skip directories - they cannot be removed safely yet
            {
//                this.handler.emitNotification("deleting " + f.getPath());
                file.delete();
            }

        }
    }

    /**
     * Writes information about the installed packs and the variables at
     * installation time.
     *
     * @throws IOException
     * @throws ClassNotFoundException
     */
    public void writeInstallationInformation() throws IOException, ClassNotFoundException
    {
        if (!idata.getInfo().isWriteInstallationInformation())
        {
            Debug.trace("skip writing installation information");
            return;
        }
        Debug.trace("writing installation information");
        String installdir = idata.getInstallPath();

        List<Pack> installedpacks = new ArrayList<Pack>(idata.getSelectedPacks());

        File installationinfo = new File(installdir + File.separator + AutomatedInstallData.INSTALLATION_INFORMATION);
        if (!installationinfo.exists())
        {
            Debug.trace("creating info file" + installationinfo.getAbsolutePath());
            installationinfo.createNewFile();
        }
        else
        {
            Debug.trace("installation information found");
            // read in old information and update
            FileInputStream fin = new FileInputStream(installationinfo);
            ObjectInputStream oin = new ObjectInputStream(fin);

            List packs = (List) oin.readObject();
            for (Object pack1 : packs)
            {
                Pack pack = (Pack) pack1;
                installedpacks.add(pack);
            }
            oin.close();
            fin.close();

        }

        FileOutputStream fout = new FileOutputStream(installationinfo);
        ObjectOutputStream oout = new ObjectOutputStream(fout);
        oout.writeObject(installedpacks);
        /*
        int selectedpackscount = installData.selectedPacks.size();
        for (int i = 0; i < selectedpackscount; i++)
        {
            Pack pack = (Pack) installData.selectedPacks.get(i);
            oout.writeObject(pack);
        }
        */
        oout.writeObject(idata.getVariables());
        Debug.trace("done.");
        oout.close();
        fout.close();
    }

    protected File getAbsolutInstallSource() throws Exception
    {
        if (absolutInstallSource == null)
        {
            URI uri = getClass().getProtectionDomain().getCodeSource().getLocation().toURI();
            if (!"file".equals(uri.getScheme()))
            {
                throw new Exception("Unexpected scheme in JAR file URI: " + uri);
            }
            absolutInstallSource = new File(uri.getSchemeSpecificPart()).getAbsoluteFile();
            if (absolutInstallSource.getName().endsWith(".jar"))
            {
                absolutInstallSource = absolutInstallSource.getParentFile();
            }
        }
        return absolutInstallSource;
    }

    protected boolean blockableForCurrentOs(PackFile pf)
    {
        return
                (pf.blockable() != Blockable.BLOCKABLE_NONE)
                        && (OsVersion.IS_WINDOWS);
    }
}

