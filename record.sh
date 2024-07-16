#!/bin/bash

# Delay of 5 minutes (300 seconds)
sleep 1

# Function to start JFR for each JVM process
start_jfr_sessions() {
    jps | while read -r line; do
        pid=$(echo $line | awk '{print $1}')
        if [ -n "$pid" ]; then
            jfr_dir="/workspace/jvm_debug/jfr-$pid"
            mkdir -p $jfr_dir
            timestamp=$(date +"%Y%m%d_%H%M%S")
            jcmd $pid JFR.start duration=60s filename="$jfr_dir/recording_$timestamp.jfr"
        fi
    done
}

# Start JFR sessions
start_jfr_sessions
