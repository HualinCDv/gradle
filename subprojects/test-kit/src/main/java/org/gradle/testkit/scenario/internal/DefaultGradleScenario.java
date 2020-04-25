/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.testkit.scenario.internal;

import org.gradle.api.Action;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.scenario.GradleScenario;
import org.gradle.testkit.scenario.GradleScenarioSteps;
import org.gradle.testkit.scenario.ScenarioResult;
import org.gradle.util.GFileUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Supplier;


public class DefaultGradleScenario implements GradleScenario {

    protected File baseDirectory;
    private Action<File> workspaceBuilder;
    private Supplier<GradleRunner> runnerFactory;
    private final List<Action<GradleRunner>> runnerActions = new ArrayList<>();
    private final List<Action<GradleScenarioSteps>> stepsBuilders = new ArrayList<>();

    @Override
    public GradleScenario withBaseDirectory(File baseDirectory) {
        this.baseDirectory = baseDirectory;
        return this;
    }

    @Override
    public GradleScenario withWorkspace(Action<File> workspaceBuilder) {
        this.workspaceBuilder = workspaceBuilder;
        return this;
    }

    @Override
    public GradleScenario withRunnerFactory(Supplier<GradleRunner> runnerFactory) {
        this.runnerFactory = runnerFactory;
        return this;
    }

    @Override
    public GradleScenario withRunnerAction(Action<GradleRunner> runnerAction) {
        this.runnerActions.add(runnerAction);
        return this;
    }

    @Override
    public GradleScenario withSteps(Action<GradleScenarioSteps> steps) {
        this.stepsBuilders.add(steps);
        return this;
    }

    @Override
    public ScenarioResult run() {

        validateScenario();

        File workspaceDirectory = createInitialWorkspace(baseDirectory);

        LinkedHashMap<String, BuildResult> results = new LinkedHashMap<>();
        for (DefaultGradleScenarioStep step : buildSteps()) {

            workspaceDirectory = step.prepareWorkspace(
                workspaceDirectory,
                () -> nextWorkspaceDirectory(baseDirectory),
                workspaceBuilder
            );

            results.put(step.getName(), runStep(step, workspaceDirectory));
        }

        return new DefaultScenarioResult(results);
    }

    private void validateScenario() {
        if (runnerFactory == null) {
            throw new IllegalStateException("No Gradle runner factory provided. Use withRunnerFactory(Supplier<GradleRunner>)");
        }
        if (baseDirectory == null) {
            throw new IllegalStateException("No base directory provided. Use withBaseDirectory(File)");
        }
        if (baseDirectory.isFile()) {
            throw new IllegalArgumentException("Provided base directory '" + baseDirectory + "' exists and is a file");
        }
        if (isNonEmptyDirectory(baseDirectory)) {
            throw new IllegalArgumentException("Provided base directory '" + baseDirectory + "' is a non-empty directory");
        }
        if (stepsBuilders.isEmpty()) {
            throw new IllegalStateException("No scenario steps provided. Use withSteps {}");
        }
    }

    private boolean isNonEmptyDirectory(File file) {
        //noinspection ConstantConditions
        return file.isDirectory() && file.list().length > 0;
    }

    private File createInitialWorkspace(File baseDirectory) {
        File workspaceDirectory = nextWorkspaceDirectory(baseDirectory);
        GFileUtils.mkdirs(workspaceDirectory);
        if (workspaceBuilder != null) {
            workspaceBuilder.execute(workspaceDirectory);
        }
        return workspaceDirectory;
    }

    private File nextWorkspaceDirectory(File baseDirectory) {
        int count = 1;
        while (true) {
            File workspaceDirectory = new File(baseDirectory, "workspace-" + count);
            if (!workspaceDirectory.exists()) {
                return workspaceDirectory;
            }
            count++;
        }
    }

    private Collection<DefaultGradleScenarioStep> buildSteps() {
        DefaultGradleScenarioSteps steps = new DefaultGradleScenarioSteps();
        for (Action<GradleScenarioSteps> stepsBuilder : stepsBuilders) {
            stepsBuilder.execute(steps);
        }
        return steps.getSteps();
    }

    private BuildResult runStep(DefaultGradleScenarioStep step, File workspaceDirectory) {

        GradleRunner runner = runnerFactory.get().withProjectDir(workspaceDirectory);
        for (Action<GradleRunner> runnerAction : runnerActions) {
            runnerAction.execute(runner);
        }
        step.executeRunnerActions(runner);

        BuildResult result;
        if (step.expectsFailure()) {
            result = runner.buildAndFail();
            step.consumeFailure(result);
        } else {
            result = runner.build();
            step.consumeResult(result);
        }
        return result;
    }
}
