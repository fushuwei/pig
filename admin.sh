#!/bin/bash
cd /data/projects/pig || exit 1
JAR="./pig-upms/pig-upms-biz/target/pig-upms-biz.jar"
pkill -9 -f "$JAR"
sleep 1
set -a
source ./bin/.env
set +a
nohup java -jar "$JAR" &