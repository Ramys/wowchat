#!/bin/bash

# Performance-optimized JVM parameters for WoWChat
JAVA_OPTS="-server \
  -Xms512m \
  -Xmx1g \
  -XX:+UseG1GC \
  -XX:G1HeapRegionSize=16m \
  -XX:MaxGCPauseMillis=200 \
  -XX:+UseStringDeduplication \
  -XX:+OptimizeStringConcat \
  -XX:+UseCompressedOops \
  -XX:+UseCompressedClassPointers \
  -XX:+TieredCompilation \
  -XX:TieredStopAtLevel=4 \
  -XX:+UnlockExperimentalVMOptions \
  -XX:+UseCGroupMemoryLimitForHeap \
  -Djava.awt.headless=true \
  -Dfile.encoding=UTF-8 \
  -Djava.net.preferIPv4Stack=true \
  -Dnetworkaddress.cache.ttl=60 \
  -Dio.netty.tryReflectionSetAccessible=true \
  -Dio.netty.eventLoopThreads=0"

# Check if java is available
if ! command -v java &> /dev/null; then
    echo "Java is not installed or not in PATH"
    exit 1
fi

# Check Java version
JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1-2)
if [[ $(echo "$JAVA_VERSION < 11" | bc -l) -eq 1 ]]; then
    echo "Warning: Java 11 or higher is recommended for optimal performance"
fi

echo "Starting WoWChat with optimized JVM settings..."
java $JAVA_OPTS -jar wowchat.jar "$@"
