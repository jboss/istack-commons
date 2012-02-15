/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 1997-2010 Oracle and/or its affiliates. All rights reserved.
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

package com.sun.istack.build;

import com.sun.codemodel.JClass;
import com.sun.codemodel.JClassAlreadyExistsException;
import com.sun.codemodel.JCodeModel;
import com.sun.codemodel.JDefinedClass;
import com.sun.codemodel.JExpr;
import com.sun.codemodel.JFieldVar;
import com.sun.codemodel.JInvocation;
import com.sun.codemodel.JMethod;
import com.sun.codemodel.JMod;
import com.sun.codemodel.JPackage;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.DirectoryScanner;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.Task;
import org.apache.tools.ant.types.FileSet;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Generate source files from resource bundles,
 * so that code can refer to resources as methods,
 * instead of hard-coding string constants, which is
 * much harder to search.
 *
 * @author Kohsuke Kawaguchi
 */
public class ResourceGenTask extends Task {
    /**
     * Resource files to be compiled
     */
    private FileSet resources;

    private File destDir;

    public void addConfiguredResource( FileSet fs ) {
        resources = fs;
    }

    public void setDestDir(File dir) {
        this.destDir = dir;
    }

    public void execute() throws BuildException {
        if(resources==null)
            throw new BuildException("No resource file is specified");
        if(destDir==null)
            throw new BuildException("No destdir attribute is specified");

        destDir.mkdirs();

        JCodeModel cm = new JCodeModel();

        DirectoryScanner ds = resources.getDirectoryScanner(getProject());
        String[] includedFiles = ds.getIncludedFiles();
        File baseDir = ds.getBasedir();

        for (String value : includedFiles) {
            File res = new File(baseDir, value);

            if(res.getName().contains("_"))
                continue;   // this is a localized bundle, so ignore.

            String className = getClassName(res);

            String bundleName = value.substring(0, value.lastIndexOf('.')).replace('/', '.').replace('\\', '.');// cut off '.properties'
            String dirName = bundleName.substring(0, bundleName.lastIndexOf('.'));

            File destFile = new File(new File(destDir,dirName.replace('.','/')),className+".java");
            if(destFile.lastModified() >= res.lastModified()) {
                log("Skipping "+res,Project.MSG_INFO);
                continue;
            }

            log("Processing "+res,Project.MSG_INFO);
            JPackage pkg = cm._package(dirName);

            Properties props = new Properties();
            try {
                FileInputStream in = new FileInputStream(res);
                props.load(in);
                in.close();
            } catch (IOException e) {
                throw new BuildException(e);
            }

            JDefinedClass clazz;
            try {
                clazz = pkg._class(JMod.PUBLIC | JMod.FINAL, className);
            } catch (JClassAlreadyExistsException e) {
                throw new BuildException("Name conflict "+className);
            }

            clazz.javadoc().add(
                "Defines string formatting method for each constant in the resource file"
            );

            /*
              [RESULT]

                LocalizableMessageFactory messageFactory =
                    new LocalizableMessageFactory("com.sun.xml.ws.resources.client");
                Localizer localizer = new Localizer();
            */

            JClass lmf_class;
            JClass l_class;
            JClass lable_class;
            try {
                lmf_class = cm.parseType("com.sun.istack.localization.LocalizableMessageFactory").boxify();
                l_class = cm.parseType("com.sun.istack.localization.Localizer").boxify();
                lable_class = cm.parseType("com.sun.istack.localization.Localizable").boxify();
            } catch (ClassNotFoundException e) {
                throw new BuildException(e); // impossible -- but why parseType throwing ClassNotFoundExceptoin!?
            }

            JFieldVar $msgFactory = clazz.field(JMod.PRIVATE|JMod.STATIC|JMod.FINAL,
                lmf_class, "messageFactory", JExpr._new(lmf_class).arg(JExpr.lit(bundleName)));

            JFieldVar $localizer = clazz.field(JMod.PRIVATE|JMod.STATIC|JMod.FINAL,
                l_class, "localizer", JExpr._new(l_class));

            for (Object key : props.keySet()) {
                // [RESULT]
                // Localizable METHOD_localizable(Object arg1, Object arg2, ...) {
                //   return messageFactory.getMessage("servlet.html.notFound", message));
                // }
                // String METHOD(Object arg1, Object arg2, ...) {
                //   return localizer.localize(METHOD_localizable(arg1,arg2,...));
                // }
                String methodBaseName = NameConverter.smart.toConstantName(key.toString());

                JMethod method = clazz.method(JMod.PUBLIC | JMod.STATIC, lable_class, "localizable"+methodBaseName);

                int countArgs = countArgs(props.getProperty(key.toString()));

                JInvocation format = $msgFactory.invoke("getMessage").arg(
                    JExpr.lit(key.toString()));

                for( int i=0; i<countArgs; i++ ) {
                    format.arg( method.param(Object.class,"arg"+i));
                }
                method.body()._return(format);

                JMethod method2 = clazz.method(JMod.PUBLIC|JMod.STATIC, String.class, methodBaseName);
                method2.javadoc().add(props.get(key));

                JInvocation localize = JExpr.invoke(method);
                for( int i=0; i<countArgs; i++ ) {
                    localize.arg( method2.param(Object.class,"arg"+i));
                }

                method2.body()._return($localizer.invoke("localize").arg(localize));
            }
        }

        try {
            cm.build(destDir);
        } catch (IOException e) {
            throw new BuildException("Failed to generate code",e);
        }
    }

    /**
     * Counts the number of arguments.
     */
    private int countArgs(String value) {
        List<String> x = new ArrayList<String>();

        while(true) {
            String r1 = MessageFormat.format(value, x.toArray());
            x.add("xxxx");
            String r2 = MessageFormat.format(value, x.toArray());

            if(r1.equals(r2))
                return x.size()-1;
        }
    }

    /**
     * Computes the class name from the resource bundle name.
     */
    private String getClassName(File res) {
        String name = res.getName();
        int suffixIndex = name.lastIndexOf('.');
        name = name.substring(0,suffixIndex);
        return NameConverter.smart.toClassName(name)+"Messages";
    }
}
