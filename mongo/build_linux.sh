#!/bin/bash

set -o errexit
set -o verbose

# Use task-local container storage. The shared rootless store on the pooled
# builders has come back corrupted from earlier runs ("stat .../overlay/...: no
# such file or directory"), so give each task a store of its own. Keep it beside
# the source dir rather than inside it, so it stays out of the bind mount below.
podman_root="$(cd .. && pwd)/podman_storage"
mkdir -p "$podman_root"

# The runroot holds unix sockets, so podman rejects paths over 50 characters --
# too short for the Evergreen task dir. Use a short unique path under /tmp and
# clean it up on exit.
podman_runroot="$(mktemp -d /tmp/pdmn.XXXXXX)"
trap 'rm -rf "$podman_runroot"' EXIT

podman="podman --root $podman_root --runroot $podman_runroot"

$podman pull docker.io/redhat/ubi8:8.10-1184
$podman run  --mount type=bind,source=$PWD,destination=/tmp/bazel/,rw=true --workdir /tmp/bazel redhat/ubi8:8.10-1184 /tmp/bazel/mongo/container_build.sh "$1" "$2" "$3"

"./$3" info
"./$3" --version
