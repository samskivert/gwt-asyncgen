//
// $Id$

package com.samskivert.asyncgen;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.apache.tools.ant.AntClassLoader;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.DirectoryScanner;
import org.apache.tools.ant.Task;
import org.apache.tools.ant.types.FileSet;
import org.apache.tools.ant.types.Reference;
import org.apache.tools.ant.util.ClasspathUtils;

/**
 * Generates Async interfaces for GWT RemoteService interfaces.
 */
public class AsyncGenTask extends Task
{
    /**
     * Adds a nested &lt;fileset&gt; element which enumerates service declaration source files.
     */
    public void addFileset (FileSet set)
    {
        _filesets.add(set);
    }

    /**
     * Configures our classpath which we'll use to load service classes.
     */
    public void setClasspathref (Reference pathref)
    {
        _cloader = ClasspathUtils.getClassLoaderForPath(getProject(), pathref);
    }

    /**
     * Configures our generated service template file.
     */
    public void setTemplate (File template)
    {
        _tmpl = template;
    }

    @Override // from Task
    public void execute ()
    {
        if (_cloader == null) {
            throw new BuildException("The 'classpathref' attribute must be set to a classpath.");
        }

        // initialize our shared bits
        AsyncGenerator genner = new AsyncGenerator(_cloader, _tmpl) {
            protected RuntimeException fail (String message, Throwable cause) {
                return new BuildException(message, cause);
            }
        };

        for (FileSet fs : _filesets) {
            DirectoryScanner ds = fs.getDirectoryScanner(getProject());
            File fromDir = fs.getDir(getProject());
            String[] srcFiles = ds.getIncludedFiles();
            for (String srcFile : srcFiles) {
                genner.processInterface(new File(fromDir, srcFile));
            }
        }
    }

    protected List<FileSet> _filesets = new ArrayList<FileSet>();
    protected ClassLoader _cloader;
    protected File _tmpl;
}
