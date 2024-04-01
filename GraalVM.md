
# Idea: GraalVM native image

Speed up performance of the server
* Can handle either more users with same resources, or
* Can use a smaller instance class for same number of users

Steps:
1. build a native image of that server jar version. Would need one for Arm vs x86.
    1. For x64 based, wget https://github.com/graalvm/graalvm-ce-builds/releases/download/vm-21.0.0/graalvm-ce-java11-linux-amd64-21.0.0.tar.gz
    2. For Arm based, wget https://github.com/graalvm/graalvm-ce-builds/releases/download/vm-21.0.0/graalvm-ce-java11-linux-aarch64-21.0.0.tar.gz
    2. tar -xzf *.tar.gz
    3. add new directory / bin to path
    4. gu install native-image
    5. sudo yum install gcc -- may require an update beforehand `sudo apt update` (on ubuntu)
        - may also need `sudo apt-get install build-essential libz-dev zlib1g-dev`


## GraalVM after install

1. Profile the app, getting info for running later: `java -agentlib:native-image-agent=config-output-dir=native-image -jar server.jar` -- saves the stuff to the output; note that we need to ^C the server after it loads up

2. Remove all grallvm types: `vim native-image/reflect-config.json` 

3. Do this big step
   
```
native-image --no-fallback --allow-incomplete-classpath -H:ReflectionConfigurationFiles=native-image/reflect-config.json -H:JNIConfigurationFiles=native-image/jni-config.json -H:DynamicProxyConfigurationFiles=native-image/proxy-config.json -H:ResourceConfigurationFiles=native-image/resource-config.json -H:IncludeResourceBundles=joptsimple.ExceptionMessages --report-unsupported-elements-at-runtime --features=org.graalvm.home.HomeFinderFeature --enable-https -jar server.jar
```


[18:32:01] [main/WARN]: Failed to load datapacks, can't proceed with server load. You can either fix your datapacks or reset to vanilla with --safeMode
   - Can't seem to find a way around this

This still doesn't work, but can we get by the AOT compilation instead?

[19:44:52] [Worker-Main-10/ERROR]: Unsupported scheme resource:data/.mcassetsroot trying to list vanilla resources (NYI?)
[19:44:52] [Worker-Main-11/ERROR]: Unsupported scheme resource:data/.mcassetsroot trying to list vanilla resources (NYI?)


## GraalVM Data - based on fallback image

1. With GraalVM - test 1: load time is 16,743 ms, maxes out CPU on load, 15.4 - 16.0% Memory, steady state ~3-5% CPU usage - debug profiler in game works fine, 20 TPS
2. With Java11 - test 1: load time is 17,972 ms, maxes out CPU on load, 16.0% Memory, steady state with ~3-5% CPU usage - debug profile in game works fine, 20 TPS

3. GraalVM load times: 17.060s, 16.871s, 5.238s (what?)
4. Java 11 load times: 17.372s, 16.772s

Java 11 - player joins and then leaves, no drop afterwards, bumps up to 21% memory
GraalVM - player joins and then leaves, no drop afterwards, bumps up to 19.9% memory - later rises just the same

Maybe we should performance test how much the server can run and the ticks per second for each setup?

## JAOTC? - built in with the JVM

1. Ahead of time compilation - creates a shared library
2. Use that shared library + jar to run with the AOT compiled binary
3. Doesn't get rid of java interpreter, but we can see performance impact