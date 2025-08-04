@echo off

REM Performance-optimized JVM parameters for WoWChat
set JAVA_OPTS=-server ^
  -Xms512m ^
  -Xmx1g ^
  -XX:+UseG1GC ^
  -XX:G1HeapRegionSize=16m ^
  -XX:MaxGCPauseMillis=200 ^
  -XX:+UseStringDeduplication ^
  -XX:+OptimizeStringConcat ^
  -XX:+UseCompressedOops ^
  -XX:+UseCompressedClassPointers ^
  -XX:+TieredCompilation ^
  -XX:TieredStopAtLevel=4 ^
  -Djava.awt.headless=true ^
  -Dfile.encoding=UTF-8 ^
  -Djava.net.preferIPv4Stack=true ^
  -Dnetworkaddress.cache.ttl=60 ^
  -Dio.netty.tryReflectionSetAccessible=true ^
  -Dio.netty.eventLoopThreads=0

echo Starting WoWChat with optimized JVM settings...
java %JAVA_OPTS% -jar wowchat.jar %*
pause
