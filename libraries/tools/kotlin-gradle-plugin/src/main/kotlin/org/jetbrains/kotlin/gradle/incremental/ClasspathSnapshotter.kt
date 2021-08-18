/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.incremental

import org.jetbrains.kotlin.incremental.*
import org.jetbrains.kotlin.name.ClassId
import java.io.File
import java.util.zip.ZipInputStream

/** Computes a [ClasspathEntrySnapshot] of a classpath entry (directory or jar). */
@Suppress("SpellCheckingInspection")
object ClasspathEntrySnapshotter {

    private val DEFAULT_CLASS_FILTER = { unixStyleRelativePath: String, isDirectory: Boolean ->
        !isDirectory
                && unixStyleRelativePath.endsWith(".class", ignoreCase = true)
                && !unixStyleRelativePath.endsWith("module-info.class", ignoreCase = true)
                && !unixStyleRelativePath.startsWith("meta-inf", ignoreCase = true)
    }

    fun snapshot(classpathEntry: File): ClasspathEntrySnapshot {
        val classes =
            DirectoryOrJarContentsReader.read(classpathEntry, DEFAULT_CLASS_FILTER)
                .map { (unixStyleRelativePath, contents) ->
                    ClassFileWithContents(ClassFile(classpathEntry, unixStyleRelativePath), contents)
                }

        val snapshots = ClassSnapshotter.snapshot(classes)

        val relativePathsToSnapshotsMap =
            classes.map { it.classFile.unixStyleRelativePath }.zip(snapshots).toMap(LinkedHashMap())
        return ClasspathEntrySnapshot(relativePathsToSnapshotsMap)
    }
}

/** Creates [ClassSnapshot]s of classes. */
@Suppress("SpellCheckingInspection")
object ClassSnapshotter {

    /**
     * Creates [ClassSnapshot]s of the given classes.
     *
     * Note that for Java (non-Kotlin) classes, creating a [ClassSnapshot] for a nested class will require accessing the outer class (and
     * possibly vice versa). Therefore, outer classes and nested classes must be passed together in one invocation of this method.
     */
    fun snapshot(classes: List<ClassFileWithContents>): List<ClassSnapshot> {
        // Snapshot Kotlin classes first
        val kotlinClassSnapshots: Map<ClassFileWithContents, KotlinClassSnapshot?> = classes.associateWith {
            trySnapshotKotlinClass(it)
        }

        // Snapshot the remaining Java classes in one invocation
        val javaClasses: List<ClassFileWithContents> = classes.filter { kotlinClassSnapshots[it] == null }
        val snapshots: List<JavaClassSnapshot> = snapshotJavaClasses(javaClasses)
        val javaClassSnapshots: Map<ClassFileWithContents, JavaClassSnapshot> = javaClasses.zip(snapshots).toMap()

        // Return a snapshot for each class
        return classes.map { kotlinClassSnapshots[it] ?: javaClassSnapshots[it]!! }
    }

    /** Creates [KotlinClassSnapshot] of the given class, or returns `null` if the class is not a Kotlin class. */
    private fun trySnapshotKotlinClass(clazz: ClassFileWithContents): KotlinClassSnapshot? {
        return KotlinClassInfo.tryCreateFrom(clazz.contents)?.let {
            KotlinClassSnapshot(it)
        }
    }

