#!/bin/bash
set -euo pipefail
root=$(git rev-parse --show-toplevel)
task="$root/verification/maven-asm-15"
work=${ASM15_WORK:?Set disposable analysis work directory}
gson=${ASM15_GSON:?Set Gson 2.10.1 JAR}
mkdir -p "$work/classes"
javac -cp "$gson" -d "$work/classes" "$task/Power.java" "$task/Differential.java"
java -cp "$work/classes:$gson" Power "$root/verification/maven-asm-14/raw" \
  "$root/verification/maven-asm-10/raw/divergence.json" "$task/raw/asm14-power.json" \
  name,random1,random2 false | tee "$task/asm14-power.log"
java -cp "$work/classes:$gson" Differential "$task/raw" | tee "$task/differential.log"
java -cp "$work/classes:$gson" Power "$task/raw" \
  "$root/verification/maven-asm-10/raw/divergence.json" "$task/raw/class-order-power.json" \
  alpha,random1,random2 true | tee "$task/class-order-power.log"
java -cp "$work/classes:$gson" Power --combine "$task/raw/asm14-power.json" \
  "$task/raw/class-order-power.json" "$root/verification/maven-asm-10/raw/divergence.json" \
  "$task/raw/combined-power.json" | tee "$task/combined-power.log"
