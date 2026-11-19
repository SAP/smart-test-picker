// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.apache.maven.execution.AbstractExecutionListener;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.ExecutionListener;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import com.sap.oss.smarttestpicker.StpCollectorSelection;

/** Invocation-local isolation, applied to resolved goals immediately before mojo configuration. */
final class MavenJacocoMappingIsolation extends AbstractExecutionListener {
    private static final Set<String> CONSUMERS = Set.of("report", "report-integration", "report-aggregate", "check", "merge", "dump");
    private final ExecutionListener delegate;
    private final Map<String, File> destinations;
    private final Log log;

    private MavenJacocoMappingIsolation(ExecutionListener delegate, Map<String, File> destinations, Log log) {
        this.delegate = delegate == null ? new AbstractExecutionListener() { } : delegate;
        this.destinations = Map.copyOf(destinations);
        this.log = log;
    }

    static void install(MavenSession session, List<MavenProject> projects, Log log) throws Exception {
        Map<String, File> destinations = new TreeMap<>();
        for (MavenProject project : projects) {
            if (StpMavenLifecycleParticipant.selectedCollector(project, session.getUserProperties()) != StpCollectorSelection.JACOCO) continue;
            var plugin = project.getPlugin("org.jacoco:jacoco-maven-plugin");
            if (plugin != null) for (var execution : plugin.getExecutions()) {
                if (execution.getGoals().contains("instrument")) throw new IllegalArgumentException(
                        "Unsupported JaCoCo offline instrument execution " + execution.getId() + " in " + project.getId()
                                + ": STP JaCoCo mapping requires an active online agent");
            }
            Path parent = Path.of(project.getBuild().getDirectory(), "stp");
            Files.createDirectories(parent);
            // A fresh private directory cannot collide with a project's configured destination.
            destinations.put(project.getId(), Files.createTempDirectory(parent, "jacoco-agent-").resolve("agent.exec").toFile());
        }
        if (!destinations.isEmpty()) session.getRequest().setExecutionListener(
                new MavenJacocoMappingIsolation(session.getRequest().getExecutionListener(), destinations, log));
    }

    @Override public void mojoStarted(ExecutionEvent event) {
        var execution = event.getMojoExecution();
        File destination = destinations.get(event.getProject().getId());
        if (destination != null && "org.jacoco".equals(execution.getGroupId())
                && "jacoco-maven-plugin".equals(execution.getArtifactId())) {
            Xpp3Dom configuration = execution.getConfiguration() == null ? new Xpp3Dom("configuration")
                    : new Xpp3Dom(execution.getConfiguration());
            if (CONSUMERS.contains(execution.getGoal())) {
                set(configuration, "skip", "true");
                log.info("[STP] Skipping " + event.getProject().getId() + " jacoco:" + execution.getGoal()
                        + " (" + execution.getExecutionId() + "): per-test mapping resets the shared agent; run project consumers separately");
            } else if (Set.of("prepare-agent", "prepare-agent-integration").contains(execution.getGoal())) {
                set(configuration, "destFile", destination.getAbsolutePath());
                set(configuration, "output", "file");
            } else if ("instrument".equals(execution.getGoal())) {
                throw new IllegalArgumentException("Unsupported JaCoCo offline instrument goal during STP mapping in " + event.getProject().getId());
            }
            execution.setConfiguration(configuration);
        }
        delegate.mojoStarted(event);
    }

    private static void set(Xpp3Dom configuration, String name, String value) {
        Xpp3Dom child = configuration.getChild(name);
        if (child == null) { child = new Xpp3Dom(name); configuration.addChild(child); }
        child.setValue(value);
    }

    // Preserve all existing build listeners, including console and event-spy notifications.
    @Override public void projectDiscoveryStarted(ExecutionEvent e) { delegate.projectDiscoveryStarted(e); }
    @Override public void sessionStarted(ExecutionEvent e) { delegate.sessionStarted(e); }
    @Override public void sessionEnded(ExecutionEvent e) { delegate.sessionEnded(e); }
    @Override public void projectSkipped(ExecutionEvent e) { delegate.projectSkipped(e); }
    @Override public void projectStarted(ExecutionEvent e) { delegate.projectStarted(e); }
    @Override public void projectSucceeded(ExecutionEvent e) { delegate.projectSucceeded(e); }
    @Override public void projectFailed(ExecutionEvent e) { delegate.projectFailed(e); }
    @Override public void mojoSkipped(ExecutionEvent e) { delegate.mojoSkipped(e); }
    @Override public void mojoSucceeded(ExecutionEvent e) { delegate.mojoSucceeded(e); }
    @Override public void mojoFailed(ExecutionEvent e) { delegate.mojoFailed(e); }
    @Override public void forkStarted(ExecutionEvent e) { delegate.forkStarted(e); }
    @Override public void forkSucceeded(ExecutionEvent e) { delegate.forkSucceeded(e); }
    @Override public void forkFailed(ExecutionEvent e) { delegate.forkFailed(e); }
    @Override public void forkedProjectStarted(ExecutionEvent e) { delegate.forkedProjectStarted(e); }
    @Override public void forkedProjectSucceeded(ExecutionEvent e) { delegate.forkedProjectSucceeded(e); }
    @Override public void forkedProjectFailed(ExecutionEvent e) { delegate.forkedProjectFailed(e); }
}
