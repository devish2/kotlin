/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.gradle.internal

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.gradle.dsl.KaptExtensionApi
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmOptionsImpl
import org.jetbrains.kotlin.gradle.internal.Kapt3GradleSubplugin.Companion.isInfoAsWarnings
import org.jetbrains.kotlin.gradle.internal.Kapt3GradleSubplugin.Companion.isKaptKeepKdocCommentsInStubs
import org.jetbrains.kotlin.gradle.internal.Kapt3GradleSubplugin.Companion.isKaptVerbose
import org.jetbrains.kotlin.gradle.plugin.*
import org.jetbrains.kotlin.gradle.plugin.PropertiesProvider
import org.jetbrains.kotlin.gradle.plugin.mpp.pm20.KotlinCompilationData
import org.jetbrains.kotlin.gradle.plugin.registerSubpluginOptionsAsInputs
import org.jetbrains.kotlin.gradle.tasks.*
import org.jetbrains.kotlin.gradle.tasks.FilteringSourceRootsContainer
import org.jetbrains.kotlin.gradle.tasks.SourceRoots
import org.jetbrains.kotlin.gradle.utils.isParentOf
import org.jetbrains.kotlin.incremental.classpathAsList
import org.jetbrains.kotlin.incremental.destinationAsFile
import java.io.File
import java.util.concurrent.Callable

@CacheableTask
abstract class KaptGenerateStubsTask : KotlinCompile(KotlinJvmOptionsImpl()), KaptGenerateStubsTaskApi {

    internal class Configurator(
        private val kotlinCompileTaskProvider: TaskProvider<KotlinCompile>,
        kotlinCompilation: KotlinCompilationData<*>,
        properties: PropertiesProvider,
        private val classpathSnapshotDir: File
    ) : KotlinCompile.Configurator<KaptGenerateStubsTask>(kotlinCompilation, properties) {

        override fun getClasspathSnapshotDir(task: KaptGenerateStubsTask): Provider<Directory> =
            task.project.objects.directoryProperty().fileValue(classpathSnapshotDir)

        override fun configure(task: KaptGenerateStubsTask) {
            super.configure(task)

            val kotlinCompileTask = kotlinCompileTaskProvider.get()
            val providerFactory = kotlinCompileTask.project.providers
            task.useModuleDetection.value(kotlinCompileTask.useModuleDetection).disallowChanges()
            task.moduleName.value(kotlinCompileTask.moduleName).disallowChanges()
            task.classpath = task.project.files(Callable { kotlinCompileTask.classpath })
            task.pluginClasspath.from(providerFactory.provider { kotlinCompileTask.pluginClasspath })
//            task.compileKotlinArgumentsContributor.set(
//                providerFactory.provider {
//                    kotlinCompileTask.compilerArgumentsContributor
//                }
//            )
            task.source(providerFactory.provider {
                kotlinCompileTask.getSourceRoots().kotlinSourceFiles.filter { task.isSourceRootAllowed(it) }
            })
            task.javaSourceRoots.from(
                providerFactory.provider {
                    kotlinCompileTask.getSourceRoots().let { compileTaskSourceRoots ->
                        compileTaskSourceRoots.javaSourceRoots.filter { task.isSourceRootAllowed(it) }
                    }
                }
            )
            task.verbose.set(KaptTask.queryKaptVerboseProperty(task.project))
        }
    }

    override fun applyFrom(ext: KaptExtensionApi) {
        val dslJavacOptions: Provider<Map<String, String>> = project.provider { ext.getJavacOptions() }

        val subpluginOptions = buildOptions("apt", dslJavacOptions, ext)
        registerSubpluginOptions(subpluginOptions)
    }

    private fun buildOptions(
        aptMode: String,
        javacOptions: Provider<Map<String, String>>,
        kaptExtension: KaptExtensionApi
    ): Provider<List<SubpluginOption>> {
        return project.provider {
            val pluginOptions = mutableListOf<SubpluginOption>()

            val generatedFilesDir = Kapt3GradleSubplugin.getKaptGeneratedSourcesDir(project, sourceSetName.get())

            pluginOptions += SubpluginOption("aptMode", aptMode)

            pluginOptions += FilesSubpluginOption("sources", listOf(generatedFilesDir))
            pluginOptions += FilesSubpluginOption("classes", listOf(Kapt3GradleSubplugin.getKaptGeneratedClassesDir(project, sourceSetName.get())))

            pluginOptions += FilesSubpluginOption("incrementalData", listOf(destinationDirectory.locationOnly.get().asFile))

            val annotationProcessors = kaptExtension.getExplicitAnnotationProcessors()
            if (annotationProcessors.isNotEmpty()) {
                pluginOptions += SubpluginOption("processors", annotationProcessors)
            }

            pluginOptions += SubpluginOption("javacArguments", encodeList(javacOptions.get()))

            addMiscOptions(pluginOptions, kaptExtension)

            pluginOptions
        }
    }

