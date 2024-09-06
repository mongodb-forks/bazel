// Copyright 2020 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.runtime;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.Mockito.mock;

import com.google.common.collect.ImmutableList;
import com.google.common.eventbus.EventBus;
import com.google.devtools.build.lib.buildtool.BuildRequest;
import com.google.devtools.build.lib.buildtool.BuildResult;
import com.google.devtools.build.lib.buildtool.buildevent.BuildCompleteEvent;
import com.google.devtools.build.lib.buildtool.buildevent.BuildStartingEvent;
import com.google.devtools.build.lib.buildtool.buildevent.MainRepoMappingComputationStartingEvent;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.testutil.ManualClock;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.io.OutErr;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for output generated by {@link UiEventHandler}. */
@RunWith(TestParameterInjector.class)
public final class UiEventHandlerStdOutAndStdErrTest {

  private static final BuildCompleteEvent BUILD_COMPLETE_EVENT =
      new BuildCompleteEvent(new BuildResult(/*startTimeMillis=*/ 0));
  private static final String BUILD_DID_NOT_COMPLETE_MESSAGE =
      "\033[31m\033[1mERROR: \033[0mBuild did NOT complete successfully" + System.lineSeparator();

  @TestParameter private TestedOutput testedOutput;
  @TestParameter private boolean skymeldMode;

  private UiEventHandler uiEventHandler;
  private FlushCollectingOutputStream output;
  private EventKind eventKind;

  private enum TestedOutput {
    STDOUT,
    STDERR;
  }

  @Before
  public void createUiEventHandler() {
    UiOptions uiOptions = new UiOptions();
    uiOptions.eventKindFilters = ImmutableList.of();
    createUiEventHandler(uiOptions);
  }

  private void createUiEventHandler(UiOptions uiOptions) {
    output = new FlushCollectingOutputStream();

    OutErr outErr = null;
    switch (testedOutput) {
      case STDOUT:
        outErr = OutErr.create(/* out= */ output, /* err= */ mock(OutputStream.class));
        eventKind = EventKind.STDOUT;
        break;
      case STDERR:
        outErr = OutErr.create(/* out= */ mock(OutputStream.class), /* err= */ output);
        eventKind = EventKind.STDERR;
        break;
    }

    uiEventHandler =
        new UiEventHandler(
            outErr,
            uiOptions,
            new ManualClock(),
            new EventBus(),
            /* workspacePathFragment= */ null,
            /* skymeldMode= */ skymeldMode,
            /* newStatsSummary= */ false);
    uiEventHandler.mainRepoMappingComputationStarted(new MainRepoMappingComputationStartingEvent());
    uiEventHandler.buildStarted(
        BuildStartingEvent.create(
            "outputFileSystemType",
            /* usesInMemoryFileSystem= */ false,
            mock(BuildRequest.class),
            /* workspace= */ null,
            "/pwd"));
  }

  @Test
  public void buildComplete_outputsBuildFailedOnStderr() {
    uiEventHandler.buildComplete(BUILD_COMPLETE_EVENT);

    if (testedOutput == TestedOutput.STDOUT) {
      output.assertFlushed();
    } else {
      output.assertFlushed(BUILD_DID_NOT_COMPLETE_MESSAGE);
    }
  }

  @Test
  public void buildComplete_flushesBufferedMessage() {
    uiEventHandler.handle(output("hello"));
    uiEventHandler.buildComplete(BUILD_COMPLETE_EVENT);

    if (testedOutput == TestedOutput.STDOUT) {
      output.assertFlushed("hello");
    } else {
      output.assertFlushed("hello", System.lineSeparator() + BUILD_DID_NOT_COMPLETE_MESSAGE);
    }
  }

  @Test
  public void buildComplete_successfulBuild() {
    uiEventHandler.handle(output(""));
    var buildSuccessResult = new BuildResult(/*startTimeMillis=*/ 0);
    buildSuccessResult.setDetailedExitCode(DetailedExitCode.success());
    uiEventHandler.buildComplete(new BuildCompleteEvent(buildSuccessResult));

    if (testedOutput == TestedOutput.STDOUT) {
      output.assertFlushed();
    } else {
      output.assertFlushed(
          "\033[32mINFO: \033[0mBuild completed successfully, 0 total actions"
              + System.lineSeparator());
    }
  }

  @Test
  public void buildComplete_emptyBuffer_outputsBuildFailedOnStderr() {
    uiEventHandler.handle(output(""));
    uiEventHandler.buildComplete(BUILD_COMPLETE_EVENT);

    if (testedOutput == TestedOutput.STDOUT) {
      output.assertFlushed();
    } else {
      output.assertFlushed(BUILD_DID_NOT_COMPLETE_MESSAGE);
    }
  }

