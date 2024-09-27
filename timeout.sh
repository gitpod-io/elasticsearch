#!/bin/bash

# Background task function
background_task() {
    local timeout=$2

    echo "timeout.sh: sleeping..."
    sleep $timeout

    echo "timeout.sh: stopping all tasks"
    gp tasks stop --all
}

# 2700 secods = 45 min
timeout=2700

# Start the background task
background_task "$binary_name" "$timeout" &