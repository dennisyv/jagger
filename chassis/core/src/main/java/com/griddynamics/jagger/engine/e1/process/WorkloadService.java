/*
 * Copyright (c) 2010-2012 Grid Dynamics Consulting Services, Inc, All Rights Reserved
 * http://www.griddynamics.com
 *
 * This library is free software; you can redistribute it and/or modify it under the terms of
 * the GNU Lesser General Public License as published by the Free Software Foundation; either
 * version 2.1 of the License, or any later version.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.griddynamics.jagger.engine.e1.process;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.AbstractExecutionThreadService;
import com.griddynamics.jagger.engine.e1.scenario.ScenarioCollector;
import com.griddynamics.jagger.invoker.Invokers;
import com.griddynamics.jagger.invoker.Scenario;
import com.griddynamics.jagger.util.NewThreadExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.griddynamics.jagger.util.TimeUtils.sleepMillis;

public abstract class WorkloadService extends AbstractExecutionThreadService {
    private static final Logger log = LoggerFactory.getLogger(WorkloadService.class);

    private final Executor executor;
    private final Scenario<Object, Object, Object> scenario;
    private final List<ScenarioCollector<Object, Object, Object>> collectors;

    private final AtomicInteger delay = new AtomicInteger(0);
    private final AtomicInteger samples = new AtomicInteger(0);

    public static WorkloadServiceBuilder builder(Scenario<Object, Object, Object> scenario) {
        return new WorkloadServiceBuilder(scenario);
    }

    private WorkloadService(Executor executor, Scenario<Object, Object, Object> scenario, List<ScenarioCollector<Object, Object, Object>> collectors) {
        this.executor = executor;
        this.scenario = scenario;
        this.collectors = collectors;
    }

    @Override
    protected void run() throws Exception {
        try {
            while (isRunning() && !terminationRequired()) {
                log.debug("Scenario {} doTransaction called", scenario);
                scenario.doTransaction();
                log.debug("Sleep between invocations for {}", delay);
                sleepMillis(delay.get());
                samples.incrementAndGet();
            }
        } catch (Throwable error) {
            log.error("Error during the invocation", error);
        }
    }

    @Override
    protected void shutDown() throws Exception {
        try {
            for (ScenarioCollector collector : collectors) {
                collector.flush();
            }
        } catch (Throwable error) {
            log.error("Error during flushing", error);
        }
    }

    @Override
    protected Executor executor() {
        return executor;
    }

    protected abstract boolean terminationRequired();

    public int getSamples() {
        return samples.get();
    }

    public void changeDelay(int delay) {
        this.delay.set(delay);
    }

    public static class WorkloadServiceBuilder {
        private final Scenario<Object, Object, Object> scenario;
        private Executor executor = NewThreadExecutor.INSTANCE;
        private ImmutableList.Builder<ScenarioCollector<Object, Object, Object>> collectors = ImmutableList.builder();

        private WorkloadServiceBuilder(Scenario<Object, Object, Object> scenario) {
            this.scenario = scenario;
        }

        public WorkloadServiceBuilder addCollectors(List<ScenarioCollector<Object, Object, Object>> collectors) {
            this.collectors.addAll(collectors);
            return this;
        }

        public WorkloadServiceBuilder addCollector(ScenarioCollector<Object, Object, Object> collector) {
            collectors.add(collector);
            return this;
        }

        public WorkloadServiceBuilder useExecutor(Executor executor) {
            this.executor = executor;
            return this;
        }

        public WorkloadService buildInfiniteService() {
            ImmutableList<ScenarioCollector<Object, Object, Object>> list = collectors.build();

            scenario.setListener(Invokers.composeListeners(list));
            return new WorkloadService(executor, scenario, list) {
                @Override
                protected boolean terminationRequired() {
                    return false;
                }
            };
        }

        public WorkloadService buildServiceWithPredefinedSamples(final int samples) {
            ImmutableList<ScenarioCollector<Object, Object, Object>> list = collectors.build();

            scenario.setListener(Invokers.composeListeners(list));
            return new WorkloadService(executor, scenario, list) {
                @Override
                protected boolean terminationRequired() {
                    return getSamples() >= samples;
                }
            };
        }
    }

}