  @Test
  public void handleOutputEvent_buffersWithoutNewline() {
    uiEventHandler.handle(output("hello"));
    output.assertFlushed();
  }

  @Test
  public void handleOutputEvent_concatenatesInBuffer() {
    uiEventHandler.handle(output("hello "));
    uiEventHandler.handle(output("there"));
    uiEventHandler.buildComplete(BUILD_COMPLETE_EVENT);

    if (testedOutput == TestedOutput.STDOUT) {
      output.assertFlushed("hello there");
    } else {
      output.assertFlushed("hello there", System.lineSeparator() + BUILD_DID_NOT_COMPLETE_MESSAGE);
    }
  }

  @Test
  public void handleOutputEvent_flushesOnNewline() {
    uiEventHandler.handle(output("hello\n"));
    output.assertFlushed("hello\n");
  }

  @Test
  public void handleOutputEvent_flushesOnlyUntilNewline() {
    uiEventHandler.handle(output("hello\nworld"));
    output.assertFlushed("hello\n");
  }

  @Test
  public void handleOutputEvent_flushesUntilLastNewline() {
    uiEventHandler.handle(output("hello\nto\neveryone"));
    output.assertFlushed("hello\nto\n");
  }

  @Test
  public void handleOutputEvent_flushesMultiLineMessageAtOnce() {
    uiEventHandler.handle(output("hello\neveryone\n"));
    output.assertFlushed("hello\neveryone\n");
  }

  @Test
  public void handleOutputEvent_concatenatesBufferBeforeFlushingOnNewline() {
    uiEventHandler.handle(output("hello"));
    uiEventHandler.handle(output(" there!\nmore text"));

    output.assertFlushed("hello there!\n");
  }

  // This test only exercises progress bar code when testing stderr output, since we don't make
  // any assertions on stderr (where the progress bar is written) when testing stdout.
  @Test
  public void noChangeOnUnflushedWrite() {
    UiOptions uiOptions = new UiOptions();
    uiOptions.showProgress = true;
    uiOptions.useCursesEnum = UiOptions.UseCurses.YES;
    uiOptions.eventKindFilters = ImmutableList.of();
    createUiEventHandler(uiOptions);
    if (testedOutput == TestedOutput.STDERR) {
      assertThat(output.flushed).hasSize(2);
      output.flushed.clear();
    }
    // Unterminated strings are saved in memory and not pushed out at all.
    assertThat(output.flushed).isEmpty();
    assertThat(output.writtenSinceFlush).isEmpty();
  }

  @Test
  public void buildCompleteMessageDoesntOverrideError() {
    Assume.assumeTrue(testedOutput == TestedOutput.STDERR);
    UiOptions uiOptions = new UiOptions();
    uiOptions.showProgress = true;
    uiOptions.useCursesEnum = UiOptions.UseCurses.YES;
    uiOptions.eventKindFilters = ImmutableList.of();
    createUiEventHandler(uiOptions);

    uiEventHandler.buildComplete(BUILD_COMPLETE_EVENT);
    uiEventHandler.handle(Event.error("Show me this!"));
    uiEventHandler.afterCommand(new AfterCommandEvent());

    assertThat(output.flushed.size()).isEqualTo(5);
    assertThat(output.flushed.get(3)).contains("Show me this!");
    assertThat(output.flushed.get(4)).doesNotContain("\033[1A\033[K");
  }

  @Test
  public void handleOutputEvent_flushesRemainingLines() {
    Assume.assumeTrue(testedOutput == TestedOutput.STDOUT);
    uiEventHandler.handle(output("hello\nto\neveryone"));
    output.assertFlushed("hello\nto\n");
    uiEventHandler.afterCommand(new AfterCommandEvent());
    output.assertFlushed("hello\nto\n", "everyone");
  }

  private Event output(String message) {
    return Event.of(eventKind, message);
  }

  private static class FlushCollectingOutputStream extends OutputStream {
    private final List<String> flushed = new ArrayList<>();
    private String writtenSinceFlush = "";

    @Override
    public void write(int b) throws IOException {
      write(new byte[] {(byte) b});
    }

    @Override
    public void write(byte[] bytes, int offset, int len) {
      writtenSinceFlush += new String(Arrays.copyOfRange(bytes, offset, offset + len), UTF_8);
    }

    @Override
    public void flush() {
      // Ignore inconsequential extra flushes.
      if (!writtenSinceFlush.isEmpty()) {
        flushed.add(writtenSinceFlush);
      }
      writtenSinceFlush = "";
    }

    private void assertFlushed(String... messages) {
      assertThat(writtenSinceFlush).isEmpty();
      assertThat(flushed).containsExactlyElementsIn(messages);
    }
  }
}
