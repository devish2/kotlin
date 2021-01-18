/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.plugin

import org.gradle.api.Project
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.gradle.dsl.KaptExtensionApi
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinTopLevelExtensionConfig
import org.jetbrains.kotlin.gradle.tasks.KaptGenerateStubsTaskApi
import org.jetbrains.kotlin.gradle.tasks.KaptKotlinTaskApi
import org.jetbrains.kotlin.gradle.tasks.KaptKotlinWithoutKotlincTaskApi
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompileApi

/** An API used by third-party plugins to integration with the Kotlin Gradle plugin. */
interface KotlinJvmFactory {
    /** Creates instance of DSL object that should be used to configure JVM/android specific compilation. */
    fun createKotlinJvmDsl(factory: (Class<out KotlinJvmOptions>) -> KotlinJvmOptions): KotlinJvmOptions

    /** Creates instance of DSL object that should be used to configure KAPT stub generation and annotation processing tasks. */
    fun createKaptExtension(factory: (Class<out KaptExtensionApi>) -> KaptExtensionApi): KaptExtensionApi

    /** Creates instance of DSL object that should be used to configure Kotlin compilation pipeline. */
    fun createKotlinProjectExtension(factory: (Class<out KotlinTopLevelExtensionConfig>) -> KotlinTopLevelExtensionConfig): KotlinTopLevelExtensionConfig

    /** Creates a Kotlin compile task. */
    fun createKotlinCompileTask(name: String, taskContainer: TaskContainer): TaskProvider<out KotlinJvmCompileApi>

    fun getKotlinCompileTaskType(): Class<out KotlinJvmCompileApi>
    fun createKotlinGenerateStubsTask(name: String, taskContainer: TaskContainer): TaskProvider<out KaptGenerateStubsTaskApi>
    fun createKotlinKaptTask(name: String, taskContainer: TaskContainer): TaskProvider<out KaptKotlinWithoutKotlincTaskApi>

    /** Gets the current version of the Kotlin Gradle plugin. */
    val pluginVersion: String

    /** Adds a compiler plugin dependency to this projects. This can be e.g Maven coordinates or a project included in the build. */
    fun addCompilerPluginDependency(project: Project, dependency: Any)
}