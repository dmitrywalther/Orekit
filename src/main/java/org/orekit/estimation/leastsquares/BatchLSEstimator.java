/* Copyright 2002-2016 CS Systèmes d'Information
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
package org.orekit.estimation.leastsquares;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.commons.math3.fitting.leastsquares.EvaluationRmsChecker;
import org.apache.commons.math3.fitting.leastsquares.LeastSquaresBuilder;
import org.apache.commons.math3.fitting.leastsquares.LeastSquaresOptimizer;
import org.apache.commons.math3.fitting.leastsquares.LeastSquaresProblem;
import org.apache.commons.math3.linear.RealVector;
import org.apache.commons.math3.optim.ConvergenceChecker;
import org.apache.commons.math3.util.Incrementor;
import org.orekit.errors.OrekitException;
import org.orekit.errors.OrekitExceptionWrapper;
import org.orekit.errors.OrekitMessages;
import org.orekit.estimation.OrbitValidator;
import org.orekit.estimation.measurements.Evaluation;
import org.orekit.estimation.measurements.Measurement;
import org.orekit.orbits.Orbit;
import org.orekit.propagation.conversion.NumericalPropagatorBuilder;
import org.orekit.utils.ParameterDriver;


/** Least squares estimator for orbit determination.
 * @author Luc Maisonobe
 * @since 7.2
 */
public class BatchLSEstimator {

    /** Builder for propagator. */
    private final NumericalPropagatorBuilder propagatorBuilder;

    /** Measurements. */
    private final List<Measurement<?>> measurements;

    /** Measurements parameters. */
    private final List<ParameterDriver> measurementsParameters;

    /** Solver for least squares problem. */
    private final LeastSquaresOptimizer optimizer;

    /** Builder for the least squares problem. */
    private final LeastSquaresBuilder lsBuilder;

    /** Oberver for iterations. */
    private BatchLSObserver observer;

    /** Last evaluations. */
    private Map<Measurement<?>, Evaluation<?>> evaluations;

    /** Last orbit. */
    private Orbit orbit;

    /** Last least squares problem evaluation. */
    private LeastSquaresProblem.Evaluation lspEvaluation;

    /** Counter for the evaluations. */
    private Incrementor evaluationsCounter;

    /** Counter for the iterations. */
    private Incrementor iterationsCounter;

    /** Simple constructor.
     * @param propagatorBuilder builder to user for propagation
     * @param optimizer solver for least squares problem
     * @exception OrekitException if some propagator parameter cannot be retrieved
     */
    public BatchLSEstimator(final NumericalPropagatorBuilder propagatorBuilder,
                            final LeastSquaresOptimizer optimizer)
        throws OrekitException {

        this.propagatorBuilder      = propagatorBuilder;
        this.measurements           = new ArrayList<Measurement<?>>();
        this.measurementsParameters = new ArrayList<ParameterDriver>();
        this.optimizer              = optimizer;
        this.lsBuilder              = new LeastSquaresBuilder();
        this.evaluations            = null;
        this.observer               = null;

        // our model computes value and Jacobian in one call,
        // so we don't use the lazy evaluation feature
        lsBuilder.lazyEvaluation(false);

        // we manage weight by ourselves, as we change them during
        // iterations (setting to 0 the identified outliers measurements)
        // so the least squares problem should not see our weights
        lsBuilder.weight(null);

    }

    /** Set an observer for iterations.
     * @param observer observer to be notified at the end of each iteration
     */
    public void setObserver(final BatchLSObserver observer) {
        this.observer = observer;
    }

    /** Get the parameters supported by this estimator (including measurements and modifiers).
     * @return parameters supported by this estimator (including measurements and modifiers)
     */
    public List<ParameterDriver> getSupportedParameters() {
        return Collections.unmodifiableList(measurementsParameters);
    }

    /** Add a measurement.
     * @param measurement measurement to add
     * @exception OrekitException if the measurement has a parameter
     * that is already used
     */
    public void addMeasurement(final Measurement<?> measurement)
      throws OrekitException {

        // add the measurement
        measurements.add(measurement);

        // add measurement parameters (including modifiers parameters)
        for (final ParameterDriver parameter : measurement.getParametersDrivers()) {
            addMeasurementParameter(parameter);
        }

    }

