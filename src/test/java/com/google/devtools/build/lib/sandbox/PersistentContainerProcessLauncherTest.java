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
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link PersistentContainerProcessLauncher}. */
@RunWith(JUnit4.class)
public final class PersistentContainerProcessLauncherTest {

  @Test
  public void wrapsOriginalActionArguments() {
    SandboxOptions options = configuredOptions();

    assertThat(PersistentContainerProcessLauncher.isConfigured(options)).isTrue();
    assertThat(
            PersistentContainerProcessLauncher.fromOptions(options)
                .wrap(ImmutableList.of("tool", "--flag", "argument")))
        .containsExactly(
            "/usr/bin/python3", "/opt/container/runner.py", "/opt/container/config.json", "tool",
            "--flag", "argument")
        .inOrder();
  }

  @Test
  public void rejectsIncompleteConfiguration() {
    SandboxOptions options = configuredOptions();
    options.persistentContainerRunner = "";

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> PersistentContainerProcessLauncher.fromOptions(options));

    assertThat(error).hasMessageThat().contains("persistent_container_runner");
  }

  private static SandboxOptions configuredOptions() {
    SandboxOptions options = new SandboxOptions();
    options.persistentContainerPython = "/usr/bin/python3";
    options.persistentContainerRunner = "/opt/container/runner.py";
    options.persistentContainerConfig = "/opt/container/config.json";
    return options;
  }
}
