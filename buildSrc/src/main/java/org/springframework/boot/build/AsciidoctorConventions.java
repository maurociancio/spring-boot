/*
 * Copyright 2012-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.build;

import java.io.File;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.asciidoctor.gradle.jvm.AbstractAsciidoctorTask;
import org.asciidoctor.gradle.jvm.AsciidoctorJExtension;
import org.asciidoctor.gradle.jvm.AsciidoctorJPlugin;
import org.asciidoctor.gradle.jvm.AsciidoctorTask;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.tasks.Sync;

import org.springframework.boot.build.artifactory.ArtifactoryRepository;
import org.springframework.util.StringUtils;

/**
 * Conventions that are applied in the presence of the {@link AsciidoctorJPlugin}. When
 * the plugin is applied:
 *
 * <ul>
 * <li>All warnings are made fatal.
 * <li>The version of AsciidoctorJ is upgraded to 2.4.3.
 * <li>A {@code asciidoctorExtensions} configuration is created.
 * <li>For each {@link AsciidoctorTask} (HTML only):
 * <ul>
 * <li>A task is created to sync the documentation resources to its output directory.
 * <li>{@code doctype} {@link AsciidoctorTask#options(Map) option} is configured.
 * <li>The {@code backend} is configured.
 * </ul>
 * <li>For each {@link AbstractAsciidoctorTask} (HTML and PDF):
 * <ul>
 * <li>{@link AsciidoctorTask#attributes(Map) Attributes} are configured to enable
 * warnings for references to missing attributes, the GitHub tag, the Artifactory repo for
 * the current version, etc.
 * <li>{@link AbstractAsciidoctorTask#baseDirFollowsSourceDir() baseDirFollowsSourceDir()}
 * is enabled.
 * </ul>
 * </ul>
 *
 * @author Andy Wilkinson
 */
class AsciidoctorConventions {

	private static final String EXTENSIONS_CONFIGURATION = "asciidoctorExtensions";

	private static final String ASCIIDOCTORJ_VERSION = "2.4.3";

	void apply(Project project) {
		project.getPlugins().withType(AsciidoctorJPlugin.class, (asciidoctorPlugin) -> {
			configureDocResourcesRepository(project);
			makeAllWarningsFatal(project);
			upgradeAsciidoctorJVersion(project);
			createAsciidoctorExtensionsConfiguration(project);
			project.getTasks().withType(AbstractAsciidoctorTask.class,
					(asciidoctorTask) -> configureAsciidoctorTask(project, asciidoctorTask));
		});
	}

	private void configureDocResourcesRepository(Project project) {
		project.getRepositories().maven((mavenRepo) -> {
			mavenRepo.setUrl(URI.create("https://repo.spring.io/snapshot"));
			mavenRepo.mavenContent((mavenContent) -> mavenContent.includeGroup("io.spring.asciidoctor.backends"));
		});
	}

	private void makeAllWarningsFatal(Project project) {
		project.getExtensions().getByType(AsciidoctorJExtension.class).fatalWarnings(".*");
	}

	private void upgradeAsciidoctorJVersion(Project project) {
		project.getExtensions().getByType(AsciidoctorJExtension.class).setVersion(ASCIIDOCTORJ_VERSION);
	}

	private void createAsciidoctorExtensionsConfiguration(Project project) {
		ConfigurationContainer configurations = project.getConfigurations();
		Configuration asciidoctorExtensions = configurations.maybeCreate(EXTENSIONS_CONFIGURATION);
		asciidoctorExtensions.getDependencies().add(project.getDependencies()
				.create("io.spring.asciidoctor.backends:spring-asciidoctor-backends:0.0.1-M1"));
		Configuration dependencyManagement = configurations.findByName("dependencyManagement");
		if (dependencyManagement != null) {
			asciidoctorExtensions.extendsFrom(dependencyManagement);
		}
	}

	private void configureAsciidoctorTask(Project project, AbstractAsciidoctorTask asciidoctorTask) {
		asciidoctorTask.configurations(EXTENSIONS_CONFIGURATION);
		configureCommonAttributes(project, asciidoctorTask);
		configureOptions(asciidoctorTask);
		asciidoctorTask.baseDirFollowsSourceDir();
		createSyncDocumentationSourceTask(project, asciidoctorTask);
		if (asciidoctorTask instanceof AsciidoctorTask) {
			configureAsciidoctorHtmlTask((AsciidoctorTask) asciidoctorTask);
		}
	}

	private void configureCommonAttributes(Project project, AbstractAsciidoctorTask asciidoctorTask) {
		Map<String, Object> attributes = new HashMap<>();
		attributes.put("attribute-missing", "warn");
		attributes.put("github-tag", determineGitHubTag(project));
		attributes.put("spring-boot-artifactory-repo", ArtifactoryRepository.forProject(project));
		attributes.put("revnumber", null);
		asciidoctorTask.attributes(attributes);
	}

	private String determineGitHubTag(Project project) {
		String version = "v" + project.getVersion();
		return (version.endsWith("-SNAPSHOT")) ? "main" : version;
	}

	private void configureOptions(AbstractAsciidoctorTask asciidoctorTask) {
		asciidoctorTask.options(Collections.singletonMap("doctype", "book"));
	}

	private Sync createSyncDocumentationSourceTask(Project project, AbstractAsciidoctorTask asciidoctorTask) {
		Sync syncDocumentationSource = project.getTasks()
				.create("syncDocumentationSourceFor" + StringUtils.capitalize(asciidoctorTask.getName()), Sync.class);
		File syncedSource = new File(project.getBuildDir(), "docs/src/" + asciidoctorTask.getName());
		syncDocumentationSource.setDestinationDir(syncedSource);
		syncDocumentationSource.from("src/docs/");
		asciidoctorTask.dependsOn(syncDocumentationSource);
		asciidoctorTask.getInputs().dir(syncedSource);
		asciidoctorTask.setSourceDir(project.relativePath(new File(syncedSource, "asciidoc/")));
		return syncDocumentationSource;
	}

	private void configureAsciidoctorHtmlTask(AsciidoctorTask asciidoctorTask) {
		asciidoctorTask.outputOptions((outputOptions) -> outputOptions.backends("spring-html"));
	}

}
