/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.watch.registry.impl;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multiset;
import com.google.common.collect.Multisets;
import net.rubygrapefruit.platform.file.FileWatcher;
import org.gradle.internal.snapshot.CompleteFileSystemLocationSnapshot;
import org.gradle.internal.watch.registry.FileWatcherUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class HierarchicalFileWatcherUpdater implements FileWatcherUpdater {
    private static final Logger LOGGER = LoggerFactory.getLogger(HierarchicalFileWatcherUpdater.class);

    private final Map<String, ImmutableList<Path>> watchedRootsForSnapshot = new HashMap<>();
    private final Multiset<Path> shouldWatchDirectories = HashMultiset.create();
    private final Set<Path> watchedRoots = new HashSet<>();
    private final Map<Path, String> projectRootDirectories = new HashMap<>();
    private final Map<Path, String> oldProjectRootDirectories = new HashMap<>();
    private final FileWatcher watcher;

    public HierarchicalFileWatcherUpdater(FileWatcher watcher) {
        this.watcher = watcher;
    }

    @Override
    public void changed(Collection<CompleteFileSystemLocationSnapshot> removedSnapshots, Collection<CompleteFileSystemLocationSnapshot> addedSnapshots) {
        removedSnapshots.forEach(snapshot -> {
            ImmutableList<Path> previouslyWatchedRootsForSnapshot = watchedRootsForSnapshot.remove(snapshot.getAbsolutePath());
            Multisets.removeOccurrences(shouldWatchDirectories, previouslyWatchedRootsForSnapshot);
        });
        addedSnapshots.forEach(snapshot -> {
            ImmutableList<Path> directoriesToWatch = WatchRootUtil.getDirectoriesToWatch(snapshot);
            shouldWatchDirectories.addAll(directoriesToWatch);
            watchedRootsForSnapshot.put(snapshot.getAbsolutePath(), directoriesToWatch);
        });
        updateWatchedDirectories();
    }

    @Override
    public void updateProjectRootDirectories(Collection<File> updatedProjectRootDirectories) {
        Set<Path> rootPaths = updatedProjectRootDirectories.stream()
            .map(File::toPath)
            .map(Path::toAbsolutePath)
            .collect(Collectors.toSet());
        Set<Path> newProjectRootDirectories = WatchRootUtil.resolveRootsToWatch(rootPaths);
        LOGGER.info("Now considering {} as root directories to watch", newProjectRootDirectories);

        projectRootDirectories.keySet().removeAll(newProjectRootDirectories);
        oldProjectRootDirectories.keySet().removeAll(newProjectRootDirectories);

        Set<Path> updatedOldProjectRootDirectories = WatchRootUtil.resolveRootsToWatch(ImmutableSet.<Path>builder()
            .addAll(projectRootDirectories.keySet())
            .addAll(oldProjectRootDirectories.keySet())
            .build());

        addProjectRootDirectoryWithPrefix(updatedOldProjectRootDirectories, oldProjectRootDirectories);
        addProjectRootDirectoryWithPrefix(newProjectRootDirectories, projectRootDirectories);

        updateWatchedDirectories();
    }

    private void addProjectRootDirectoryWithPrefix(Set<Path> projectRootDirectories, Map<Path, String> targetMap) {
        targetMap.clear();
        projectRootDirectories.forEach(
            oldProjectRootDirectory -> targetMap.put(oldProjectRootDirectory, oldProjectRootDirectory.toString() + File.separator)
        );
    }

    private void updateWatchedDirectories() {
        Set<Path> directoriesToWatch = new HashSet<>();
        shouldWatchDirectories.elementSet().forEach(shouldWatchDirectory -> {
            if (maybeWatchProjectRootDirectory(directoriesToWatch, shouldWatchDirectory, projectRootDirectories)) {
                return;
            }
            if (maybeWatchProjectRootDirectory(directoriesToWatch, shouldWatchDirectory, oldProjectRootDirectories)) {
                return;
            }
            directoriesToWatch.add(shouldWatchDirectory);
        });
        oldProjectRootDirectories.keySet().retainAll(directoriesToWatch);

        updateWatchedDirectories(WatchRootUtil.resolveRootsToWatch(directoriesToWatch));
    }

    private boolean maybeWatchProjectRootDirectory(Set<Path> directoriesToWatch, Path shouldWatchDirectory, Map<Path, String> projectRootDirectories) {
        for (Map.Entry<Path, String> entry : projectRootDirectories.entrySet()) {
            if (shouldWatchDirectory.toString().startsWith(entry.getValue())) {
                directoriesToWatch.add(entry.getKey());
                return true;
            }
        }
        return false;
    }

    private void updateWatchedDirectories(Set<Path> newWatchRoots) {
        Set<Path> watchRootsToRemove = new HashSet<>(watchedRoots);
        if (newWatchRoots.isEmpty()) {
            LOGGER.info("Not watching anything anymore");
        }
        watchRootsToRemove.removeAll(newWatchRoots);
        newWatchRoots.removeAll(watchedRoots);
        if (newWatchRoots.isEmpty() && watchRootsToRemove.isEmpty()) {
            return;
        }
        LOGGER.warn("Current watch roots {}, new watch roots {}, watch roots to remove {}", watchedRoots, newWatchRoots, watchRootsToRemove);
        if (!watchRootsToRemove.isEmpty()) {
            watcher.stopWatching(watchRootsToRemove.stream()
                .map(Path::toFile)
                .collect(Collectors.toList())
            );
            watchedRoots.removeAll(watchRootsToRemove);
        }
        if (!newWatchRoots.isEmpty()) {
            watcher.startWatching(newWatchRoots.stream()
                .map(Path::toFile)
                .collect(Collectors.toList())
            );
            watchedRoots.addAll(newWatchRoots);
        }
        LOGGER.info("Watching {} directory hierarchies to track changes", watchedRoots.size());
    }
}
