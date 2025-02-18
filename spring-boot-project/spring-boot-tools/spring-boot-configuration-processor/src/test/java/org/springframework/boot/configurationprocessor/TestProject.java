/*
 * Copyright 2012-2023 the original author or authors.
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

package org.springframework.boot.configurationprocessor;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.boot.configurationprocessor.metadata.ConfigurationMetadata;
import org.springframework.boot.configurationprocessor.test.TestConfigurationMetadataAnnotationProcessor;
import org.springframework.boot.configurationsample.ConfigurationProperties;
import org.springframework.boot.configurationsample.NestedConfigurationProperty;
import org.springframework.boot.testsupport.BuildOutput;
import org.springframework.boot.testsupport.compiler.TestCompiler;
import org.springframework.boot.testsupport.compiler.TestCompiler.TestCompilationTask;
import org.springframework.util.Assert;
import org.springframework.util.FileCopyUtils;
import org.springframework.util.FileSystemUtils;

/**
 * A TestProject contains a copy of a subset of test sample code.
 * <p>
 * Why a copy? Because when doing incremental build testing, we need to make modifications
 * to the contents of the 'test project'. But we don't want to actually modify the
 * original content itself.
 *
 * @author Kris De Volder
 */
public class TestProject {

	private static final Class<?>[] ALWAYS_INCLUDE = { ConfigurationProperties.class,
			NestedConfigurationProperty.class };

	/**
	 * Contains copies of the original source so we can modify it safely to test
	 * incremental builds.
	 */
	private final File sourceDirectory;

	private final TestCompiler compiler;

	private final Set<File> sourceFiles = new LinkedHashSet<>();

	private final File classPathDirectory;

	public TestProject(File tempDirectory, Class<?>... classes) throws IOException {
		this.sourceDirectory = new File(tempDirectory, "src");
		File outputDirectory = new File(tempDirectory, "build");
		File testClasses = new BuildOutput(TestProject.class).getTestClassesLocation();
		this.classPathDirectory = new File(tempDirectory, "classPath");
		FileSystemUtils.copyRecursively(testClasses, this.classPathDirectory);
		this.compiler = new TestCompiler(outputDirectory) {

			@Override
			protected File getSourceDirectory() {
				return TestProject.this.sourceDirectory;
			}

			@Override
			protected Iterable<? extends File> prepareClassPath(Iterable<? extends File> classPath) {
				List<File> updatedClassPath = new ArrayList<>();
				for (File entry : classPath) {
					if (!entry.equals(testClasses)) {
						updatedClassPath.add(entry);
					}
					else {
						updatedClassPath.add(TestProject.this.classPathDirectory);
					}
				}
				return updatedClassPath;
			}

		};
		Set<Class<?>> contents = new HashSet<>(Arrays.asList(classes));
		contents.addAll(Arrays.asList(ALWAYS_INCLUDE));
		copySources(contents);
	}

	private void copySources(Set<Class<?>> contents) throws IOException {
		for (Class<?> type : contents) {
			copySources(type);
		}
	}

	private void copySources(Class<?> type) throws IOException {
		File original = getOriginalSourceFile(type);
		File target = getSourceFile(type);
		target.getParentFile().mkdirs();
		FileCopyUtils.copy(original, target);
		this.sourceFiles.add(target);
	}

	public File getSourceFile(Class<?> type) {
		return new File(this.sourceDirectory, TestCompiler.sourcePathFor(type));
	}

	public ConfigurationMetadata fullBuild() {
		TestConfigurationMetadataAnnotationProcessor processor = new TestConfigurationMetadataAnnotationProcessor(
				this.compiler.getOutputLocation());
		TestCompilationTask task = this.compiler.getTask(this.sourceFiles);
		deleteDirectoryContents(this.compiler.getOutputLocation());
		task.call(processor);
		return processor.getMetadata();
	}

	public ConfigurationMetadata incrementalBuild(Class<?>... toRecompile) {
		TestConfigurationMetadataAnnotationProcessor processor = new TestConfigurationMetadataAnnotationProcessor(
				this.compiler.getOutputLocation());
		TestCompilationTask task = this.compiler.getTask(toRecompile);
		task.call(processor);
		return processor.getMetadata();
	}

	private void deleteDirectoryContents(File outputDirectory) {
		FileSystemUtils.deleteRecursively(outputDirectory);
		outputDirectory.mkdirs();
	}

	/**
	 * Retrieve File relative to project's output directory.
	 * @param relativePath the relative path
	 * @return the output file
	 */
	public File getOutputFile(String relativePath) {
		Assert.isTrue(!new File(relativePath).isAbsolute(), "'" + relativePath + "' was absolute");
		return new File(this.compiler.getOutputLocation(), relativePath);
	}

	/**
	 * Add source code at the end of file, just before last '}'
	 * @param target the target
	 * @param snippetStream the snippet stream
	 * @throws Exception if the source cannot be added
	 */
	public void addSourceCode(Class<?> target, InputStream snippetStream) throws Exception {
		File targetFile = getSourceFile(target);
		String contents = getContents(targetFile);
		int insertAt = contents.lastIndexOf('}');
		String additionalSource = FileCopyUtils.copyToString(new InputStreamReader(snippetStream));
		contents = contents.substring(0, insertAt) + additionalSource + contents.substring(insertAt);
		putContents(targetFile, contents);
	}

	/**
	 * Delete from the project the source {@code .java} file and any compiled
	 * {@code .class} file for the given class.
	 * @param type the class to delete
	 */
	public void delete(Class<?> type) {
		File target = getSourceFile(type);
		target.delete();
		this.sourceFiles.remove(target);
		String fileName = type.getName().replace(".", "/") + ".class";
		new File(this.compiler.getOutputLocation(), fileName).delete();
		new File(this.classPathDirectory, fileName).delete();
	}

	/**
	 * Restore source code of given class to its original contents.
	 * @param type the class to revert
	 * @throws IOException on IO error
	 */
	public void revert(Class<?> type) throws IOException {
		Assert.isTrue(getSourceFile(type).exists(), "Source file for type '" + type + "' does not exist");
		copySources(type);
	}

	/**
	 * Add source code of given class to this project.
	 * @param type the class to add
	 * @throws IOException on IO error
	 */
	public void add(Class<?> type) throws IOException {
		Assert.isTrue(!getSourceFile(type).exists(), "Source file for type '" + type + "' already exists");
		copySources(type);
	}

	public void replaceText(Class<?> type, String find, String replace) throws Exception {
		File target = getSourceFile(type);
		String contents = getContents(target);
		contents = contents.replace(find, replace);
		putContents(target, contents);
	}

	/**
	 * Find the 'original' source code for given test class. Clients or subclasses should
	 * have no need to know about these. They should work only with the copied source
	 * code.
	 */
	private File getOriginalSourceFile(Class<?> type) {
		return new File(TestCompiler.SOURCE_DIRECTORY, TestCompiler.sourcePathFor(type));
	}

	private static void putContents(File targetFile, String contents) throws IOException {
		FileCopyUtils.copy(new StringReader(contents), new FileWriter(targetFile));
	}

	private static String getContents(File file) throws Exception {
		return FileCopyUtils.copyToString(new FileReader(file));
	}

}
