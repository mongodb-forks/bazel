#!/bin/bash

set -o errexit
set -o verbose

if [[ "$4" == "ubi7" ]]; then
    docker pull registry.access.redhat.com/ubi7/ubi:7.9-1445
    docker run  --mount type=bind,source=$PWD,destination=/tmp/bazel/ --workdir /tmp/bazel registry.access.redhat.com/ubi7/ubi:7.9-1445 /tmp/bazel/mongo/container_build_rhel7.sh "$1" "$2" "$3"
else
    podman pull docker.io/redhat/ubi8:8.10-1184
    podman run  --mount type=bind,source=$PWD,destination=/tmp/bazel/,rw=true --workdir /tmp/bazel redhat/ubi8:8.10-1184 /tmp/bazel/mongo/container_build.sh "$1" "$2" "$3"
fi

"./$3" info
"./$3" --version