    /** Add a measurement parameter.
     * @param parameter measurement parameter
     * @exception OrekitException if a parameter with the same name already exists
     */
    private void addMeasurementParameter(final ParameterDriver parameter)
        throws OrekitException {

        // compare against existing parameters
        for (final ParameterDriver existing : measurementsParameters) {
            if (existing.getName().equals(parameter.getName())) {
                if (existing == parameter) {
                    // the parameter was already known
                    return;
                } else {
                    // we have two different parameters sharing the same name
                    throw new OrekitException(OrekitMessages.DUPLICATED_PARAMETER_NAME,
                                              parameter.getName());
                }
            }
        }

        // it is a new parameter
        measurementsParameters.add(parameter);

    }

    /** Set the maximum number of iterations.
     * @param maxIterations maxIterations maximum number of iterations
     */
    public void setMaxIterations(final int maxIterations) {
        lsBuilder.maxIterations(maxIterations);
    }

    /** Set the maximum number of model evaluations.
     * @param maxEvaluations maximum number of model evaluations
     */
    public void setMaxEvaluations(final int maxEvaluations) {
        lsBuilder.maxEvaluations(maxEvaluations);
    }

    /**
     * Set convergence thresholds on RMS.
     * @param relTol the relative tolerance.
     * @param absTol the absolute tolerance.
     * @see EvaluationRmsChecker
     */
    public void setConvergenceThreshold(final double relTol, final double absTol) {
        lsBuilder.checker(new EvaluationRmsChecker(relTol, absTol) {
            /** {@inheritDoc} */
            @Override
            public boolean converged(final int iteration,
                                     final LeastSquaresProblem.Evaluation previous,
                                     final LeastSquaresProblem.Evaluation current) {

                // save the last evaluations
                lspEvaluation = current;

                // notify the observer
                if (observer != null) {
                    observer.iterationPerformed(iterationsCounter.getCount(),
                                                evaluationsCounter.getCount(),
                                                orbit,
                                                Collections.unmodifiableMap(evaluations),
                                                lspEvaluation);
                }

                return super.converged(iteration, previous, current);
            }
        });
    }

    /** Estimate the orbit and the parameters.
     * <p>
     * The estimated parameters are available using {@link #getParameters()}
     * </p>
     * @param initialGuess initial guess for the orbit
     * @return estimated orbit
     * @exception OrekitException if orbit cannot be determined
     */
    public Orbit estimate(final Orbit initialGuess) throws OrekitException {

        // compute problem dimension:
        // orbital parameters + propagator parameters + measurements parameters
        final int                   nbOrbitalParameters      = 6;
        final List<ParameterDriver> propagatorParameters     = propagatorBuilder.getParametersDrivers();
        int nbPropagatorParameters   = 0;
        for (final ParameterDriver parameter : propagatorParameters) {
            if (parameter.isEstimated()) {
                nbPropagatorParameters += parameter.getDimension();
            }
        }
        int nbMeasurementsParameters = 0;
        for (final ParameterDriver parameter : measurementsParameters) {
            if (parameter.isEstimated()) {
                nbMeasurementsParameters += parameter.getDimension();
            }
        }
        final int dimension = nbOrbitalParameters + nbPropagatorParameters + nbMeasurementsParameters;

        // create start point
        final double[] start = new double[dimension];
        propagatorBuilder.getOrbitType().mapOrbitToArray(initialGuess,
                                                         propagatorBuilder.getPositionAngle(),
                                                         start);
        int index = nbOrbitalParameters;
        for (final ParameterDriver propagatorParameter : propagatorParameters) {
            if (propagatorParameter.isEstimated()) {
                System.arraycopy(propagatorParameter.getValue(), 0, start, index, propagatorParameter.getDimension());
                index += propagatorParameter.getDimension();
            }
        }
        for (final ParameterDriver parameter : measurementsParameters) {
            if (parameter.isEstimated()) {
                System.arraycopy(parameter.getValue(), 0, start, index, parameter.getDimension());
                index += parameter.getDimension();
            }
        }
        lsBuilder.start(start);

        // create target (which is an array set to 0, as we compute weighted residuals ourselves)
        int p = 0;
        for (final Measurement<?> measurement : measurements) {
            if (measurement.isEnabled()) {
                p += measurement.getDimension();
            }
        }
        final double[] target = new double[p];
        lsBuilder.target(target);

        // set up the model
        final ModelObserver modelObserver = new ModelObserver() {
            /** {@inheritDoc} */
            @Override
            public void modelCalled(final Orbit newOrbit,
                                    final Map<Measurement<?>, Evaluation<?>> newEvaluations) {
                BatchLSEstimator.this.orbit       = newOrbit;
                BatchLSEstimator.this.evaluations = newEvaluations;
            }
        };
        final Model model = new Model(propagatorBuilder, propagatorParameters,
                                      measurements, measurementsParameters,
                                      initialGuess.getDate(), modelObserver);
        lsBuilder.model(model);

        // add a validator for orbital parameters
        lsBuilder.parameterValidator(OrbitValidator.getValidator(propagatorBuilder.getOrbitType()));

        try {

            // solve the problem
            optimizer.optimize(new TappedLSProblem(lsBuilder.build(), model));

            // extract the orbit
            return orbit;

        } catch (OrekitExceptionWrapper oew) {
            throw oew.getException();
        }

    }

