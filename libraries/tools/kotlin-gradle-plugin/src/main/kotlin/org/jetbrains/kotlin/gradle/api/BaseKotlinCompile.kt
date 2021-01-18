///*
// * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
// * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
// */
//
//package org.jetbrains.kotlin.gradle.api
//
//import com.intellij.openapi.util.SystemInfo
//import com.intellij.util.lang.JavaVersion
//import org.gradle.api.Project
//import org.gradle.api.attributes.Attribute.*
//import org.gradle.api.file.*
//import org.gradle.api.internal.ConventionTask
//import org.gradle.api.model.ObjectFactory
//import org.gradle.api.provider.ListProperty
//import org.gradle.api.provider.MapProperty
//import org.gradle.api.provider.Property
//import org.gradle.api.provider.Provider
//import org.gradle.api.tasks.*
//import org.gradle.api.tasks.incremental.IncrementalTaskInputs
//import org.gradle.process.CommandLineArgumentProvider
//import org.gradle.workers.IsolationMode
//import org.gradle.workers.WorkAction
//import org.gradle.workers.WorkParameters
//import org.gradle.workers.WorkerExecutor
//import org.jetbrains.kotlin.build.DEFAULT_KOTLIN_SOURCE_FILES_EXTENSIONS
//import org.jetbrains.kotlin.build.report.metrics.BuildMetricsReporter
//import org.jetbrains.kotlin.build.report.metrics.BuildMetricsReporterImpl
//import org.jetbrains.kotlin.build.report.metrics.BuildTime
//import org.jetbrains.kotlin.build.report.metrics.measure
//import org.jetbrains.kotlin.cli.common.arguments.CommonCompilerArguments
//import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
//import org.jetbrains.kotlin.compilerRunner.*
//import org.jetbrains.kotlin.daemon.common.MultiModuleICSettings
//import org.jetbrains.kotlin.gradle.dsl.*
//import org.jetbrains.kotlin.gradle.dsl.KotlinJvmOptionsImpl
//import org.jetbrains.kotlin.gradle.incremental.ChangedFiles
//import org.jetbrains.kotlin.gradle.internal.*
//import org.jetbrains.kotlin.gradle.internal.Kapt3GradleSubplugin.Companion.isInfoAsWarnings
//import org.jetbrains.kotlin.gradle.internal.Kapt3GradleSubplugin.Companion.isKaptKeepKdocCommentsInStubs
//import org.jetbrains.kotlin.gradle.internal.Kapt3GradleSubplugin.Companion.isKaptVerbose
//import org.jetbrains.kotlin.gradle.internal.Kapt3GradleSubplugin.Companion.isIncrementalKapt
//import org.jetbrains.kotlin.gradle.internal.Kapt3GradleSubplugin.Companion.maybeRegisterTransform
//import org.jetbrains.kotlin.gradle.internal.kapt.incremental.*
//import org.jetbrains.kotlin.gradle.internal.tasks.TaskWithLocalState
//import org.jetbrains.kotlin.gradle.internal.tasks.allOutputFiles
//import org.jetbrains.kotlin.gradle.logging.GradleKotlinLogger
//import org.jetbrains.kotlin.gradle.logging.GradlePrintingMessageCollector
//import org.jetbrains.kotlin.gradle.logging.kotlinDebug
//import org.jetbrains.kotlin.gradle.plugin.*
//import org.jetbrains.kotlin.gradle.report.ReportingSettings
//import org.jetbrains.kotlin.gradle.tasks.*
//import org.jetbrains.kotlin.gradle.utils.*
//import org.jetbrains.kotlin.gradle.utils.isJavaFile
//import org.jetbrains.kotlin.gradle.utils.pathsAsStringRelativeTo
//import org.jetbrains.kotlin.gradle.utils.toSortedPathsArray
//import org.jetbrains.kotlin.incremental.ChangedFiles
//import org.jetbrains.kotlin.incremental.classpathAsList
//import org.jetbrains.kotlin.incremental.destinationAsFile
//import org.jetbrains.kotlin.utils.PathUtil
//import java.io.File
//import java.io.Serializable
//import java.net.URL
//import java.net.URLClassLoader
//import javax.inject.Inject
//import org.jetbrains.kotlin.gradle.dsl.KaptExtensionApi as KaptExtensionApi
//import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompile as DslKotlinJvmCompile
//
//abstract class BaseKotlinCompile<T : CommonCompilerArguments>() : AbstractKotlinCompileTool<T>() {
//    init {
//        cacheOnlyIfEnabledForKotlin()
//    }
//
//    @get:Inject
//    abstract val layout: ProjectLayout
//
//    @get:Inject
//    abstract val objectFactory: ObjectFactory
//
//    // avoid creating directory in getter: this can lead to failure in parallel build
//    @get:LocalState
//    val taskBuildDirectory: DirectoryProperty =
//        objectFactory.directoryProperty().value(
//            layout.buildDirectory.dir(KOTLIN_BUILD_DIR_NAME + "/" + name)
//        )
//
//    @get:LocalState
//    internal val localStateDirectoriesProvider: FileCollection = objectFactory.fileCollection().from(taskBuildDirectory)
//
//    override fun localStateDirectories(): FileCollection = localStateDirectoriesProvider
//
//    @get:Internal
//    var incremental: Boolean = false
//        get() = field
//        set(value) {
//            field = value
//            logger.kotlinDebug { "Set $this.incremental=$value" }
//        }
//
//    @Input
//    internal open fun isIncrementalCompilationEnabled(): Boolean =
//        incremental
//
//    @get:Internal
//    internal var reportingSettings = ReportingSettings()
//
//    @get:Input
//    abstract val useModuleDetection: Property<Boolean>
//
//    @get:Internal
//    protected val multiModuleICSettings: MultiModuleICSettings
//        get() = MultiModuleICSettings(taskBuildDirectory.file("build-history.bin").get().asFile, useModuleDetection.get())
//
//    @get:InputFiles
//    @get:Classpath
//    open val pluginClasspath: FileCollection = project.configurations.getByName(PLUGIN_CLASSPATH_CONFIGURATION_NAME)
//
//    @get:Internal
//    val pluginOptions = CompilerPluginOptions()
//
//    @get:Internal
//    val customPluginOptions = CompilerPluginOptions()
//
//    @get:Internal
//    protected val finalPluginOptions by lazy {
//        val finalOptions = CompilerPluginOptions()
//        finalOptions.addFrom(pluginOptions)
//        finalOptions.addFrom(customPluginOptions)
//        finalOptions
//    }
//
//    @get:Input
//    val sourceFilesExtensions: ListProperty<String> =
//        objectFactory.listProperty(String::class.java).value(DEFAULT_KOTLIN_SOURCE_FILES_EXTENSIONS)
//
//    @get:Input
//    abstract val coroutines: Property<Coroutines>
//
//    @get:InputFiles
//    @get:PathSensitive(PathSensitivity.RELATIVE)
//    internal var commonSourceSet: FileCollection = project.files()
//
//    @get:Input
//    abstract val moduleName: Property<String>
//
//    @get:Internal // takes part in the compiler arguments
//    abstract val friendPaths: ConfigurableFileCollection
//
//    private val kotlinLogger by lazy { GradleKotlinLogger(logger) }
//
//    /** Keep lazy to avoid computing before all projects are evaluated. */
//    @get:Internal
//    internal val compilerRunner by lazy { compilerRunner() }
//
//    internal open fun compilerRunner(): GradleCompilerRunner = GradleCompilerRunner(GradleCompileTaskProvider(this))
//
//    @TaskAction
//    fun execute(inputs: IncrementalTaskInputs) {
//        // If task throws exception, but its outputs are changed during execution,
//        // then Gradle forces next build to be non-incremental (see Gradle's DefaultTaskArtifactStateRepository#persistNewOutputs)
//        // To prevent this, we backup outputs before incremental build and restore when exception is thrown
//        val outputsBackup: TaskOutputsBackup? =
//            if (isIncrementalCompilationEnabled() && inputs.isIncremental)
//                metrics.measure(BuildTime.BACKUP_OUTPUT) {
//                    TaskOutputsBackup(allOutputFiles())
//                }
//            else null
//
//        if (!isIncrementalCompilationEnabled()) {
//            clearLocalState("IC is disabled")
//        } else if (!inputs.isIncremental) {
//            clearLocalState("Task cannot run incrementally")
//        }
//
//        try {
//            executeImpl(inputs)
//        } catch (t: Throwable) {
//            if (outputsBackup != null) {
//                metrics.measure(BuildTime.RESTORE_OUTPUT_FROM_BACKUP) {
//                    outputsBackup.restoreOutputs()
//                }
//            }
//            throw t
//        }
//    }
//
//    protected open fun skipCondition(inputs: IncrementalTaskInputs): Boolean {
//        return !inputs.isIncremental && getSourceRoots().kotlinSourceFiles.isEmpty()
//    }
//
//    @get:Internal
//    private val projectDir = project.rootProject.projectDir
//
//    private fun executeImpl(inputs: IncrementalTaskInputs) {
//        // Check that the JDK tools are available in Gradle (fail-fast, instead of a fail during the compiler run):
//        findToolsJar()
//
//        val sourceRoots = getSourceRoots()
//        val allKotlinSources = sourceRoots.kotlinSourceFiles
//
//        logger.kotlinDebug { "All kotlin sources: ${allKotlinSources.pathsAsStringRelativeTo(projectDir)}" }
//
//        if (skipCondition(inputs)) {
//            // Skip running only if non-incremental run. Otherwise, we may need to do some cleanup.
//            logger.kotlinDebug { "No Kotlin files found, skipping Kotlin compiler task" }
//            return
//        }
//
//        sourceRoots.log(this.name, logger)
//        val args = prepareCompilerArguments()
//        taskBuildDirectory.get().asFile.mkdirs()
//        callCompilerAsync(args, sourceRoots, ChangedFiles(inputs))
//    }
//
//    @Internal
//    internal abstract fun getSourceRoots(): SourceRoots
//
//    /**
//     * Compiler might be executed asynchronously. Do not do anything requiring end of compilation after this function is called.
//     * @see [GradleKotlinCompilerWork]
//     */
//    internal abstract fun callCompilerAsync(args: T, sourceRoots: SourceRoots, changedFiles: ChangedFiles)
//
//    @get:Input
//    abstract val enableMultiplatform: Property<Boolean>
//
//    @get:Internal
//    internal val abstractKotlinCompileArgumentsContributor by lazy {
//        AbstractKotlinCompileArgumentsContributor<T>(
//            KotlinCompileArgumentsProvider(coroutines, logger, enableMultiplatform.get(), pluginClasspath, finalPluginOptions)
//        )
//    }
//
//    override fun setupCompilerArgs(args: T, defaultsOnly: Boolean, ignoreClasspathResolutionErrors: Boolean) {
//        abstractKotlinCompileArgumentsContributor.contributeArguments(
//            args,
//            compilerArgumentsConfigurationFlags(defaultsOnly, ignoreClasspathResolutionErrors)
//        )
//    }
//
//    internal fun setupPlugins(compilerArgs: T) {
//        compilerArgs.pluginClasspaths = pluginClasspath.toSortedPathsArray()
//        compilerArgs.pluginOptions = finalPluginOptions.arguments.toTypedArray()
//    }
//
//    protected fun hasFilesInTaskBuildDirectory(): Boolean {
//        val taskBuildDir = taskBuildDirectory.get().asFile
//        return taskBuildDir.walk().any { it != taskBuildDir && it.isFile }
//    }
//}
//
//
//@CacheableTask
//abstract class KotlinJvmCompile : BaseKotlinCompile<K2JVMCompilerArguments>(), DslKotlinJvmCompile {
//    @get:Internal
//    internal val parentKotlinOptionsImpl: KotlinJvmOptionsImpl
//        get() = parentOptionsProperty.get() as KotlinJvmOptionsImpl
//
//    override val kotlinOptions: KotlinJvmOptions get() = kotlinOptionsProperty.get()
//
//    @get:Internal
//    abstract val kotlinOptionsProperty: Property<KotlinJvmOptions>
//
//    @get:Internal
//    abstract val parentOptionsProperty: Property<KotlinJvmOptions>
//
//    @get:Internal
//    internal open val sourceRootsContainer = FilteringSourceRootsContainer()
//
//    /** A package prefix that is used for locating Java sources in a directory structure with non-full-depth packages.
//     *
//     * Example: a Java source file with `package com.example.my.package` is located in directory `src/main/java/my/package`.
//     * Then, for the Kotlin compilation to locate the source file, use package prefix `"com.example"` */
//    @get:Input
//    @get:Optional
//    var javaPackagePrefix: String? = null
//
//    @get:Input
//    var usePreciseJavaTracking: Boolean = true
//        set(value) {
//            field = value
//            logger.kotlinDebug { "Set $this.usePreciseJavaTracking=$value" }
//        }
//
//    init {
//        incremental = true
//    }
//
//    override fun findKotlinCompilerClasspath(project: Project): List<File> =
//        findKotlinJvmCompilerClasspath(project)
//
//    override fun createCompilerArgs(): K2JVMCompilerArguments =
//        K2JVMCompilerArguments()
//
//    override fun setupCompilerArgs(args: K2JVMCompilerArguments, defaultsOnly: Boolean, ignoreClasspathResolutionErrors: Boolean) {
//        compilerArgumentsContributor.contributeArguments(
//            args, compilerArgumentsConfigurationFlags(
//                defaultsOnly,
//                ignoreClasspathResolutionErrors
//            )
//        )
//    }
//
//    @get:Internal
//    internal val compilerArgumentsContributor: CompilerArgumentsContributor<K2JVMCompilerArguments> by lazy {
//        KotlinJvmCompilerArgumentsContributor(
//            KotlinJvmCompilerArgumentsProvider(
//                moduleName.get(),
//                friendPaths,
//                classpath.files,
//                destinationDir,
//                listOf(parentKotlinOptionsImpl, kotlinOptions as KotlinJvmOptionsImpl),
//                coroutines,
//                logger,
//                enableMultiplatform.get(),
//                pluginClasspath,
//                finalPluginOptions
//            )
//        )
//    }
//
//    @Internal
//    override fun getSourceRoots() = SourceRoots.ForJvm.create(getSource(), sourceRootsContainer, sourceFilesExtensions.get())
//
//    override fun callCompilerAsync(args: K2JVMCompilerArguments, sourceRoots: SourceRoots, changedFiles: ChangedFiles) {
//        sourceRoots as SourceRoots.ForJvm
//
//        val messageCollector = GradlePrintingMessageCollector(logger, args.allWarningsAsErrors)
//        val outputItemCollector = OutputItemsCollectorImpl()
//        val compilerRunner = compilerRunner
//
//        val icEnv = if (isIncrementalCompilationEnabled()) {
//            logger.info(USING_JVM_INCREMENTAL_COMPILATION_MESSAGE)
//            IncrementalCompilationEnvironment(
//                if (hasFilesInTaskBuildDirectory()) changedFiles else ChangedFiles.Unknown(),
//                taskBuildDirectory.get().asFile,
//                usePreciseJavaTracking = usePreciseJavaTracking,
//                disableMultiModuleIC = disableMultiModuleIC.get(),
//                multiModuleICSettings = multiModuleICSettings
//            )
//        } else null
//
//        val environment = GradleCompilerEnvironment(
//            computedCompilerClasspath, messageCollector, outputItemCollector,
//            outputFiles = allOutputFiles(),
//            reportingSettings = reportingSettings,
//            incrementalCompilationEnvironment = icEnv,
//            kotlinScriptExtensions = sourceFilesExtensions.get().toTypedArray()
//        )
//        compilerRunner.runJvmCompilerAsync(
//            sourceRoots.kotlinSourceFiles,
//            commonSourceSet.toList(),
//            sourceRoots.javaSourceRoots,
//            javaPackagePrefix,
//            args,
//            environment
//        )
//    }
//
//    @get:Input
//    abstract val disableMultiModuleIC: Property<Boolean>
//
//    // override setSource to track source directory sets and files (for generated android folders)
//    override fun setSource(sources: Any) {
//        sourceRootsContainer.set(sources)
//        super.setSource(sources)
//    }
//
//    // override source to track source directory sets and files (for generated android folders)
//    override fun source(vararg sources: Any): SourceTask {
//        sourceRootsContainer.add(*sources)
//        return super.source(*sources)
//    }
//}
//
//@CacheableTask
//abstract class KaptGenerateStubsTask : KotlinJvmCompile() {
//    @get:OutputDirectory
//    abstract val stubsDir: DirectoryProperty
//
//    @get:Internal
//    lateinit var generatedSourcesDirs: List<File>
//
//    /** All Kotlin compiler plugins used. */
//    @get:Classpath
//    @get:InputFiles
//    @Suppress("unused")
//    abstract val kotlinTaskPluginClasspath: ConfigurableFileCollection
//
//    @get:Input
//    val verbose = (project.hasProperty("kapt.verbose") && project.property("kapt.verbose").toString().toBoolean())
//
//    override fun setupCompilerArgs(args: K2JVMCompilerArguments, defaultsOnly: Boolean, ignoreClasspathResolutionErrors: Boolean) {
//
//        listOf<SubpluginOption>(
//            FilesSubpluginOption("incrementalData", listOf(destinationDirectory.get().asFile)),
//            FilesSubpluginOption("stubs", listOf(stubsDir.get().asFile)),
//            FilesSubpluginOption("sources", listOf(File(""))),
//            FilesSubpluginOption("classes", listOf(File("")))
//        ).forEach {
//            pluginOptions.addPluginArgument(
//                "org.jetbrains.kotlin.kapt3",
//                it
//            )
//        }
//
//        val kaptPluginOptions = pluginOptions.subpluginOptionsByPluginId.remove("org.jetbrains.kotlin.kapt3")!!
//
//        val kaptCompilerOptions = with(CompilerPluginOptions()) {
//            kaptPluginOptions.forEach { option ->
//                addPluginArgument("org.jetbrains.kotlin.kapt3", option)
//            }
//            this
//        }
//        compilerArgumentsContributor.contributeArguments(
//            args, compilerArgumentsConfigurationFlags(
//                defaultsOnly,
//                ignoreClasspathResolutionErrors
//            )
//        )
//
//        kaptPluginOptions.forEach {
//            pluginOptions.addPluginArgument("org.jetbrains.kotlin.kapt3", it)
//        }
//
//        val pluginOptionsWithKapt = kaptCompilerOptions.withWrappedKaptOptions(withApClasspath = listOf(File("")))
//        args.pluginOptions = (pluginOptionsWithKapt.arguments + args.pluginOptions!!).toTypedArray()
//
//        args.verbose = verbose
//        args.classpathAsList = this.classpath.filter { it.exists() }.toList()
//        args.destinationAsFile = this.destinationDir
//    }
//}
//
//private val artifactType = of("artifactType", String::class.java)
//
//
//@CacheableTask
//abstract class KaptKotlinTask : ConventionTask(), TaskWithLocalState {
//    init {
//        cacheOnlyIfEnabledForKotlin()
//
//        val reason = "Caching is disabled for kapt with 'kapt.useBuildCache'"
//        outputs.cacheIf(reason) { useBuildCache.get() }
//    }
//
//    override fun localStateDirectories(): FileCollection = objects.fileCollection()
//
//    @get:Inject
//    abstract val workerExecutor: WorkerExecutor
//
//    @get:Inject
//    abstract val objects: ObjectFactory
//
//    @get:Classpath
//    @get:InputFiles
//    abstract val kaptClasspath: ConfigurableFileCollection
//
//    @get:Input
//    internal val enableIncremental = project.isIncrementalKapt()
//
//    @get:PathSensitive(PathSensitivity.NONE)
//    @get:InputFiles
//    internal var classpathStructure: FileCollection = if (enableIncremental) {
//        maybeRegisterTransform(project)
//
//        val classStructureIfIncremental = project.configurations.create("_classStructure${name}")
//        project.dependencies.add(classStructureIfIncremental.name, project.files(project.provider { classpath }))
//
//        classStructureIfIncremental!!.incoming.artifactView { viewConfig ->
//            viewConfig.attributes.attribute(artifactType, CLASS_STRUCTURE_ARTIFACT_TYPE)
//        }.files
//    } else objects.fileCollection()
//
//    @get:LocalState
//    @get:Optional
//    abstract val incAptCache: DirectoryProperty
//
//    @get:OutputDirectory
//    abstract val classesDir: DirectoryProperty
//
//    @get:OutputDirectory
//    abstract val destinationDir: DirectoryProperty
//
//    /** Used in the model builder only. */
//    @get:OutputDirectory
//    abstract val kotlinSourcesDestinationDir: DirectoryProperty
//
//    @get:Nested
//    internal val annotationProcessorOptionProviders: MutableList<Any> = mutableListOf()
//
//    @get:CompileClasspath
//    abstract val classpath: ConfigurableFileCollection
//
//    @get:Internal
//    abstract val useBuildCache: Property<Boolean>
//
//    @get:InputFiles
//    @get:PathSensitive(PathSensitivity.RELATIVE)
//    val source: FileCollection = javaSourceRoots.asFileTree.filter { it.isJavaFile() }
//
//    /** Use [source] as input, as only .java files should be taken into account. */
//    @get:Internal
//    abstract val javaSourceRoots: ConfigurableFileCollection
//
//    @get:Internal
//    override val metrics: BuildMetricsReporter =
//        BuildMetricsReporterImpl()
//
//    // Here we assume that kotlinc and javac output is available for incremental runs. We should insert some checks.
//    @get:Internal
//    abstract val compiledSources: ListProperty<File>
//
//    protected fun getIncrementalChanges(inputs: IncrementalTaskInputs): KaptIncrementalChanges {
//        return if (enableIncremental) {
//            findClasspathChanges(inputs)
//        } else {
//            clearLocalState()
//            KaptIncrementalChanges.Unknown
//        }
//    }
//
//    private fun findClasspathChanges(inputs: IncrementalTaskInputs): KaptIncrementalChanges {
//        val incAptCacheDir = incAptCache!!
//        incAptCacheDir.asFile.get().mkdirs()
//
//        val allDataFiles = classpathStructure!!.files
//        val changedFiles = if (inputs.isIncremental) {
//            with(mutableSetOf<File>()) {
//                inputs.outOfDate { this.add(it.file) }
//                inputs.removed { this.add(it.file) }
//                return@with this
//            }
//        } else {
//            allDataFiles
//        }
//
//        val startTime = System.currentTimeMillis()
//
//        val previousSnapshot = if (inputs.isIncremental) {
//            val loadedPrevious = ClasspathSnapshot.ClasspathSnapshotFactory.loadFrom(incAptCacheDir.get().asFile)
//
//            val previousAndCurrentDataFiles = lazy { loadedPrevious.getAllDataFiles() + allDataFiles }
//            val allChangesRecognized = changedFiles.all {
//                val extension = it.extension
//                if (extension.isEmpty() || extension == "java" || extension == "jar" || extension == "class") {
//                    return@all true
//                }
//                // if not a directory, Java source file, jar, or class, it has to be a structure file, in order to understand changes
//                it in previousAndCurrentDataFiles.value
//            }
//            if (allChangesRecognized) {
//                loadedPrevious
//            } else {
//                ClasspathSnapshot.ClasspathSnapshotFactory.getEmptySnapshot()
//            }
//        } else {
//            ClasspathSnapshot.ClasspathSnapshotFactory.getEmptySnapshot()
//        }
//        val currentSnapshot =
//            ClasspathSnapshot.ClasspathSnapshotFactory.createCurrent(
//                incAptCacheDir.get().asFile,
//                classpath.files.toList(),
//                kaptClasspath.files.toList(),
//                allDataFiles
//            )
//
//        val classpathChanges = currentSnapshot.diff(previousSnapshot, changedFiles)
//        if (classpathChanges == KaptClasspathChanges.Unknown) {
//            // We are unable to determine classpath changes, so clean the local state as we will run non-incrementally
//            clearLocalState()
//        }
//        currentSnapshot.writeToCache()
//
//        if (logger.isInfoEnabled) {
//            val time = "Took ${System.currentTimeMillis() - startTime}ms."
//            when {
//                previousSnapshot == UnknownSnapshot ->
//                    logger.info("Initializing classpath information for KAPT. $time")
//                classpathChanges == KaptClasspathChanges.Unknown ->
//                    logger.info("Unable to use existing data, re-initializing classpath information for KAPT. $time")
//                else -> {
//                    classpathChanges as KaptClasspathChanges.Known
//                    logger.info("Full list of impacted classpath names: ${classpathChanges.names}. $time")
//                }
//            }
//        }
//        return when (classpathChanges) {
//            is KaptClasspathChanges.Unknown -> KaptIncrementalChanges.Unknown
//            is KaptClasspathChanges.Known -> KaptIncrementalChanges.Known(
//                changedFiles.filter { it.extension == "java" }.toSet(), classpathChanges.names
//            )
//        }
//    }
//
//    @get:InputFiles
//    @get:Classpath
//    abstract val kaptJars: ConfigurableFileCollection
//
//    @get:Internal
//    internal val enableVerbose = project.isKaptVerbose()
//
//    @get:Input
//    abstract val mapDiagnosticLocations: Property<Boolean>
//
//    @get:Input
//    abstract val annotationProcessorFqNames: ListProperty<String>
//
//    @get:Input
//    abstract val processorOptions: MapProperty<String, String>
//
//    @get:Input
//    abstract val javacOptions: MapProperty<String, String>
//
//    @get:Input
//    abstract val sourceCompatibility: Property<org.gradle.api.JavaVersion>
//
//    @get:Input
//    internal val kotlinAndroidPluginWrapperPluginDoesNotExist = project.plugins.none { it is KotlinAndroidPluginWrapper }
//
//    @get:Classpath
//    internal val kotlinStdlibClasspath = findKotlinStdlibClasspath(project)
//
//    @get:Internal
//    internal val projectDir = project.projectDir
//
//    @get:Internal
//    internal val providers = project.providers
//
//    @TaskAction
//    fun compile(inputs: IncrementalTaskInputs) {
//        logger.info("Running kapt annotation processing using the Gradle Worker API")
//
//        val incrementalChanges = getIncrementalChanges(inputs)
//        val (changedFiles, classpathChanges) = when (incrementalChanges) {
//            is KaptIncrementalChanges.Unknown -> Pair(emptyList<File>(), emptyList<String>())
//            is KaptIncrementalChanges.Known -> Pair(incrementalChanges.changedSources.toList(), incrementalChanges.changedClasspathJvmNames)
//        }
//
//        val compileClasspath = classpath.files.toMutableList()
//        if (kotlinAndroidPluginWrapperPluginDoesNotExist) {
//            compileClasspath.addAll(0, PathUtil.getJdkClassesRootsFromCurrentJre())
//        }
//
//        val kaptFlagsForWorker = mutableSetOf<String>().apply {
//            if (enableVerbose) add("VERBOSE")
//            if (mapDiagnosticLocations.get()) add("MAP_DIAGNOSTIC_LOCATIONS")
//            if (incrementalChanges is KaptIncrementalChanges.Known) add("INCREMENTAL_APT")
//        }
//
//        val kaptProcessorOptions = processorOptions.get().toMutableMap().apply {
//            this["kapt.kotlin.generated"] = kotlinSourcesDestinationDir.get().asFile.canonicalPath
//            annotationProcessorOptionProviders.forEach {
//                (it as CommandLineArgumentProvider).asArguments().forEach { commandLineArg ->
//                    this[commandLineArg.removePrefix("-A")] = ""
//                }
//            }
//        }
//
//        val optionsForWorker = KaptOptionsForWorker(
//            projectDir,
//            compileClasspath,
//            javaSourceRoots.files.toList(),
//
//            changedFiles,
//            compiledSources.get(),
//            incAptCache.orNull?.asFile,
//            classpathChanges.toList(),
//
//            destinationDir.get().asFile,
//            classesDir.get().asFile,
//            javaSourceRoots.files.first(),
//
//            kaptClasspath.files.toList(),
//            annotationProcessorFqNames.get(),
//
//            kaptProcessorOptions,
//            javacOptions.get(),
//
//            kaptFlagsForWorker
//        )
//
//        // Skip annotation processing if no annotation processors were provided.
//        if (annotationProcessorFqNames.get().isEmpty() && kaptClasspath.isEmpty())
//            return
//
//        val kaptClasspath = kaptJars + kotlinStdlibClasspath
//
//        //TODO for gradle < 6.5
//        val isolationModeStr = getValue("kapt.workers.isolation") ?: "none"
//        val isolationMode = when (isolationModeStr.toLowerCase()) {
//            "process" -> IsolationMode.PROCESS
//            "none" -> IsolationMode.NONE
//            else -> IsolationMode.NONE
//        }
//        val toolsJarURLSpec = findToolsJar()?.toURI()?.toURL()?.toString().orEmpty()
//
//        submitWork(
//            isolationMode,
//            optionsForWorker,
//            toolsJarURLSpec,
//            kaptClasspath
//        )
//    }
//
//    private fun submitWork(
//        isolationMode: IsolationMode,
//        optionsForWorker: KaptOptionsForWorker,
//        toolsJarURLSpec: String,
//        kaptClasspath: List<File>
//    ) {
//        val workQueue = when (isolationMode) {
//            IsolationMode.PROCESS -> workerExecutor.processIsolation {
//                if (getValue("kapt.workers.log.classloading") == "true") {
//                    // for tests
//                    it.forkOptions.jvmArgs("-verbose:class")
//                }
//                logger.info("Kapt worker classpath: ${it.classpath}")
//            }
//            IsolationMode.CLASSLOADER -> workerExecutor.classLoaderIsolation() {
//                logger.info("Kapt worker classpath: ${it.classpath}")
//            }
//            IsolationMode.NONE -> workerExecutor.noIsolation()
//            IsolationMode.AUTO -> throw UnsupportedOperationException(
//                "Kapt worker compilation does not support $isolationMode"
//            )
//        }
//
//        workQueue.submit(KaptExecutionWorkAction::class.java) {
//            it.workerOptions.set(optionsForWorker)
//            it.toolsJarURLSpec.set(toolsJarURLSpec)
//            it.kaptClasspath.setFrom(kaptClasspath)
//        }
//    }
//
//    internal fun getValue(propertyName: String): String? =
//        if (isGradleVersionAtLeast(6, 5)) {
//            providers.gradleProperty(propertyName).forUseAtConfigurationTime().orNull
//        } else {
//            project.findProperty(propertyName) as String?
//        }
//
//    internal interface KaptWorkParameters : WorkParameters {
//        val workerOptions: Property<KaptOptionsForWorker>
//        val toolsJarURLSpec: Property<String>
//        val kaptClasspath: ConfigurableFileCollection
//    }
//
//    internal abstract class KaptExecutionWorkAction : WorkAction<KaptWorkParameters> {
//        override fun execute() {
//            KaptExecution(
//                parameters.workerOptions.get(),
//                parameters.toolsJarURLSpec.get(),
//                parameters.kaptClasspath.toList()
//            ).run()
//        }
//    }
//}
//
//
//private class KaptExecution @Inject constructor(
//    val optionsForWorker: KaptOptionsForWorker,
//    val toolsJarURLSpec: String,
//    val kaptClasspath: List<File>
//) : Runnable {
//    private companion object {
//        private const val JAVAC_CONTEXT_CLASS = "com.sun.tools.javac.util.Context"
//
//        private fun kaptClass(classLoader: ClassLoader) = Class.forName("org.jetbrains.kotlin.kapt3.base.Kapt", true, classLoader)
//        private var cachedClassLoaderWithToolsJar: ClassLoader? = null
//        private var cachedKaptClassLoader: ClassLoader? = null
//    }
//
//    override fun run(): Unit = with(optionsForWorker) {
//        val kaptClasspathUrls = kaptClasspath.map { it.toURI().toURL() }.toTypedArray()
//        val rootClassLoader = findRootClassLoader()
//
//        val classLoaderWithToolsJar = cachedClassLoaderWithToolsJar ?: if (!toolsJarURLSpec.isEmpty() && !javacIsAlreadyHere()) {
//            URLClassLoader(arrayOf(URL(toolsJarURLSpec)), rootClassLoader)
//        } else {
//            rootClassLoader
//        }
//        cachedClassLoaderWithToolsJar = classLoaderWithToolsJar
//
//        val kaptClassLoader = cachedKaptClassLoader ?: URLClassLoader(kaptClasspathUrls, classLoaderWithToolsJar)
//        cachedKaptClassLoader = kaptClassLoader
//
//        val kaptMethod = kaptClass(kaptClassLoader).declaredMethods.single { it.name == "kapt" }
//        kaptMethod.invoke(null, createKaptOptions(kaptClassLoader))
//    }
//
//    private fun javacIsAlreadyHere(): Boolean {
//        return try {
//            Class.forName(JAVAC_CONTEXT_CLASS, false, KaptExecution::class.java.classLoader) != null
//        } catch (e: Throwable) {
//            false
//        }
//    }
//
//    private fun createKaptOptions(classLoader: ClassLoader) = with(optionsForWorker) {
//        val flags = kaptClass(classLoader).declaredMethods.single { it.name == "kaptFlags" }.invoke(null, flags)
//
//        val mode = Class.forName("org.jetbrains.kotlin.base.kapt3.AptMode", true, classLoader)
//            .enumConstants.single { (it as Enum<*>).name == "APT_ONLY" }
//
//        val detectMemoryLeaksMode = Class.forName("org.jetbrains.kotlin.base.kapt3.DetectMemoryLeaksMode", true, classLoader)
//            .enumConstants.single { (it as Enum<*>).name == "NONE" }
//
//        Class.forName("org.jetbrains.kotlin.base.kapt3.KaptOptions", true, classLoader).constructors.single().newInstance(
//            projectBaseDir,
//            compileClasspath,
//            javaSourceRoots,
//
//            changedFiles,
//            compiledSources,
//            incAptCache,
//            classpathChanges,
//
//            sourcesOutputDir,
//            classesOutputDir,
//            stubsOutputDir,
//            stubsOutputDir, // sic!
//
//            processingClasspath,
//            processors,
//
//            processingOptions,
//            javacOptions,
//
//            flags,
//            mode,
//            detectMemoryLeaksMode
//        )
//    }
//
//    private fun findRootClassLoader(): ClassLoader {
//        tailrec fun parentOrSelf(classLoader: ClassLoader): ClassLoader {
//            val parent = classLoader.parent ?: return classLoader
//            return parentOrSelf(parent)
//        }
//        return parentOrSelf(KaptExecution::class.java.classLoader)
//    }
//}
//
//internal data class KaptOptionsForWorker(
//    val projectBaseDir: File,
//    val compileClasspath: List<File>,
//    val javaSourceRoots: List<File>,
//
//    val changedFiles: List<File>,
//    val compiledSources: List<File>,
//    val incAptCache: File?,
//    val classpathChanges: List<String>,
//
//    val sourcesOutputDir: File,
//    val classesOutputDir: File,
//    val stubsOutputDir: File,
//
//    val processingClasspath: List<File>,
//    val processors: List<String>,
//
//    val processingOptions: Map<String, String>,
//    val javacOptions: Map<String, String>,
//
//    val flags: Set<String>
//) : Serializable
//
//@Suppress("unused")
//object KotlinJvmDslFactory {
//    fun createKotlinJvmDsl(): KotlinJvmOptions = KotlinJvmOptionsImpl()
//
//    fun createKaptExtension(factory: (Class<out KaptExtensionApi>) -> KaptExtensionApi): KaptExtensionApi =
//        factory.invoke(KaptExtensionApi::class.java)
//
//    fun createKotlinProjectExtension(factory: (Class<out KotlinProjectExtensionConfig>) -> KotlinProjectExtensionConfig): KotlinProjectExtensionConfig =
//        factory.invoke(KotlinProjectExtension::class.java)
//
//
//    fun configureFromExtension(ext: KotlinProjectExtensionConfig, task: BaseKotlinCompile<*>) {
//        //TODO: apply all args from the extension
//        task.coroutines.set(task.project.provider { ext.experimental.coroutines ?: Coroutines.DEFAULT })
//    }
//
//    fun configureFromExtension(kaptExtension: KaptExtensionApi, task: KaptGenerateStubsTask) {
//        val pluginOptions = mutableListOf<SubpluginOption>()
//        val project = task.project
//        pluginOptions += SubpluginOption("aptMode", "stubs")
//        pluginOptions += SubpluginOption("useLightAnalysis", "${kaptExtension.useLightAnalysis}")
//        pluginOptions += SubpluginOption("correctErrorTypes", "${kaptExtension.correctErrorTypes}")
//        pluginOptions += SubpluginOption("dumpDefaultParameterValues", "${kaptExtension.dumpDefaultParameterValues}")
//        pluginOptions += SubpluginOption("mapDiagnosticLocations", "${kaptExtension.mapDiagnosticLocations}")
//        pluginOptions += SubpluginOption("strictMode", "${kaptExtension.strictMode}")
//        pluginOptions += SubpluginOption("stripMetadata", "${kaptExtension.stripMetadata}")
//        pluginOptions += SubpluginOption("keepKdocCommentsInStubs", "${project.isKaptKeepKdocCommentsInStubs()}")
//        pluginOptions += SubpluginOption("showProcessorTimings", "${kaptExtension.showProcessorTimings}")
//        pluginOptions += SubpluginOption("detectMemoryLeaks", kaptExtension.detectMemoryLeaks)
//        pluginOptions += SubpluginOption("infoAsWarnings", "${project.isInfoAsWarnings()}")
//        pluginOptions += SubpluginOption("processors", "fake_proc")
//
//        if (project.isKaptVerbose()) {
//            pluginOptions += SubpluginOption("verbose", "true")
//        }
//        pluginOptions.forEach {
//            task.pluginOptions.addPluginArgument("org.jetbrains.kotlin.kapt3", it)
//        }
//
//    }
//
//    fun configureFromExtension(kaptExtension: KaptExtensionApi, task: KaptKotlinTask) {
//        kaptExtension as org.jetbrains.kotlin.gradle.plugin.KaptExtension
//        val project = task.project
//        val dslJavacOptions: Provider<Map<String, String>> = project.provider {
//            kaptExtension.getJavacOptions().toMutableMap().also { result ->
//                if ("-source" !in result && "--source" !in result && "--release" !in result) {
//                    val atLeast12Java =
//                        if (isConfigurationCacheAvailable(project.gradle)) {
//                            val currentJavaVersion =
//                                JavaVersion.parse(project.providers.systemProperty("java.version").forUseAtConfigurationTime().get())
//                            currentJavaVersion.feature >= 12
//                        } else {
//                            SystemInfo.isJavaVersionAtLeast(12, 0, 0)
//                        }
//                    val sourceOptionKey = if (atLeast12Java) {
//                        "--source"
//                    } else {
//                        "-source"
//                    }
//                    result[sourceOptionKey] = task.sourceCompatibility.get().toString()
//                }
//            }
//        }
//
//        task.mapDiagnosticLocations.set(kaptExtension.mapDiagnosticLocations)
//        task.annotationProcessorFqNames.set(kaptExtension.processors.split(',').filter { it.isNotEmpty() })
//        task.javacOptions.set(dslJavacOptions.get())
//        task.processorOptions.set(kaptExtension.getAdditionalArguments(project, null, null))
//    }
//}