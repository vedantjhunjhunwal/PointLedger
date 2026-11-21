@echo off
rem Windows: compiles the service, tests and benchmarks with plain javac (JDK 21+).
if exist out rmdir /s /q out
dir /s /b src\main\*.java > out.sources
javac --release 21 -d out\main @out.sources || exit /b 1
javac --release 21 -cp out\main -d out\test src\test\java\dev\pointledger\TestMain.java || exit /b 1
javac --release 21 -cp out\main -d out\bench src\bench\java\dev\pointledger\Bench.java || exit /b 1
del out.sources
echo built into out\
rem Tests:  java -cp out\main;out\test dev.pointledger.TestMain
rem Bench:  java -Xmx3g -cp out\main;out\bench dev.pointledger.Bench
rem Run:    java -cp out\main dev.pointledger.App
