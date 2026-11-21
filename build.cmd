@echo off
rem Windows: compiles the service, tests and benchmarks with javac (JDK 21+).
rem Guice jars are fetched into lib\ once with Maven (see pom.xml).
if not exist lib\*.jar call mvn -q -B dependency:copy-dependencies -DoutputDirectory=lib -DincludeScope=runtime || exit /b 1
if exist out rmdir /s /q out
dir /s /b src\main\*.java > out.sources
javac --release 21 -cp "lib\*" -d out\main @out.sources || exit /b 1
javac --release 21 -cp "out\main;lib\*" -d out\test src\test\java\dev\pointledger\TestMain.java || exit /b 1
javac --release 21 -cp "out\main;lib\*" -d out\bench src\bench\java\dev\pointledger\Bench.java || exit /b 1
del out.sources
echo built into out\
rem Tests:  java -cp "out\main;out\test;lib\*" dev.pointledger.TestMain
rem Bench:  java -Xmx3g -cp "out\main;out\bench;lib\*" dev.pointledger.Bench
rem Run:    java -cp "out\main;lib\*" dev.pointledger.App
