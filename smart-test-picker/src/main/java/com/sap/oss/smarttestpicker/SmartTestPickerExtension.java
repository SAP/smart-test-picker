// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import javax.inject.Inject;

import org.gradle.api.Action;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;


/**
 * Gradle DSL extension for configuring the Smart Test Picker plugin.
 *
 * <p>Usage in {@code build.gradle}:</p>
 * <pre>{@code
 * smartTestPicker {
 *     coverageCollector = 'ASM'    // ASM (default) or JACOCO
 *     revision = providers.environmentVariable('GIT_COMMIT')
 *     shardId = 'ci-shard-1'       // optional; defaults to the mapping task path
 *     baseBranch = 'develop'       // default: 'main'
 *     maxCommitDistance = 500       // default: 500
 *     fullSuiteTriggers = [        // default: empty (disabled)
 *         '**\/*-items.xml',
 *         '**\/*.impex',
 *         '**\/spring*.xml'
 *     ]
 *     remoteStore {                // default: not configured
 *         url = 'https://nexus.example.com/repository/stp-maps/'
 *         push = true
 *         credentials {
 *             username = providers.environmentVariable('STP_REMOTE_USER')
 *             password = providers.environmentVariable('STP_REMOTE_PASS')
 *         }
 *     }
 * }
 * }</pre>
 *
 * @see SmartTestPickerPlugin
 */
public abstract class SmartTestPickerExtension
{

	private final RemoteStoreExtension remoteStore;
	private final Property<String> coverageCollector;

	@Inject
	public SmartTestPickerExtension(ObjectFactory objects)
	{
		this.remoteStore = objects.newInstance(RemoteStoreExtension.class);
		this.coverageCollector = objects.property(String.class);
		this.coverageCollector.convention(CoverageCollectorType.ASM.name());
	}

	/** Accepts {@code ASM} or {@code JACOCO}, case-insensitively. */
	public Property<String> getCoverageCollector() { return coverageCollector; }

	/** Revision persisted in ASM fragments. No Git discovery is performed by the agent. */
	public abstract Property<String> getRevision();

	/** Optional externally orchestrated shard ID; defaults to the mapping task path. */
	public abstract Property<String> getShardId();

	public abstract ListProperty<String> getCoverageIncludes();

	public abstract ListProperty<String> getCoverageExcludes();

	/**
	 * The base branch used for change detection (e.g. "main", "develop").
	 * The coverage map is generated against this branch and used as the diff baseline.
	 *
	 * @return the base branch property (default: "main")
	 */
	public abstract Property<String> getBaseBranch();

	/**
	 * Maximum number of commits between the coverage map's commitId and HEAD.
	 * If the distance exceeds this threshold, the mapping is considered stale
	 * and the full test suite is run instead.
	 *
	 * @return the max commit distance property (default: 500)
	 */
	public abstract Property<Integer> getMaxCommitDistance();

	/**
	 * When enabled, the smartTest task includes entire test classes instead of
	 * individual test methods. Use this for projects where test methods within
	 * a class depend on shared state or execution order.
	 *
	 * @return the class-level selection property (default: false)
	 */
	public abstract Property<Boolean> getClassLevelSelection();

	/**
	 * Glob patterns for files that trigger a full test suite when changed.
	 * Use this for non-Java files that affect runtime behavior but are invisible
	 * to JaCoCo coverage (e.g. XML data models, Spring config, ImpEx files).
	 *
	 * <p>Patterns are matched against git diff file paths relative to the project root.
	 * Standard glob syntax is supported (e.g. {@code **\/*-items.xml}, {@code *\/spring*.xml}).</p>
	 *
	 * <p>When any changed file matches a trigger pattern, the plugin outputs FULL_SUITE
	 * and all tests are run. This is the safety-first approach: when configuration files
	 * change, run everything.</p>
	 *
	 * @return the list of glob trigger patterns (default: empty = disabled)
	 */
	public abstract ListProperty<String> getFullSuiteTriggers();

	public RemoteStoreExtension getRemoteStore()
	{
		return remoteStore;
	}

	public void remoteStore(Action<? super RemoteStoreExtension> action)
	{
		action.execute(remoteStore);
	}
}
