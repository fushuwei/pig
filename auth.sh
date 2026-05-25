#!/bin/bash
cd /data/projects/pig || exit 1
JAR="./pig-auth/target/pig-auth.jar"
pkill -9 -f "$JAR"
sleep 1
set -a
source ./.env
set +a
nohup java -jar "$JAR" &