    /** Get the last evaluations performed.
     * @return last evaluations performed
     */
    public Map<Measurement<?>, Evaluation<?>> getLastEvaluations() {
        return Collections.unmodifiableMap(evaluations);
    }

    /** Get the last {@link LeastSquaresProblem.Evaluation least squares problem evaluation}.
     * @return last least squares problem evaluation
     */
    public LeastSquaresProblem.Evaluation getLastLSPEvaluation() {
        return lspEvaluation;
    }

    /** Get the number of iterations used for last estimation.
     * @return number of iterations used for last estimation
     */
    public int getIterationsCount() {
        return iterationsCounter.getCount();
    }

    /** Get the number of evaluations used for last estimation.
     * @return number of evaluations used for last estimation
     */
    public int getEvaluationsCount() {
        return evaluationsCounter.getCount();
    }

    /** Wrapper used to tap the various counters. */
    private class TappedLSProblem implements LeastSquaresProblem {

        /** Underlying problem. */
        private final LeastSquaresProblem problem;

        /** Multivariate function model. */
        private final Model model;

        /** Simple constructor.
         * @param problem underlying problem
         * @param model multivariate function model
         */
        TappedLSProblem(final LeastSquaresProblem problem,
                        final Model model) {
            this.problem = problem;
            this.model   = model;
        }

        /** {@inheritDoc} */
        @Override
        public Incrementor getEvaluationCounter() {
            // tap the evaluations counter
            BatchLSEstimator.this.evaluationsCounter = problem.getEvaluationCounter();
            model.setEvaluationsCounter(BatchLSEstimator.this.evaluationsCounter);
            return BatchLSEstimator.this.evaluationsCounter;
        }

        /** {@inheritDoc} */
        @Override
        public Incrementor getIterationCounter() {
            // tap the iterations counter
            BatchLSEstimator.this.iterationsCounter = problem.getIterationCounter();
            model.setIterationsCounter(BatchLSEstimator.this.iterationsCounter);
            return BatchLSEstimator.this.iterationsCounter;
        }

        /** {@inheritDoc} */
        @Override
        public ConvergenceChecker<Evaluation> getConvergenceChecker() {
            return problem.getConvergenceChecker();
        }

        /** {@inheritDoc} */
        @Override
        public RealVector getStart() {
            return problem.getStart();
        }

        /** {@inheritDoc} */
        @Override
        public int getObservationSize() {
            return problem.getObservationSize();
        }

        /** {@inheritDoc} */
        @Override
        public int getParameterSize() {
            return problem.getParameterSize();
        }

        /** {@inheritDoc} */
        @Override
        public Evaluation evaluate(final RealVector point) {
            return problem.evaluate(point);
        }

    }

}