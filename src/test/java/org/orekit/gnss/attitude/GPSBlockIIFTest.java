/* Copyright 2002-2018 CS Systèmes d'Information
 * Licensed to CS Systèmes d'Information (CS) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * CS licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.orekit.gnss.attitude;

import org.junit.Test;
import org.orekit.errors.OrekitException;
import org.orekit.frames.Frame;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.PVCoordinatesProvider;


public class GPSBlockIIFTest extends AbstractGNSSAttitudeProviderTest {

    protected GNSSAttitudeProvider createProvider(final AbsoluteDate validityStart,
                                                  final AbsoluteDate validityEnd,
                                                  final PVCoordinatesProvider sun,
                                                  final Frame inertialFrame,
                                                  final int prnNumber) {
        return new GPSBlockIIF(validityStart, validityEnd, sun, inertialFrame);
    }

    @Test
    public void testLargeNegativeBeta() throws OrekitException {
        doTestAxes("beta-large-negative-BLOCK-IIF.txt",  1.1e-15, 8.9e-16, 4.2e-16);
    }

    @Test
    public void testSmallNegativeBeta() throws OrekitException {
        doTestAxes("beta-small-negative-BLOCK-IIF.txt", 7.1e-13, 7.1e-13, 9.2e-16);
    }

    @Test
    public void testCrossingBeta() throws OrekitException {
        // TODO: these results are not good,
        // however the reference data is also highly suspicious
        // this needs to be investigated
        doTestAxes("beta-crossing-BLOCK-IIF.txt", 2.8, 2.8, 7.8e-16);
    }

    @Test
    public void testSmallPositiveBeta() throws OrekitException {
        doTestAxes("beta-small-positive-BLOCK-IIF.txt", 1.2e-12, 1.2e-12, 3.7e-16);
    }

    @Test
    public void testLargePositiveBeta() throws OrekitException {
        doTestAxes("beta-large-positive-BLOCK-IIF.txt", 1.1e-15, 7.7e-16, 3.2e-16);
    }

}