    /**
     * Creates [JavaClassSnapshot]s of the given Java classes.
     *
     * Note that creating a [JavaClassSnapshot] for a nested class will require accessing the outer class (and possibly vice versa).
     * Therefore, outer classes and nested classes must be passed together in one invocation of this method.
     */
    private fun snapshotJavaClasses(classes: List<ClassFileWithContents>): List<JavaClassSnapshot> {
        val contents: List<ByteArray> = classes.map { it.contents }
        val classNames: List<JavaClassName> = contents.map { JavaClassName.compute(it) }
        val classContents: Map<JavaClassName, ByteArray> = classNames.zip(contents).toMap()

        // Compute `ClassId`s from `JavaClassName`s.
        // Note that we don't need to compute ClassId`s for certain classes as they won't be used (see below).
        val regularClasses = classNames.filterNot { it is LocalClass || (it is NestedNonLocalClass && (it.isAnonymous || it.isSynthetic)) }
        val classIdsOfRegularClasses: Map<JavaClassName, ClassId> = regularClasses.zip(computeJavaClassIds(regularClasses)).toMap()

        // Snapshot special cases first
        val specialCaseSnapshots: Map<JavaClassName, JavaClassSnapshot?> = classNames.associateWith { className ->
            when (className) {
                is TopLevelClass -> null // Snapshot later
                is LocalClass -> {
                    // A local class can't be referenced from other source files, so any changes in a local class will not cause
                    // recompilation of other source files. Therefore, the snapshot of a local class is empty.
                    EmptyJavaClassSnapshot
                }
                is NestedNonLocalClass -> {
                    if (classIdsOfRegularClasses[className]!!.isLocal) {
                        // A nested class of a local class is also considered local (has ClassId.isLocal == true, see ClassId's kdoc).
                        // It also can't impact recompilation of other source files.
                        EmptyJavaClassSnapshot
                    } else if (className.isAnonymous || className.isSynthetic) {
                        // Same for anonymous or synthetic classes.
                        EmptyJavaClassSnapshot
                    } else {
                        null // Snapshot later
                    }
                }
            }
        }

        // Snapshot the remaining classes in one invocation
        val snapshottedClasses = specialCaseSnapshots.filter { it.value != null }.keys
        val remainingClasses: List<JavaClassName> = classNames.filterNot { it in snapshottedClasses }
        val remainingClassIds: List<ClassId> = remainingClasses.map { classIdsOfRegularClasses[it]!! }
        val remainingClassesContents: List<ByteArray> = remainingClasses.map { classContents[it]!! }

        val snapshots: List<JavaClassSnapshot> = JavaClassDescriptorCreator.create(remainingClassIds, remainingClassesContents).map {
            RegularJavaClassSnapshot(it.toSerializedJavaClass())
        }
        val remainingSnapshots: Map<JavaClassName, JavaClassSnapshot> /* maps a class index to its snapshot */ =
            remainingClasses.zip(snapshots).toMap()

        // Return a snapshot for each class
        return classNames.map { specialCaseSnapshots[it] ?: remainingSnapshots[it]!! }
    }
}

/** Utility to read the contents of a directory or jar. */
private object DirectoryOrJarContentsReader {

    /**
     * Returns a map from Unix-style relative paths of entries to their contents. The paths are relative to the given container (directory or
     * jar).
     *
     * The map entries need to satisfy the given filter.
     *
     * The map entries are sorted based on their Unix-style relative paths (to ensure deterministic results across filesystems).
     *
     * Note: If a jar has duplicate entries, only one of them will be used (there is no guarantee which one will be used, but it will be
     * deterministic).
     */
    fun read(
        directoryOrJar: File,
        entryFilter: ((unixStyleRelativePath: String, isDirectory: Boolean) -> Boolean)? = null
    ): LinkedHashMap<String, ByteArray> {
        return if (directoryOrJar.isDirectory) {
            readDirectory(directoryOrJar, entryFilter)
        } else {
            check(
                directoryOrJar.isFile
                        && (directoryOrJar.path.endsWith(".jar", ignoreCase = true)
                        || directoryOrJar.path.endsWith(".zip", ignoreCase = true))
            )
            readJar(directoryOrJar, entryFilter)
        }
    }

    private fun readDirectory(
        directory: File,
        entryFilter: ((unixStyleRelativePath: String, isDirectory: Boolean) -> Boolean)? = null
    ): LinkedHashMap<String, ByteArray> {
        val relativePathsToContents: MutableList<Pair<String, ByteArray>> = mutableListOf()
        directory.walk().forEach { file ->
            val unixStyleRelativePath = file.relativeTo(directory).invariantSeparatorsPath
            if (entryFilter == null || entryFilter(unixStyleRelativePath, file.isDirectory)) {
                relativePathsToContents.add(unixStyleRelativePath to file.readBytes())
            }
        }
        return relativePathsToContents.sortedBy { it.first }.toMap(LinkedHashMap())
    }

    private fun readJar(
        jarFile: File,
        entryFilter: ((unixStyleRelativePath: String, isDirectory: Boolean) -> Boolean)? = null
    ): LinkedHashMap<String, ByteArray> {
        val relativePathsToContents: MutableList<Pair<String, ByteArray>> = mutableListOf()
        ZipInputStream(jarFile.inputStream().buffered()).use { zipInputStream ->
            while (true) {
                val entry = zipInputStream.nextEntry ?: break
                val unixStyleRelativePath = entry.name
                if (entryFilter == null || entryFilter(unixStyleRelativePath, entry.isDirectory)) {
                    relativePathsToContents.add(unixStyleRelativePath to zipInputStream.readBytes())
                }
            }
        }
        return relativePathsToContents.sortedBy { it.first }.toMap(LinkedHashMap())
    }
}
