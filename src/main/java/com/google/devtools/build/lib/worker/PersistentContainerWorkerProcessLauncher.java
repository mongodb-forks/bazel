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

package com.google.devtools.build.lib.worker;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.sandbox.PersistentContainerProcessLauncher;
import com.google.devtools.build.lib.sandbox.SandboxOptions;
import com.google.devtools.build.lib.shell.Subprocess;
import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;

/** Starts a worker's long-lived protocol process inside the configured persistent container. */
final class PersistentContainerWorkerProcessLauncher implements WorkerProcessLauncher {
  private final PersistentContainerProcessLauncher launcher;

  private PersistentContainerWorkerProcessLauncher(PersistentContainerProcessLauncher launcher) {
    this.launcher = launcher;
  }

  static PersistentContainerWorkerProcessLauncher fromOptions(SandboxOptions options) {
    return new PersistentContainerWorkerProcessLauncher(
        PersistentContainerProcessLauncher.fromOptions(options));
  }

  @Override
  public Subprocess start(
      ImmutableList<String> arguments,
      Path workingDirectory,
      Path stderrFile,
      ImmutableMap<String, String> environment,
      ImmutableMap<String, String> clientEnv)
      throws IOException {
    return launcher.start(arguments, workingDirectory, stderrFile, environment, clientEnv);
  }

  @Override
  public boolean equals(Object obj) {
    return obj instanceof PersistentContainerWorkerProcessLauncher
        && launcher.equals(((PersistentContainerWorkerProcessLauncher) obj).launcher);
  }

  @Override
  public int hashCode() {
    return launcher.hashCode();
  }
}