    private fun addMiscOptions(pluginOptions: MutableList<SubpluginOption>, kaptExtension: KaptExtensionApi) {
        // These option names must match those defined in org.jetbrains.kotlin.kapt.cli.KaptCliOption.
        pluginOptions += SubpluginOption("useLightAnalysis", "${kaptExtension.useLightAnalysis}")
        pluginOptions += SubpluginOption("correctErrorTypes", "${kaptExtension.correctErrorTypes}")
        pluginOptions += SubpluginOption("dumpDefaultParameterValues", "${kaptExtension.dumpDefaultParameterValues}")
        pluginOptions += SubpluginOption("mapDiagnosticLocations", "${kaptExtension.mapDiagnosticLocations}")
        pluginOptions += SubpluginOption(
            "strictMode", // Currently doesn't match KaptCliOption.STRICT_MODE_OPTION, is it a typo introduced in https://github.com/JetBrains/kotlin/commit/c83581e6b8155c6d89da977be6e3cd4af30562e5?
            "${kaptExtension.strictMode}"
        )
        pluginOptions += SubpluginOption("stripMetadata", "${kaptExtension.stripMetadata}")
        pluginOptions += SubpluginOption("keepKdocCommentsInStubs", "${project.isKaptKeepKdocCommentsInStubs()}")
        pluginOptions += SubpluginOption("showProcessorTimings", "${kaptExtension.showProcessorTimings}")
        pluginOptions += SubpluginOption("detectMemoryLeaks", kaptExtension.detectMemoryLeaks)
        pluginOptions += SubpluginOption("infoAsWarnings", "${project.isInfoAsWarnings()}")
        pluginOptions += FilesSubpluginOption("stubs", listOf(stubsDir.locationOnly.get().asFile))

        if (project.isKaptVerbose()) {
            pluginOptions += SubpluginOption("verbose", "true")
        }
    }

    private fun registerSubpluginOptions(optionsProvider: Provider<List<SubpluginOption>>) {
        val compilerPluginId = Kapt3GradleSubplugin.KAPT_SUBPLUGIN_ID

        val options = optionsProvider.get()

        registerSubpluginOptionsAsInputs(compilerPluginId, options)

        for (option in options) {
            pluginOptions.addPluginArgument(compilerPluginId, option)
        }
    }

    @field:Transient
    override val sourceRootsContainer = FilteringSourceRootsContainer(objects, { isSourceRootAllowed(it) })

    @get:Internal("Not an input, just passed as kapt args. ")
    abstract val kaptClasspath: ConfigurableFileCollection

    /* Used as input as empty kapt classpath should not trigger stub generation, but a non-empty one should. */
    @Input
    fun getIfKaptClasspathIsPresent() = !kaptClasspath.isEmpty

    override fun source(vararg sources: Any): SourceTask {
        return super.source(sourceRootsContainer.add(sources))
    }

    override fun setSource(sources: Any) {
        super.setSource(sourceRootsContainer.set(sources))
    }

    private fun isSourceRootAllowed(source: File): Boolean =
        !destinationDir.isParentOf(source) &&
                !stubsDir.asFile.get().isParentOf(source) &&
                excludedSourceDirs.get().none { it.isParentOf(source) }

//    @get:Internal
//    internal val compileKotlinArgumentsContributor: Property<CompilerArgumentsContributor<K2JVMCompilerArguments>> =
//        objects.propertyWithNewInstance<CompilerArgumentsContributor<K2JVMCompilerArguments>>().value(
//            project.provider {
//                KotlinJvmCompilerArgumentsProvider(
//
//                )
//            }
//        )

    override fun setupCompilerArgs(args: K2JVMCompilerArguments, defaultsOnly: Boolean, ignoreClasspathResolutionErrors: Boolean) {
        compilerArgumentsContributor.contributeArguments(args, compilerArgumentsConfigurationFlags(
            defaultsOnly,
            ignoreClasspathResolutionErrors
        ))

        val pluginOptionsWithKapt = pluginOptions.withWrappedKaptOptions(withApClasspath = kaptClasspath)
        args.pluginOptions = (pluginOptionsWithKapt.arguments + args.pluginOptions!!).toTypedArray()

        args.verbose = verbose.get()
        args.classpathAsList = this.classpath.filter { it.exists() }.toList()
        args.destinationAsFile = this.destinationDir
    }

    private val jvmSourceRoots by lazy { SourceRoots.ForJvm(this.source, javaSourceRoots) }

    override fun getSourceRoots(): SourceRoots.ForJvm = jvmSourceRoots
}