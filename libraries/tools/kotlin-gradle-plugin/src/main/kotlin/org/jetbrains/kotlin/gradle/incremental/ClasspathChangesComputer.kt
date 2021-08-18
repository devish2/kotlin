/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.incremental

import com.intellij.openapi.util.io.FileUtil
import org.jetbrains.kotlin.build.report.BuildReporter
import org.jetbrains.kotlin.build.report.ICReporter
import org.jetbrains.kotlin.build.report.metrics.BuildAttribute
import org.jetbrains.kotlin.build.report.metrics.BuildMetrics
import org.jetbrains.kotlin.build.report.metrics.BuildMetricsReporter
import org.jetbrains.kotlin.build.report.metrics.BuildTime
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.incremental.ChangesCollector
import org.jetbrains.kotlin.incremental.ClasspathChanges
import org.jetbrains.kotlin.incremental.IncrementalJvmCache
import org.jetbrains.kotlin.incremental.getDirtyData
import org.jetbrains.kotlin.incremental.storage.FileToCanonicalPathConverter
import java.io.File
import java.util.*

/** Computes [ClasspathChanges] between two [ClasspathSnapshot]s .*/
object ClasspathChangesComputer {

    fun getChanges(currentSnapshot: ClasspathSnapshot, previousSnapshot: ClasspathSnapshot): ClasspathChanges {
        val classpathChanges = getChangesForClasses(currentSnapshot.getAllClassSnapshots(), previousSnapshot.getAllClassSnapshots()) as ClasspathChanges.Available
        println(classpathChanges.fqNames)
        return classpathChanges
    }

    // DEBUG NOTES:
    // This method diffs each class individually instead of diffing the whole classpath at once.
    // Rename this method to getChange() to replace the method above.
    fun getChangesDEBUG(currentSnapshot: ClasspathSnapshot, previousSnapshot: ClasspathSnapshot): ClasspathChanges {
        var i = 0
        currentSnapshot.getAllClassSnapshots().zip(previousSnapshot.getAllClassSnapshots()).forEach { (current, previous) ->
            i++
            if (i % 10 != 1) { // Can't run on the whole classpath so take a sample only
                return@forEach
            }
            val classpathChanges = getChangesForClasses(listOf(current), listOf(previous)) as ClasspathChanges.Available
            if (classpathChanges.fqNames.isNotEmpty()) {
                println("Diffing ${current.toString()} vs. ${previous.toString()}")
                println("    FqNames: ${classpathChanges.fqNames}")
            }
        }
        return ClasspathChanges.NotAvailable.UnableToCompute
    }

    private fun getChangesForClasses(currentClassSnapshots: List<ClassSnapshot>, previousClassSnapshots: List<ClassSnapshot>): ClasspathChanges {
        val workingDir =
            FileUtil.createTempDirectory(this::class.java.simpleName, "_WorkingDir_${UUID.randomUUID()}", /* deleteOnExit */ true)
        val incrementalJvmCache = IncrementalJvmCache(workingDir, /* targetOutputDir */ null, FileToCanonicalPathConverter)

        // Store previous snapshot in incrementalJvmCache, the returned ChangesCollector result is not used.
        val unusedChangesCollector = ChangesCollector()
        for (previous in previousClassSnapshots) {
            when (previous) {
                is KotlinClassSnapshot -> incrementalJvmCache.saveClassToCache(
                    kotlinClassInfo = previous.classInfo,
                    sourceFiles = null,
                    changesCollector = unusedChangesCollector
                )
                is RegularJavaClassSnapshot -> incrementalJvmCache.saveJavaClassProto(
                    source = null, previous.serializedJavaClass, unusedChangesCollector
                )
                is EmptyJavaClassSnapshot -> {
                    // Nothing to do
                }
            }
        }
//        incrementalJvmCache.clearCacheForRemovedClasses(unusedChangesCollector) // May or may not be needed

        // Compute changes between the current snapshot and the previously stored snapshot, and store the result in changesCollector.
        val changesCollector = ChangesCollector()
        for (current in currentClassSnapshots) {
            when (current) {
                is KotlinClassSnapshot -> incrementalJvmCache.saveClassToCache(
                    kotlinClassInfo = current.classInfo,
                    sourceFiles = null,
                    changesCollector = changesCollector
                )
                is RegularJavaClassSnapshot -> incrementalJvmCache.saveJavaClassProto(
                    source = null,
                    current.serializedJavaClass,
                    changesCollector
                )
                is EmptyJavaClassSnapshot -> {
                    // Nothing to do
                }
            }
        }
        incrementalJvmCache.clearCacheForRemovedClasses(changesCollector)

        val dirtyData = changesCollector.getDirtyData(listOf(incrementalJvmCache), NoOpBuildReporter.NoOpICReporter)
        workingDir.deleteRecursively()

        return ClasspathChanges.Available(dirtyData.dirtyLookupSymbols.sorted(), dirtyData.dirtyClassesFqNames.sortedBy { it.asString() })
    }

    private fun ClasspathSnapshot.getAllClassSnapshots(): List<ClassSnapshot> = classpathEntrySnapshots.flatMap { it.classSnapshots.values }

//    private fun ClasspathSnapshot.getAllClassSnapshots(): List<ClassSnapshot> =
//        classpathEntrySnapshots.flatMap { it.classSnapshots.values }.distinctBy { it.toString() }
}

private object NoOpBuildReporter : BuildReporter(NoOpICReporter, NoOpBuildMetricsReporter) {

    object NoOpICReporter : ICReporter {
        override fun report(message: () -> String) {}
        override fun reportVerbose(message: () -> String) {}
        override fun reportCompileIteration(incremental: Boolean, sourceFiles: Collection<File>, exitCode: ExitCode) {}
        override fun reportMarkDirtyClass(affectedFiles: Iterable<File>, classFqName: String) {}
        override fun reportMarkDirtyMember(affectedFiles: Iterable<File>, scope: String, name: String) {}
        override fun reportMarkDirty(affectedFiles: Iterable<File>, reason: String) {}
    }

    object NoOpBuildMetricsReporter : BuildMetricsReporter {
        override fun startMeasure(metric: BuildTime, startNs: Long) {}
        override fun endMeasure(metric: BuildTime, endNs: Long) {}
        override fun addAttribute(attribute: BuildAttribute) {}
        override fun getMetrics(): BuildMetrics = BuildMetrics()
        override fun addMetrics(metrics: BuildMetrics?) {}
    }
}
