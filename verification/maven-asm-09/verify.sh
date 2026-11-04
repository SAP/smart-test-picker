#!/bin/sh
set -eu
repo="$1/.m2/repository/com/sap/oss/smart-test-picker"
classpath="$repo/smart-test-picker-common/0.3.0-SNAPSHOT/smart-test-picker-common-0.3.0-SNAPSHOT.jar:$repo/smart-test-picker-maven/0.3.0-SNAPSHOT/smart-test-picker-maven-0.3.0-SNAPSHOT.jar"
gson=$(find "$1/.m2/repository/com/google/code/gson/gson" -name '*.jar' -print | head -1)
mkdir -p "$1/.verification-classes"
javac -cp "$classpath:$gson" -d "$1/.verification-classes" /opt/asm09/VerifyEvidence.java
java -cp "$1/.verification-classes:$classpath:$gson" com.sap.oss.smarttestpicker.maven.VerifyEvidence "$@"
