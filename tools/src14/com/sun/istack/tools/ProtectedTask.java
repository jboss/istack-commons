/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 1997-2011 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package com.sun.istack.tools;

import java.io.Closeable;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.DynamicConfigurator;
import org.apache.tools.ant.IntrospectionHelper;
import org.apache.tools.ant.Task;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Iterator;
import java.util.Map.Entry;

/**
 * Executes a {@link Task} in a special class loader that allows
 * us to control where to load 2.1 APIs, even if we run in Java 6.
 *
 * <p>
 * No JDK 1.5 code here, please. This allows us to detect "require JDK5" bug nicely.
 *
 * @author Kohsuke Kawaguchi
 * @author Bhakti Mehta
 */
public abstract class ProtectedTask extends Task implements DynamicConfigurator {

    private final AntElement root = new AntElement("root");

    public void setDynamicAttribute(String name, String value) throws BuildException {
        root.setDynamicAttribute(name,value);
    }

    public Object createDynamicElement(String name) throws BuildException {
        return root.createDynamicElement(name);
    }

    public void execute() throws BuildException {
        //Leave XJC2 in the publicly visible place
        // and then isolate XJC1 in a child class loader,
        // then use a MaskingClassLoader
        // so that the XJC2 classes in the parent class loader
        //  won't interfere with loading XJC1 classes in a child class loader
        ClassLoader ccl = SecureLoader.getContextClassLoader();
        try {
            ClassLoader cl = createClassLoader();
            Class driver = cl.loadClass(getCoreClassName());

            Task t = (Task)driver.newInstance();
            t.setProject(getProject());
            t.setTaskName(getTaskName());
            root.configure(t);

            SecureLoader.setContextClassLoader(cl);
            t.execute();
            
            driver = null;
            t.setTaskName(null);
            t.setProject(null);
            t = null;
        } catch (UnsupportedClassVersionError e) {
            throw new BuildException("Requires JDK 5.0 or later. Please download it from http://java.sun.com/j2se/1.5/");
        } catch (ClassNotFoundException e) {
            throw new BuildException(e);
        } catch (InstantiationException e) {
            throw new BuildException(e);
        } catch (IllegalAccessException e) {
            throw new BuildException(e);
        } catch (IOException e) {
            throw new BuildException(e);
        } finally {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            SecureLoader.setContextClassLoader(ccl);
            
            //close/cleanup all classloaders but the one which loaded this class
            while (cl != null && !ccl.equals(cl)) {
                if (cl instanceof Closeable) {
                    //JDK7+, ParallelWorldClassLoader, Ant (AntClassLoader5)
                    try {
                        ((Closeable) cl).close();
                    } catch (IOException ex) {
                        throw new BuildException(ex);
                    }
                } else {
                    if (cl instanceof URLClassLoader) {
                        //JDK6 - API jars are loaded by instance of URLClassLoader
                        //so use proprietary API to release holded resources
                        try {
                            Class clUtil = ccl.loadClass("sun.misc.ClassLoaderUtil");
                            Method release = clUtil.getDeclaredMethod("releaseLoader", URLClassLoader.class);
                            release.invoke(null, cl);
                        } catch (ClassNotFoundException ex) {
                            //not Sun JDK 6, ignore
                        } catch (IllegalAccessException ex) {
                            throw new BuildException(ex);
                        } catch (IllegalArgumentException ex) {
                            throw new BuildException(ex);
                        } catch (InvocationTargetException ex) {
                            throw new BuildException(ex);
                        } catch (NoSuchMethodException ex) {
                            throw new BuildException(ex);
                        } catch (SecurityException ex) {
                            throw new BuildException(ex);
                        }
                    }
                }
                cl = cl.getParent();
            }
            cl = null;
        }
    }

    /**
     * Returns the name of the class that extends {@link Task}.
     * This class will be loaded int the protected classloader.
     */
    protected abstract String getCoreClassName();

    /**
     * Creates a protective class loader that will host the actual task.
     */
    protected abstract ClassLoader createClassLoader() throws ClassNotFoundException, IOException;

    /**
     * Captures the elements and attributes.
     */
    private class AntElement implements DynamicConfigurator {
        private final String name;

        private final Map/*<String,String>*/ attributes = new HashMap();

        private final List/*<AntElement>*/ elements = new ArrayList();

        public AntElement(String name) {
            this.name = name;
        }

        public void setDynamicAttribute(String name, String value) throws BuildException {
            attributes.put(name,value);
        }

        public Object createDynamicElement(String name) throws BuildException {
            AntElement e = new AntElement(name);
            elements.add(e);
            return e;
        }

        /**
         * Copies the properties into the Ant task.
         */
        public void configure(Object antObject) {
            IntrospectionHelper ih = IntrospectionHelper.getHelper(antObject.getClass());

            // set attributes first
            for (Iterator itr = attributes.entrySet().iterator(); itr.hasNext();) {
                Entry att = (Entry)itr.next();
                ih.setAttribute(getProject(), antObject, (String)att.getKey(), (String)att.getValue());
            }

            // then nested elements
            for (Iterator itr = elements.iterator(); itr.hasNext();) {
                AntElement e = (AntElement) itr.next();
                Object child = ih.createElement(getProject(), antObject, e.name);
                e.configure(child);
                ih.storeElement(getProject(), antObject, child, e.name);
            }
        }
    }
}

