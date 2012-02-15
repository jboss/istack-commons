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

package com.sun.istack.test;

import java.util.StringTokenizer;

/**
 * Immutable representation of a dot-separated digits (such as "1.0.1").
 *
 * {@link VersionNumber}s are {@link Comparable}.
 *
 * <p>
 * We allow a component to be not just a number, but also "ea", "ea1", "ea2".
 * "ea" is treated as "ea0", and eaN &lt; M for any M > 0.
 *
 * <p>
 * '*' is also allowed as a component, and '*' > M for any M > 0.
 *
 * <pre>
 * 2.0.* > 2.0.1 > 2.0.0 > 2.0.ea > 2.0
 * </pre>
 *
 * @author
 *     Kohsuke Kawaguchi (kohsuke.kawaguchi@sun.com)
 */
public class VersionNumber implements Comparable<VersionNumber> {

    public static final VersionNumber v1_0   = new VersionNumber("1.0");
    public static final VersionNumber v1_0_1 = new VersionNumber("1.0.1");
    public static final VersionNumber v1_0_2 = new VersionNumber("1.0.2");
    public static final VersionNumber v1_0_3 = new VersionNumber("1.0.3");
    public static final VersionNumber v2_0   = new VersionNumber("2.0");
    public static final VersionNumber v2_1   = new VersionNumber("2.1");

    private final int[] digits;

    /**
     * Parses a string like "1.0.2" into the version number.
     *
     * @throws IllegalArgumentException
     *      if the parsing fails.
     */
    public VersionNumber( String num ) {
        StringTokenizer tokens = new StringTokenizer(num,".");
        digits = new int[tokens.countTokens()];
        if(digits.length<2)
            throw new IllegalArgumentException();

        int i=0;
        while( tokens.hasMoreTokens() ) {
            String token = tokens.nextToken().toLowerCase();
            if(token.equals("*")) {
                digits[i++] = 1000;
            } else
            if(token.startsWith("ea")) {
                if(token.length()==2)
                    digits[i++] = -1000;    // just "ea"
                else
                    digits[i++] = -1000 + Integer.parseInt(token.substring(2)); // "eaNNN"
            } else {
                digits[i++] = Integer.parseInt(token);
            }
        }
    }

    public String toString() {
        StringBuffer buf = new StringBuffer();
        for( int i=0; i<digits.length; i++ ) {
            if(i!=0)    buf.append('.');
            buf.append( Integer.toString(digits[i]) );
        }
        return buf.toString();
    }

    public boolean isOlderThan( VersionNumber rhs ) {
        return compareTo(rhs)<0;
    }

    public boolean isNewerThan( VersionNumber rhs ) {
        return compareTo(rhs)>0;
    }


    public boolean equals( Object o ) {
        return compareTo((VersionNumber)o)==0;
    }

    public int hashCode() {
        int x=0;
        for (int i : digits)
            x = (x << 1) | i;
        return x;
    }

    public int compareTo(VersionNumber rhs) {
        for( int i=0; ; i++ ) {
            if( i==this.digits.length && i==rhs.digits.length )
                return 0;   // equals
            if( i==this.digits.length )
                return -1;  // rhs is larger
            if( i==rhs.digits.length )
                return 1;

            int r = this.digits[i] - rhs.digits[i];
            if(r!=0)    return r;
        }
    }
}
