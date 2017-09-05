/* Copyright 2002-2017 CS Systèmes d'Information
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
package org.orekit.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

import org.orekit.errors.OrekitException;
import org.orekit.errors.OrekitExceptionWrapper;
import org.orekit.time.AbsoluteDate;


/** Class managing several {@link ParameterDriver parameter drivers},
 * taking care of duplicated names.
 * <p>
 * Once parameter drivers sharing the same name have been added to
 * an instance of this class, they are permanently bound together and
 * also bound to the {@link #getDrivers() delegating driver} that
 * manages them. This means that if drivers {@code d1}, {@code d2}...
 * {@code dn} are added to the list and both correspond to parameter
 * name "P", then {@link #getDrivers()} will return a list containing
 * a delegating driver {@code delegateD} for the same name "P".
 * Afterwards, whenever either {@link ParameterDriver#setValue(double)}
 * or {@link ParameterDriver#setReferenceDate(AbsoluteDate)} is called
 * on any of the {@code n+1} instances {@code d1}, {@code d2}... {@code dn}
 * or {@code delegateD}, the call will be automatically forwarded to the
 * {@code n} remaining instances, hence ensuring they remain consistent
 * with each other.
 * </p>
 * @author Luc Maisonobe
 * @since 8.0
 */
public class ParameterDriversList {

    /** Managed drivers. */
    private final List<DelegatingDriver> delegating;

    /** Creates an empty list.
     */
    public ParameterDriversList() {
        this.delegating = new ArrayList<DelegatingDriver>();
    }

    /** Add a driver.
     * <p>
     * If the driver is already present, it will not be added.
     * If another driver managing the same parameter is present,
     * both drivers will be managed together, existing drivers
     * being set to the value of the last driver added (i.e.
     * each addition overrides the parameter value).
     * </p>
     * @param driver driver to add
     * @exception OrekitException if an existing driver for the
     * same parameter throws one when its value is reset using the
     * new driver value
     */
    public void add(final ParameterDriver driver) throws OrekitException {

        for (final DelegatingDriver d : delegating) {
            if (d.getName().equals(driver.getName())) {
                // the parameter is already managed by existing drivers

                for (final ParameterDriver existing : d.drivers) {
                    if (existing == driver) {
                        // the driver is already known, don't duplicate it
                        return;
                    }
                }

                // this is a new driver for an already managed parameter
                d.add(driver);
                return;

            }
        }

        // this is the first driver we have for this parameter name
        delegating.add(new DelegatingDriver(driver));

    }

    /** Sort the parameters lexicographically.
     */
    public void sort() {
        Collections.sort(delegating, new Comparator<DelegatingDriver>() {
            /** {@inheritDoc} */
            @Override
            public int compare(final DelegatingDriver d1, final DelegatingDriver d2) {
                return d1.getName().compareTo(d2.getName());
            }
        });
    }

    /** Filter parameters to keep only one type of selection status.
     * @param selected if true, only {@link ParameterDriver#isSelected()
     * selected} parameters will be kept, the other ones will be removed
     */
    public void filter(final boolean selected) {
        for (final Iterator<DelegatingDriver> iterator = delegating.iterator(); iterator.hasNext();) {
            if (iterator.next().isSelected() != selected) {
                iterator.remove();
            }
        }
    }

    /** Get the number of parameters with different names.
     * @return number of parameters with different names
     */
    public int getNbParams() {
        return delegating.size();
    }

    /** Get delegating drivers for all parameters.
     * <p>
     * The delegating drivers are <em>not</em> the same as
     * the drivers added to the list, but they delegate to them.
     * </p>
     * <p>
     * All delegating drivers manage parameters with different names.
     * </p>
     * @return unmodifiable view of the list of delegating drivers
     */
    public List<DelegatingDriver> getDrivers() {
        return Collections.unmodifiableList(delegating);
    }

    /** Specialized driver delegating to several other managing
     * the same parameter name.
     */
    public static class DelegatingDriver extends ParameterDriver {

        /** Drivers managing the same parameter. */
        private final List<ParameterDriver> drivers;

