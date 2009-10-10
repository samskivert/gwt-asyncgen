//
// $Id$

package com.samskivert.asyncgen;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

        // resolve the RemoteService class using our classloader
        try {
            _rsclass = _cloader.loadClass("com.google.gwt.user.client.rpc.RemoteService");
            _acclass = _cloader.loadClass("com.google.gwt.user.client.rpc.AsyncCallback");
        } catch (Exception e) {
            throw new BuildException("Can't resolve GWT classes. Is gwt-user.jar in classpath?", e);
        }

        // read in our template file
        InputStream in;
        try {
            if (_tmpl == null) {
                in = getClass().getClassLoader().getResourceAsStream("gwt-asyncgen.tmpl");
            } else {
                in = new FileInputStream(_tmpl);
            }
            StringBuilder tdata = new StringBuilder();
            for (String line : toList(in)) {
                if (line.trim().equals("@METHOD_START@")) {
                    _tmplpre = tdata.toString();
                    tdata = new StringBuilder();
                } else if (line.trim().equals("@METHOD_END@")) {
                    _tmplmeth = tdata.toString();
                    tdata = new StringBuilder();
                } else {
                    tdata.append(line).append(LINESEP);
                }
            }
            _tmplpost = tdata.toString();
        } catch (Exception e) {
            throw new BuildException("Failure reading template file: " + e.getMessage(), e);
        }

        for (FileSet fs : _filesets) {
            DirectoryScanner ds = fs.getDirectoryScanner(getProject());
            File fromDir = fs.getDir(getProject());
            String[] srcFiles = ds.getIncludedFiles();
            for (String srcFile : srcFiles) {
                processInterface(new File(fromDir, srcFile));
            }
        }
    }

    protected void processInterface (File source)
    {
        // load up the file and determine it's package and classname
        String name = null;
        try {
            // System.err.println("Processing " + source + "...");
            List<String> slines = toList(new FileInputStream(source));
            name = findClassName(source, slines);
            processInterface(source, slines, _cloader.loadClass(name));
        } catch (ClassNotFoundException cnfe) {
            System.err.println("Failed to load " + name + ".\n" +
                               "Missing class: " + cnfe.getMessage());
            System.err.println("Be sure to set the 'classpathref' attribute to a classpath\n" +
                               "that contains your projects invocation service classes.");
        } catch (Exception e) {
            throw new BuildException("Failed to process " + source.getName() + ": " + e, e);
        }
    }

    protected void processInterface (File source, List<String> slines, Class<?> oclass)
    {
        if (!_rsclass.isAssignableFrom(oclass)) { // make sure we extend RemoteService
            System.err.println("Skipping non-RemoteService: " + oclass.getName());
            return;
        }

        File asource = new File(source.getParent(), oclass.getSimpleName() + "Async.java");
        if (source.lastModified() < asource.lastModified()) {
            // System.out.println(oclass.getSimpleName() + " not modified. Skipping.");
            return;
        }

        // System.out.println("Processing " + oclass.getName() + "...");
        Map<String, String> repls = new HashMap<String, String>();
        repls.put("SERVICE", oclass.getSimpleName());
        repls.put("PACKAGE", oclass.getPackage().getName());
        Set<Type> imports = new HashSet<Type>();
        imports.add(_acclass);

        StringBuilder methods = new StringBuilder();
        for (Method m : oclass.getDeclaredMethods()) {
            if (methods.length() > 0) {
                methods.append(LINESEP);
            }
            repls.put("METHOD_NAME", m.getName());
            StringBuilder args = new StringBuilder();
            List<String> atypes = new ArrayList<String>();
            for (Type type : m.getGenericParameterTypes()) {
                imports.add(type);
                atypes.add(simpleName(type));
            }
            atypes.add("AsyncCallback<" + simpleName(box(m.getGenericReturnType())) + ">");
            imports.add(m.getGenericReturnType());
            List<String> anames = getArgNames(oclass.getSimpleName(), slines, m.getName());
            for (int ii = 0; ii < atypes.size(); ii++) {
                if (ii > 0) {
                    args.append(", ");
                }
                args.append(atypes.get(ii)).append(" ");
                if (ii == (atypes.size()-1) && !anames.contains("callback")) {
                    args.append("callback");
                } else if (anames.size() <= ii) {
                    args.append("arg").append(ii);
                } else {
                    args.append(anames.get(ii));
                }
            }
            repls.put("METHOD_ARGS", args.toString());
            methods.append(replace(repls, _tmplmeth));
        }

        repls.remove("METHOD_NAME");
        repls.remove("METHOD_ARGS");

        Set<Class<?>> redimps = new TreeSet<Class<?>>(new Comparator<Class<?>>() {
            public int compare (Class<?> c1, Class<?> c2) {
                // hackily put Java above everything else
                boolean java1 = c1.getName().startsWith("java");
                boolean java2 = c2.getName().startsWith("java");
                return (java1 == java2) ? c1.getName().compareTo(c2.getName()) : (java1 ? -1 : 1);
            }
        });
        reduceImports(imports, redimps);

        StringBuilder imptext = new StringBuilder();
        for (Class<?> imp : redimps) {
            if (imp.getPackage().equals(oclass.getPackage())) {
                continue;
            }
            if (imptext.length() > 0) {
                imptext.append(LINESEP);
            }
            imptext.append("import ").append(imp.getName()).append(";");
        }
        repls.put("IMPORTS", imptext.toString());

        System.out.println("Updating " + asource.getName() + "...");
        try {
            FileWriter fout = new FileWriter(asource);
            fout.write(replace(repls, _tmplpre) + methods + replace(repls, _tmplpost));
            fout.close();
        } catch (IOException ioe) {
            System.err.println("Failed to write " + asource + ": " + ioe.getMessage());
        }
    }

    protected static void reduceImports (Iterable<Type> imports, Set<Class<?>> redimps)
    {
        for (Type type : imports) {
            while (type instanceof GenericArrayType) {
                type = ((GenericArrayType)type).getGenericComponentType();
            }

            if (type instanceof ParameterizedType) {
                ParameterizedType pt = (ParameterizedType)type;
                reduceImports(Arrays.asList(pt.getActualTypeArguments()), redimps);
                type = pt.getRawType();
            }

            if (type instanceof WildcardType) {
                WildcardType wt = (WildcardType)type;
                reduceImports(Arrays.asList(wt.getUpperBounds()), redimps);
                reduceImports(Arrays.asList(wt.getLowerBounds()), redimps);
                continue; // no raw type
            }

            if (type instanceof Class<?>) {
                Class<?> clazz = (Class<?>)type;
                if (!clazz.isPrimitive() && !clazz.getName().startsWith("java.lang.")) {
                    redimps.add(clazz);
                }
            }
        }
    }

    protected static String replace (Map<String, String> repls, String text)
    {
        for (Map.Entry<String, String> repl : repls.entrySet()) {
            String key = "@" + repl.getKey() + "@";
            while (text.indexOf(key) != -1) {
                text = text.replace(key, repl.getValue());
            }
        }
        return text;
    }

    protected static Type box (Type type)
    {
        return (type instanceof Class<?> && ((Class<?>)type).isPrimitive()) ?
            BOXED.get(type) : type;
    }

    protected static List<String> toList (InputStream source)
        throws IOException
    {
        BufferedReader bin = new BufferedReader(new InputStreamReader(source));
        String line;
        List<String> list = new ArrayList<String>();
        while ((line = bin.readLine()) != null) {
            list.add(line);
        }
        bin.close();
        return list;
    }

    protected static String findClassName (File source, List<String> lines)
        throws IOException
    {
        // load up the file and determine it's package and classname
        String pkgname = null, name = null;
        for (String line : lines) {
            Matcher pm = PACKAGE_PATTERN.matcher(line);
            if (pm.find()) {
                pkgname = pm.group(1);
            }
            Matcher nm = NAME_PATTERN.matcher(line);
            if (nm.find()) {
                name = nm.group(1);
                break;
            }
        }
        // make sure we found something
        if (name == null) {
            throw new IOException("Unable to locate class or interface name in " + source + ".");
        }
        // prepend the package name to get a name we can Class.forName()
        if (pkgname != null) {
            name = pkgname + "." + name;
        }
        return name;
    }

    protected static String simpleName (Type type)
    {
        if (type instanceof GenericArrayType) {
            return simpleName(((GenericArrayType)type).getGenericComponentType()) + "[]";
        } else if (type instanceof Class<?>) {
            Class<?> clazz = (Class<?>)type;
            if (clazz.isArray()) {
                return simpleName(clazz.getComponentType()) + "[]";
            } else {
                Package pkg = clazz.getPackage();
                int offset = (pkg == null) ? 0 : pkg.getName().length()+1;
                return clazz.getName().substring(offset).replaceAll("\\$", ".");
            }

        } else if (type instanceof ParameterizedType) {
            ParameterizedType pt = (ParameterizedType)type;
            StringBuilder buf = new StringBuilder();
            for (Type arg : pt.getActualTypeArguments()) {
                if (buf.length() > 0) {
                    buf.append(", ");
                }
                buf.append(simpleName(arg));
            }
            return simpleName(pt.getRawType()) + "<" + buf + ">";

        } else if (type instanceof WildcardType) {
            WildcardType wt = (WildcardType)type;
            if (wt.getLowerBounds().length > 0) {
                String errmsg = "Generation of simple name for wildcard type with lower bounds " +
                    "not implemented [type=" + type + ", lower=" + wt.getLowerBounds() + "]";
                throw new IllegalArgumentException(errmsg);
            }
            if (wt.getUpperBounds().length > 1) {
                String errmsg = "Generation of simple name for wildcard type with multiple upper " +
                    "bounds not implemented [type=" + type + ", up=" + wt.getUpperBounds() + "]";
                throw new IllegalArgumentException(errmsg);
            }
            StringBuilder buf = new StringBuilder("?");
            if (!Object.class.equals(wt.getUpperBounds()[0])) {
                buf.append(" extends ").append(simpleName(wt.getUpperBounds()[0]));
            }
            return buf.toString();

        } else {
            throw new IllegalArgumentException("Can't generate simple name [type=" + type + "]");
        }
    }

    protected List<String> getArgNames (String sclass, List<String> slines, String methodName)
    {
        Pattern p = Pattern.compile(".*\\s+" + methodName + "\\s*\\(([^)]*)\\).*");
        Pattern halfp = Pattern.compile(".*\\s+" + methodName + "\\s*\\([^)]*");
        for (int ii = 0, ll = slines.size(); ii < ll; ii++) {
            String line = slines.get(ii);
            Matcher m = p.matcher(line);
            if (m.matches()) {
                return toArgNames(m.group(1));
            }

            Matcher hm = halfp.matcher(line);
            for (int cc = ii+1; hm.matches() && cc < slines.size(); cc++) {
                line += slines.get(cc).trim();
                m = p.matcher(line);
                if (m.matches()) {
                    return toArgNames(m.group(1));
                }
                hm = halfp.matcher(line);
            }
        }

        System.out.println("Failed to find arg names for " + sclass + "." + methodName + ". " +
                           "Is it formatted weirdly?");
        return new ArrayList<String>();
    }

    protected static List<String> toArgNames (String args)
    {
        List<String> anames = new ArrayList<String>();
        for (String arg : args.split(",")) {
            int sidx = arg.lastIndexOf(" ");
            arg = arg.substring(sidx+1).trim();
            anames.add(arg);
        }
        return anames;
    }

    protected List<FileSet> _filesets = new ArrayList<FileSet>();
    protected ClassLoader _cloader;
    protected Class<?> _rsclass;
    protected Class<?> _acclass;
    protected File _tmpl;
    protected String _tmplpre, _tmplmeth, _tmplpost;

    /** The platform-specific line separator character. */
    protected static final String LINESEP = System.getProperty("line.separator");

    /** A regular expression for matching the package declaration. */
    protected static final Pattern PACKAGE_PATTERN = Pattern.compile("^\\s*package\\s+(\\S+)\\W");

    /** A regular expression for matching the interface declaration. */
    protected static final Pattern NAME_PATTERN =
        Pattern.compile("^\\s*public\\s+interface\\s+(\\S+)(\\W|$)");

    /** A mapping from primitive types to their boxed counterparts. */
    protected static Map<Type, Type> BOXED = new HashMap<Type, Type>();
    static {
        BOXED.put(Void.TYPE, Void.class);
        BOXED.put(Boolean.TYPE, Boolean.class);
        BOXED.put(Byte.TYPE, Byte.class);
        BOXED.put(Short.TYPE, Short.class);
        BOXED.put(Integer.TYPE, Integer.class);
        BOXED.put(Long.TYPE, Long.class);
        BOXED.put(Float.TYPE, Float.class);
        BOXED.put(Double.TYPE, Double.class);
    }
}
