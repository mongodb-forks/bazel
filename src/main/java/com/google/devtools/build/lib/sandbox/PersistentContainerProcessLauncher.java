// Copyright 2026 MongoDB, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.sandbox;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.shell.Subprocess;
import com.google.devtools.build.lib.shell.SubprocessBuilder;
import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/** Builds host-side commands that enter a caller-managed persistent action container. */
public final class PersistentContainerProcessLauncher {
  private final String python;
  private final String runner;
  private final String config;

  private PersistentContainerProcessLauncher(String python, String runner, String config) {
    this.python = python;
    this.runner = runner;
    this.config = config;
  }

  /** Returns whether all options required to launch an action in the container are present. */
  public static boolean isConfigured(SandboxOptions options) {
    return !options.persistentContainerPython.isEmpty()
        && !options.persistentContainerRunner.isEmpty()
        && !options.persistentContainerConfig.isEmpty();
  }

  /** Creates a launcher from the persistent-container options. */
  public static PersistentContainerProcessLauncher fromOptions(SandboxOptions options) {
    if (!isConfigured(options)) {
      throw new IllegalArgumentException(
          "persistent-container sandbox requires --experimental_persistent_container_python, "
              + "--experimental_persistent_container_runner, and "
              + "--experimental_persistent_container_config");
    }
    return new PersistentContainerProcessLauncher(
        options.persistentContainerPython,
        options.persistentContainerRunner,
        options.persistentContainerConfig);
  }

  /** Prefixes an action's original argv with the configured host-side container runner. */
  public ImmutableList<String> wrap(Iterable<String> actionArguments) {
    return ImmutableList.<String>builder()
        .add(python)
        .add(runner)
        .add(config)
        .addAll(actionArguments)
        .build();
  }

  /** Starts a persistent worker through the same runner used for ordinary spawns. */
  public Subprocess start(
      ImmutableList<String> actionArguments,
      Path workingDirectory,
      Path stderrFile,
      Map<String, String> environment)
      throws IOException {
    SubprocessBuilder processBuilder = new SubprocessBuilder();
    processBuilder.setArgv(wrap(actionArguments));
    processBuilder.setWorkingDirectory(workingDirectory.getPathFile());
    processBuilder.setStderr(stderrFile.getPathFile());
    processBuilder.setEnv(ImmutableMap.copyOf(environment));
    return processBuilder.start();
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof PersistentContainerProcessLauncher)) {
      return false;
    }
    PersistentContainerProcessLauncher other = (PersistentContainerProcessLauncher) obj;
    return python.equals(other.python)
        && runner.equals(other.runner)
        && config.equals(other.config);
  }

  @Override
  public int hashCode() {
    return Objects.hash(python, runner, config);
  }
}
