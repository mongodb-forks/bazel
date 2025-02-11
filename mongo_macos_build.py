import docker
import os
import traceback
import sys
client = docker.from_env()
try:
    container = client.containers.get("bazel_build")
except:
    pass
else:
    try:
        container.stop()
    except:
        pass
    try:
        container.remove()
    except:
        pass


container = client.containers.run(
        image = "ubuntu:22.04", 
        name="bazel_build", 
        volumes={
            os.getcwd(): {
                'bind': "/tmp/bazel",
                'mode': 'rw',
            }
        },
        network='host',
        tty=True,
        detach=True,
        entrypoint="/bin/bash",

    )
try:
    
    cmds = [
        'apt-get update',
        'apt-get -y install curl ninja-build build-essential openjdk-21-jdk python3 zip unzip g++ cmake automake clang-15 bison flex libfuse-dev libudev-dev pkg-config libcairo2-dev libgl1-mesa-dev curl libglu1-mesa-dev libtiff5-dev libfreetype6-dev git git-lfs libelf-dev libxml2-dev libegl1-mesa-dev libfontconfig1-dev libbsd-dev libxrandr-dev libxcursor-dev libgif-dev libavutil-dev libpulse-dev libavformat-dev libavcodec-dev libswresample-dev libdbus-1-dev libxkbfile-dev libssl-dev libstdc++-12-dev',
        'GIT_CLONE_PROTECTION_ACTIVE=false git clone --recursive https://github.com/darlinghq/darling.git /tmp/darling',
        ('git lfs install', "/tmp/darling"),
        ('git pull', "/tmp/darling"),
        ('git submodule update --init --recursive', "/tmp/darling"),
        ('mkdir build', "/tmp/darling"),
        ('cmake -G Ninja ..', '/tmp/darling/build'),
        ('ninja install', '/tmp/darling/build'),
        'darling shell uname',
        # 'curl -L https://github.com/bazelbuild/bazel/releases/download/7.5.0/bazel-7.5.0-linux-arm64 -o bazel_download',
        # 'chmod +x ./bazel_download',
        # './bazel_download build //src:bazel-dev'
    ]
    
    for cmd in cmds:
        workdir = None
        if isinstance(cmd, tuple):
            workdir = cmd[1]
            cmd = cmd[0]
            print(workdir)
        
        print(f"cmd:\n{cmd}")
        print("output:")
        result = container.exec_run(["sh", "-c", cmd], workdir="/tmp/bazel" if workdir is None else workdir, stream=True, demux=True)
        for stream in result:
            if stream:
                for chunk in stream:
                    if chunk[0]:
                        sys.stdout.write(chunk[0].decode())
                    if chunk[1]:
                        sys.stdout.write(chunk[1].decode())
            
except:
    traceback.print_exc()
try:
    container.stop()
except:
    pass
try:
    container.remove()
except:
    pass
    