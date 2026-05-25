#!/bin/bash
cd /data/projects/pig || exit 1
JAR="./pig-gateway/target/pig-gateway.jar"
pkill -9 -f "$JAR"
sleep 1
set -a
source ./.env
set +a
nohup java -jar "$JAR" &