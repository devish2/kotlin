/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.tasks

import org.gradle.api.Task
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.work.Incremental
import org.jetbrains.kotlin.gradle.dsl.KaptExtensionApi
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinTopLevelExtensionConfig
import java.io.File

/** Common for all Kotlin compile tasks. */
interface KotlinCompileApi : CompileUsingKotlinDaemon {
    @get:Input
    val moduleName: Property<String>

    @get:Input
    val sourceSetName: Property<String>

    @get:Input
    val useModuleDetection: Property<Boolean>

    @get:Input
    val multiPlatformEnabled: Property<Boolean>

    @get:Classpath
    val pluginClasspath: ConfigurableFileCollection

    @get:Internal("Takes part in compiler args.")
    val friendPaths: ConfigurableFileCollection

    @get:LocalState
    val taskBuildDirectory: DirectoryProperty

    @get:Internal("Already marked as output, this is just a helper property.")
    val outputDirectory: DirectoryProperty
}

/** Specific for JVM Kotlin compile tasks. */
interface KotlinJvmCompileApi : KotlinCompileApi {

    fun setSource(sources: Any)

    fun source(vararg sources: Any): SourceTask

    fun setClasspath(classpath: FileCollection)

    @get:Internal("Takes part in compiler args.")
    val parentKotlinOptions: Property<KotlinJvmOptions>

    @get:Internal("Takes part in compiler args.")
    val customPluginOptions: Property<CompilerPluginOptions>

    fun applyFrom(ext: KotlinTopLevelExtensionConfig)
}

interface KaptGenerateStubsTaskApi : KotlinJvmCompileApi {
    @get:OutputDirectory
    val stubsDir: DirectoryProperty

    @get:Internal
    val javaSourceRoots: ConfigurableFileCollection

    @get:Input
    val verbose: Property<Boolean>

    @get:Internal
    val excludedSourceDirs: ListProperty<File>

    fun applyFrom(ext: KaptExtensionApi)
}

interface KaptKotlinTaskApi : Task {

    @get:Input
    val verbose: Property<Boolean>

    @get:Classpath
    @get:InputFiles
    val kaptClasspath: ConfigurableFileCollection

    /**
     * Output directory that contains caches necessary to support incremental annotation processing.
     */
    @get:LocalState
    val incAptCache: DirectoryProperty

    @get:OutputDirectory
    val classesDir: DirectoryProperty

    @get:OutputDirectory
    val destinationDir: DirectoryProperty

    /** Used in the model builder only. */
    @get:OutputDirectory
    val kotlinSourcesDestinationDir: DirectoryProperty

    @get:Nested
    val annotationProcessorOptionProviders: MutableList<Any>

    @get:Internal
    val classpath: ConfigurableFileCollection

    @get:InputFiles
    @get:IgnoreEmptyDirectories
    @get:Incremental
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val source: ConfigurableFileCollection

    @get:Internal
    val stubsDir: DirectoryProperty

    // TODO(gavra): Here we assume that kotlinc and javac output is available for incremental runs. We should insert some checks.
    @get:Internal
    val compiledSources: ConfigurableFileCollection
}

interface KaptKotlinWithoutKotlincTaskApi : KaptKotlinTaskApi {
    @get:Classpath
    @get:InputFiles
    val kaptJars: ConfigurableFileCollection

    @get:Input
    val addJdkClassesToClasspath: Property<Boolean>

    fun applyFrom(kaptExtension: KaptExtensionApi)
    fun applyFrom(ext: KotlinTopLevelExtensionConfig)
}