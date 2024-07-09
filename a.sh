#!/bin/bash

trap "exit" INT

function execute_once() {
    timestamp=$(date +%Y%m%d%H%M%S)
    mkdir -p $timestamp

    echo $timestamp

    jps -mv > $timestamp/jps.txt
    for pid in $(awk '{print $1}' $timestamp/jps.txt); do
        jstack -l $pid > $timestamp/dump_$pid.txt
    done
}

if [ "$1" == "once" ]; then
    execute_once
else
    while true; do
        execute_once
        sleep 0.2
    done
fi