        /** Observer for propagating changes between all drivers. */
        private final ChangesForwarder forwarder;

        /** Simple constructor.
         * @param driver first driver in the series
         * @exception OrekitException if first drivers throws one
         */
        DelegatingDriver(final ParameterDriver driver) throws OrekitException {
            super(driver.getName(), driver.getReferenceValue(),
                  driver.getScale(), driver.getMinValue(), driver.getMaxValue());
            drivers = new ArrayList<ParameterDriver>();
            drivers.add(driver);

            setValue(driver.getValue());
            setReferenceDate(driver.getReferenceDate());
            setSelected(driver.isSelected());

            // when the value or reference date of the delegating driver change,
            // all underlying drivers must reproduce the change
            forwarder = new ChangesForwarder();

            addObserver(forwarder);
            driver.addObserver(forwarder);

        }

        /** Add a driver.
         * @param driver driver to add
         * @exception OrekitException if an existing drivers cannot be set to the same value
         */
        private void add(final ParameterDriver driver)
            throws OrekitException {

            setValue(driver.getValue());
            setReferenceDate(driver.getReferenceDate());

            // if any of the drivers is selected, all must be selected
            if (isSelected()) {
                driver.setSelected(true);
            } else {
                setSelected(driver.isSelected());
            }

            driver.addObserver(forwarder);
            drivers.add(driver);

        }

        /** Get the raw drivers to which this one delegates.
         * <p>
         * These raw drivers all manage the same parameter name.
         * </p>
         * @return raw drivers to which this one delegates
         */
        public List<ParameterDriver> getRawDrivers() {
            return Collections.unmodifiableList(drivers);
        }

        /** Local observer for propagating changes, avoiding infinite recursion. */
        private class ChangesForwarder implements ParameterObserver {

            /** Root of the current update chain. */
            private ParameterDriver root;

            /** Depth of the current update chain. */
            private int depth;

            //debug
            int count;
            //debug

            /** {@inheritDoc} */
            @Override
            public void valueChanged(final double previousValue, final ParameterDriver driver)
                throws OrekitException {
                try {
                    updateAll(driver, d -> {
                        try {
                            d.setValue(driver.getValue());
                        } catch (OrekitException oe) {
                            throw new OrekitExceptionWrapper(oe);
                        }
                    });
                } catch (OrekitExceptionWrapper oew) {
                    throw oew.getException();
                }
            }

            /** {@inheritDoc} */
            @Override
            public void referenceDateChanged(final AbsoluteDate previousReferenceDate, final ParameterDriver driver) {
                updateAll(driver, d -> d.setReferenceDate(driver.getReferenceDate()));
            }

            /** {@inheritDoc} */
            @Override
            public void nameChanged(final String previousName, final ParameterDriver driver) {
                updateAll(driver, d -> d.setName(driver.getName()));
            }

            /** {@inheritDoc} */
            @Override
            public void selectionChanged(final boolean previousSelection, final ParameterDriver driver) {
                updateAll(driver, d -> d.setSelected(driver.isSelected()));
            }

            /** Update all bound parameters.
             * @param driver driver triggering the update
             * @param updater updater to use
             */
            private void updateAll(final ParameterDriver driver, final Updater updater) {

                //debug
                System.out.println(count++);
                //debug

                final boolean firstCall = depth++ == 0;
                if (firstCall) {
                    root = driver;
                }

                if (driver == DelegatingDriver.this) {
                    // propagate change downwards, which will trigger recursive calls
                    for (final ParameterDriver d : drivers) {
                        if (d != root) {
                            updater.update(d);
                        }
                    }
                } else if (firstCall) {
                    // first call started from an underlying driver, propagate change upwards
                    updater.update(DelegatingDriver.this);
                }

                if (--depth == 0) {
                    // this is the end of the root call
                    root = null;
                }

            }

        }

    }

    /** Interface for updating parameters. */
    @FunctionalInterface
    private interface Updater {
        /** Update a driver.
         * @param driver driver to update
         */
        void update(ParameterDriver driver);
    }

}
