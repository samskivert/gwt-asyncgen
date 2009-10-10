//
// $Id$

package com.samskivert.asyncgen;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * A command line interface to the asyncgen task.
 */
public class AsyncGenTool
{
    public static void main (String[] argv)
    {
        List<String> args = new ArrayList<String>(Arrays.asList(argv));

        File tmpl = null;
        for (Iterator<String> iter = args.iterator(); iter.hasNext(); ) {
            if (iter.next().equals("-tmpl")) {
                if (!iter.hasNext()) {
                    failWithUsage();
                    return;
                }
                iter.remove();
                tmpl = new File(iter.next());
                iter.remove();
                if (!tmpl.exists()) {
                    System.err.println("Can't read template file '" + tmpl + "'.");
                    return;
                }
            }
        }

        if (args.size() == 0) {
            failWithUsage();
            return;
        }

        AsyncGenerator genner = new AsyncGenerator(AsyncGenTool.class.getClassLoader(), tmpl) {
            protected RuntimeException fail (String message, Throwable cause) {
                return new RuntimeException(message, cause);
            }
        };
        for (String arg : args) {
            genner.processInterface(new File(arg));
        }
    }

    protected static void failWithUsage ()
    {
        System.err.println(
            "Usage: AsyncGenTool [-tmpl template] FooService.java [BarService.java ...]");
        System.exit(255);
    }
}
