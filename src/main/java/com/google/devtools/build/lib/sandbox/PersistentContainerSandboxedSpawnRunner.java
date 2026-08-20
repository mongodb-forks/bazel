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

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.exec.TreeDeleter;
import com.google.devtools.build.lib.exec.local.LocalEnvProvider;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxInputs;
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxOutputs;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;

/**
 * Sandboxes an action on the host and asks a persistent-container runner to execute it there.
 *
 * <p>The runner is deliberately external to Bazel. It owns the container lifecycle,
 * runtime-specific setup, and signal forwarding while this runner owns the action's declared-input
 * sandbox and output collection. This keeps every {@code Spawn}, including ones produced by
 * external rules, on the same execution boundary without rewriting the action executable in
 * Starlark.
 */
final class PersistentContainerSandboxedSpawnRunner extends AbstractSandboxSpawnRunner {
  private final Path execRoot;
  private final Path sandboxBase;
  private final LocalEnvProvider localEnvProvider;
  private final TreeDeleter treeDeleter;
  private final PersistentContainerProcessLauncher launcher;

  PersistentContainerSandboxedSpawnRunner(
      CommandEnvironment cmdEnv,
      Path sandboxBase,
      TreeDeleter treeDeleter,
      PersistentContainerProcessLauncher launcher) {
    super(cmdEnv);
    this.execRoot = cmdEnv.getExecRoot();
    this.sandboxBase = sandboxBase;
    this.localEnvProvider = LocalEnvProvider.forCurrentOs(cmdEnv.getClientEnv());
    this.treeDeleter = treeDeleter;
    this.launcher = launcher;
  }

  @Override
  public boolean canExec(Spawn spawn) {
    // Some Bazel-internal actions are represented as empty spawns. They do not have a tool for
    // the runner to execute and must fall through to the next selected strategy.
    return !spawn.getArguments().isEmpty() && super.canExec(spawn);
  }

  @Override
  protected SandboxedSpawn prepareSpawn(Spawn spawn, SpawnExecutionContext context)
      throws IOException, InterruptedException {
    Path sandboxPath =
        sandboxBase.getRelative(getName()).getRelative(Integer.toString(context.getId()));
    sandboxPath.createDirectoryAndParents();

    // Like every other sandbox implementation, retain the workspace-name suffix so relative
    // paths and sibling external repositories have the normal Bazel layout.
    Path sandboxExecRoot = sandboxPath.getRelative("execroot").getRelative(execRoot.getBaseName());
    sandboxExecRoot.createDirectoryAndParents();

    ImmutableMap<String, String> environment =
        localEnvProvider.rewriteLocalEnv(spawn.getEnvironment(), binTools, "/tmp");
    SandboxInputs inputs =
        SandboxHelpers.processInputFiles(
            context.getInputMapping(PathFragment.EMPTY_FRAGMENT, /* willAccessRepeatedly= */ true),
            execRoot);
    SandboxOutputs outputs = SandboxHelpers.getOutputs(spawn);

    return new SymlinkedSandboxedSpawn(
        sandboxPath,
        sandboxExecRoot,
        launcher.wrap(spawn.getArguments()),
        environment,
        inputs,
        outputs,
        getWritableDirs(sandboxExecRoot, environment),
        treeDeleter,
        /* sandboxDebugPath= */ null,
        /* statisticsPath= */ null,
        /* interactiveDebugArguments= */ null,
        spawn.getMnemonic(),
        spawn.getTargetLabel());
  }

  @Override
  public String getName() {
    return "persistent-container";
  }
}
