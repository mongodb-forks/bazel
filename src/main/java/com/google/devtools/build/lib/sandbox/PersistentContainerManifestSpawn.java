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

import static com.google.devtools.build.lib.util.StringEncoding.internalToUnicode;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.exec.TreeDeleter;
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxInputs;
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxOutputs;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.gson.stream.JsonWriter;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.Map;
import java.util.Set;

/** Passes an action's inputs and mapped output paths to the external container runner. */
final class PersistentContainerManifestSpawn extends AbstractContainerizingSandboxedSpawn {
  static final String MANIFEST = ".bazel-persistent-container-inputs.json";
  private final Map<PathFragment, Path> declaredFiles;
  private final SandboxOutputs declaredOutputs;
  private final String actionMnemonic;

  PersistentContainerManifestSpawn(
      Path sandboxPath,
      Path sandboxExecRoot,
      ImmutableList<String> arguments,
      ImmutableMap<String, String> environment,
      SandboxInputs declared,
      SandboxOutputs outputs,
      Set<Path> writableDirs,
      TreeDeleter treeDeleter,
      String mnemonic) {
    super(
        sandboxPath,
        sandboxExecRoot,
        arguments,
        environment,
        SandboxInputs.getEmptyInputs(),
        outputs,
        writableDirs,
        treeDeleter,
        /* sandboxDebugPath= */ null,
        /* statisticsPath= */ null,
        mnemonic);
    declaredFiles = declared.getFiles();
    declaredOutputs = outputs;
    actionMnemonic = mnemonic;
  }

  static boolean supportsLayout(SandboxInputs inputs, SandboxOutputs outputs) {
    // Unresolved links and sibling repositories still need the regular sandbox.
    return inputs.getSymlinks().isEmpty()
        && inputs.getFiles().keySet().stream().allMatch(PersistentContainerManifestSpawn::relative)
        && outputs.files().values().stream().allMatch(PersistentContainerManifestSpawn::relative)
        && outputs.dirs().values().stream().allMatch(PersistentContainerManifestSpawn::relative);
  }

  private static boolean relative(PathFragment path) {
    return !path.isEmpty() && !path.isAbsolute() && !path.containsUplevelReferences();
  }

  private static void writePaths(JsonWriter writer, String name, Iterable<PathFragment> paths)
      throws IOException {
    writer.name(name).beginArray();
    for (PathFragment path : paths) {
      writer.value(internalToUnicode(path.getPathString()));
    }
    writer.endArray();
  }

  @Override
  public void createFileSystem() throws IOException, InterruptedException {
    // Prepare only the output and writable directories on the host. Store the manifest beside
    // execroot/ so it is not itself part of the action's inputs.
    super.createFileSystem();
    try (var writer =
        new JsonWriter(
            new BufferedWriter(
                new OutputStreamWriter(sandboxPath.getChild(MANIFEST).getOutputStream(), UTF_8)))) {
      writer.beginObject().name("version").value(2).name("mnemonic").value(actionMnemonic);
      writer.name("files").beginObject();
      for (var entry : declaredFiles.entrySet()) {
        if (Thread.interrupted()) {
          throw new InterruptedException();
        }
        writer.name(internalToUnicode(entry.getKey().getPathString()));
        if (entry.getValue() == null) {
          writer.nullValue();
        } else {
          writer.value(internalToUnicode(entry.getValue().getPathString()));
        }
      }
      writer.endObject();
      writePaths(writer, "output_files", declaredOutputs.files().values());
      writePaths(writer, "output_directories", declaredOutputs.dirs().values());
      writer.endObject();
    }
  }

  @Override
  protected void copyFile(Path source, Path target) {
    throw new IllegalStateException("Manifest spawns do not create host input links");
  }
}
