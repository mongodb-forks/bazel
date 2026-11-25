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

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxInputs;
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxOutputs;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.util.TreeMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class PersistentContainerManifestSpawnTest {
  private final InMemoryFileSystem fs = new InMemoryFileSystem(DigestHashFunction.SHA256);

  @Test
  public void leavesInputsInPlaceAndCollectsOutputs() throws Exception {
    var path = fs.getPath("/sandbox/action");
    var execroot = path.getRelative("execroot/main");
    execroot.createDirectoryAndParents();
    var source = fs.getPath("/sources/header.h");
    source.getParentDirectory().createDirectoryAndParents();
    FileSystemUtils.writeContent(source, UTF_8, "header");
    var input = PathFragment.create("external/sdk/header.h");
    var originalOutput = PathFragment.create("bazel-out/original/file.obj");
    var output = PathFragment.create("bazel-out/results/file.obj");
    var outputTree = PathFragment.create("bazel-out/results/tree");
    var declaredFiles = new TreeMap<PathFragment, Path>();
    declaredFiles.put(input, source);
    declaredFiles.put(PathFragment.create("runfiles/__init__.py"), null);
    var inputs =
        new SandboxInputs(declaredFiles, ImmutableMap.of(), ImmutableMap.of());
    var spawn =
        new PersistentContainerManifestSpawn(
            path,
            execroot,
            ImmutableList.of("runner"),
            ImmutableMap.of(),
            inputs,
            SandboxOutputs.create(
                ImmutableMap.of(originalOutput, output), ImmutableMap.of(outputTree, outputTree)),
            ImmutableSet.of(),
            new SynchronousTreeDeleter(),
            "CppCompile");

    spawn.createFileSystem();

    assertThat(execroot.getChild("external").exists()).isFalse();
    assertThat(
            FileSystemUtils.readContent(
                path.getChild(PersistentContainerManifestSpawn.MANIFEST), UTF_8))
        .isEqualTo(
            "{\"version\":2,\"mnemonic\":\"CppCompile\",\"files\":{"
                + "\"external/sdk/header.h\":\"/sources/header.h\",\"runfiles/__init__.py\":null},"
                + "\"output_files\":[\"bazel-out/results/file.obj\"],"
                + "\"output_directories\":[\"bazel-out/results/tree\"]}");
    assertThat(execroot.getChild("runfiles").exists()).isFalse();
    var built = execroot.getRelative(output);
    assertThat(built.getParentDirectory().isDirectory()).isTrue();
    FileSystemUtils.writeContent(built, UTF_8, "object");
    FileSystemUtils.writeContent(execroot.getRelative(outputTree).getChild("file"), UTF_8, "tree");
    var mainExecroot = fs.getPath("/main");
    mainExecroot.getRelative(originalOutput).getParentDirectory().createDirectoryAndParents();
    mainExecroot.getRelative(outputTree).getParentDirectory().createDirectoryAndParents();
    spawn.copyOutputs(mainExecroot);
    assertThat(FileSystemUtils.readContent(mainExecroot.getRelative(originalOutput), UTF_8))
        .isEqualTo("object");
    assertThat(
            FileSystemUtils.readContent(mainExecroot.getRelative(outputTree).getChild("file"), UTF_8))
        .isEqualTo("tree");
  }

  @Test
  public void layoutsThatNeedLinksKeepTheRegularSandbox() {
    var source = fs.getPath("/sources/header.h");
    var input = PathFragment.create("header.h");
    var sibling =
        new SandboxInputs(
            ImmutableMap.of(PathFragment.create("../repo/header.h"), source),
            ImmutableMap.of(),
            ImmutableMap.of());
    var symlink =
        new SandboxInputs(
            ImmutableMap.of(),
            ImmutableMap.of(),
            ImmutableMap.of(input, PathFragment.create("other.h")));
    var outputs = SandboxOutputs.getEmptyInstance();

    assertThat(PersistentContainerManifestSpawn.supportsLayout(sibling, outputs)).isFalse();
    assertThat(PersistentContainerManifestSpawn.supportsLayout(symlink, outputs)).isFalse();
    assertThat(
            PersistentContainerManifestSpawn.supportsLayout(
                new SandboxInputs(ImmutableMap.of(input, source), ImmutableMap.of(), ImmutableMap.of()),
                outputs))
        .isTrue();
  }
}
