/* Copyright 2002-2019 CS Systèmes d'Information
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

import java.util.List;
import java.util.Map;

import org.hipparchus.linear.Array2DRowRealMatrix;
import org.hipparchus.linear.RealMatrix;
import org.orekit.propagation.SpacecraftState;
import org.orekit.propagation.integration.AdditionalEquations;
import org.orekit.propagation.numerical.EpochDerivativesEquations;
import org.orekit.propagation.numerical.NumericalPropagator;

/**
 * Multiple shooting method applicable for trajectories, in an ephemeris model.
 * Not suited for closed orbits.
 * @see "TRAJECTORY DESIGN AND ORBIT MAINTENANCE STRATEGIES IN MULTI-BODY DYNAMICAL REGIMES by Thomas A. Pavlak, Purdue University"
 * @author William Desprats
 */
public class MultipleShooter extends AbstractMultipleShooting {

    /** Number of patch points. */
    private int npoints;

    /** Simple Constructor.
     * <p> Standard constructor for multiple shooting which can be used with the CR3BP model.</p>
     * @param initialGuessList initial patch points to be corrected.
     * @param propagatorList list of propagators associated to each patch point.
     * @param additionalEquations list of additional equations linked to propagatorList.
     * @param arcDuration initial guess of the duration of each arc.
     * @param tolerance convergence tolerance on the constraint vector
     */
    public MultipleShooter(final List<SpacecraftState> initialGuessList, final List<NumericalPropagator> propagatorList,
                           final List<AdditionalEquations> additionalEquations, final double arcDuration, final double tolerance) {
        super(initialGuessList, propagatorList, additionalEquations, arcDuration, tolerance);
        this.npoints = initialGuessList.size();
    }

    /** {@inheritDoc} */
    protected SpacecraftState getAugmentedInitialState(final SpacecraftState initialState,
                                                       final AdditionalEquations additionalEquation) {          
        return ((EpochDerivativesEquations) additionalEquation).setInitialJacobians(initialState);
    }

    /** {@inheritDoc} */
    public double[][] computeAdditionalJacobianMatrix(final List<SpacecraftState> propagatedSP) {
        final Map<Integer, Double> mapConstraints = getConstraintsMap();

        final int n = mapConstraints.size();
        final int ncolumns = getNumberOfFreeVariables() - 1;

        final double[][] M = new double[n][ncolumns];

        int k = 0;
        for (int index : mapConstraints.keySet()) {
            M[k][index] = 1;
            k++;
        }
        return M;
    }

    /** {@inheritDoc} */
    public double[][] computeEpochJacobianMatrix(final List<SpacecraftState> propagatedSP) {

        final boolean[] freeEpochMap = getFreeEpochMap();

        final int nFreeEpoch = getNumberOfFreeEpoch();
        final int ncolumns = 1 + nFreeEpoch;
        final int nrows = npoints - 1;

        final double[][] M = new double[nrows][ncolumns];

        // The Jacobian matrix has the following form:

        //      [-1 -1   1  0                 ]
        //      [-1     -1   1  0             ]
        // F =  [..          ..   ..          ]
        //      [..               ..   ..   0 ]
        //      [-1                    -1   1 ]

        int index = 1;
        for (int i = 0; i < nrows; i++) {
            M[i][0] = -1;
            if (freeEpochMap[i]) {
                M[i][index] = -1;
                index++;
            }
            if (freeEpochMap[i + 1]) {
                M[i][index] =  1;
            }
        }

        return M;
    }

    /** {@inheritDoc} */
    public double[] computeAdditionalConstraints(final List<SpacecraftState> propagatedSP) {

        // The additional constraint vector has the following form :

        //           [ y1i - y1d ]---- other constraints (component of
        // Fadd(X) = [    ...    ]    | a patch point eaquals to a
        //           [vz2i - vz2d]----  desired value)

        final Map<Integer, Double> mapConstraints = getConstraintsMap();
        // Number of additional constraints
        final int n = mapConstraints.size();

        final List<SpacecraftState> patchedSpacecraftStates = getPatchedSpacecraftState();

        final double[] fxAdditionnal = new double[n];
        int i = 0;

        for (int index : mapConstraints.keySet()) {
            final int np = (int) (index / 6);
            final int nc = index % 6;
            final AbsolutePVCoordinates absPv = patchedSpacecraftStates.get(np).getAbsPVA();
            if (nc < 3) {
                fxAdditionnal[i] = absPv.getPosition().toArray()[nc] - mapConstraints.get(index);
            } else {
                fxAdditionnal[i] = absPv.getVelocity().toArray()[nc - 3] -  mapConstraints.get(index);
            }
            i++;
        }
        return fxAdditionnal;
    }

    /** {@inheritDoc} */
    protected RealMatrix getStateTransitionMatrix(final SpacecraftState s) {
        final Map<String, double[]> map = s.getAdditionalStates();
        RealMatrix phiM = null;
        for (String name : map.keySet()) {
            if ("derivatives".equals(name)) {
                final int dim = 6;
                final double[][] phi2dA = new double[dim][dim];
                final double[] stm = map.get(name);
                for (int i = 0; i < dim; i++) {
                    for (int j = 0; j < 6; j++) {
                        phi2dA[i][j] = stm[dim * i + j];
                    }
                }
                phiM = new Array2DRowRealMatrix(phi2dA, false);
            }
        }
        return phiM;
    }
}