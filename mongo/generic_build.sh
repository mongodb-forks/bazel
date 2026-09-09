#!/bin/bash

set -o errexit
set -o verbose

curl -fL $1 -o bootstrap_bazel$4

# Fail loudly if the download did not produce a binary (e.g. S3 returned an XML
# error document instead of the bootstrap binary).
if [[ "$(head -c 1 "bootstrap_bazel$4")" == "<" ]]; then
  echo "Downloaded bootstrap bazel from $1 is not a binary:" >&2
  head -c 512 "bootstrap_bazel$4" >&2
  exit 1
fi
chmod +x "./bootstrap_bazel$4"
echo "//src:bazel$4" > target.file
"./bootstrap_bazel$4" --output_base=$PWD/bazel_output_base build --compilation_mode=opt --subcommands --verbose_failures --stamp --embed_label=$2 --target_pattern_file="target.file"
cp "bazel-bin/src/bazel$4" "$3"
"./$3" info
"./$3" --version
shasum -b -a 256 "./$3" > "./${3}.sha256"
