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
import com.google.devtools.build.lib.shell.Subprocess;
import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;

/** Starts a worker process without changing the worker protocol or pool semantics. */
interface WorkerProcessLauncher {
  Subprocess start(
      ImmutableList<String> arguments,
      Path workingDirectory,
      Path stderrFile,
      ImmutableMap<String, String> environment)
      throws IOException;
}
