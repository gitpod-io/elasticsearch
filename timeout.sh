#!/bin/bash

# Background task function
background_task() {
    local timeout=$2

    echo "timeout.sh: sleeping..."
    sleep $timeout

    echo "timeout.sh: stopping all tasks"
    gp tasks stop --all
}

timeout=320

# Start the background task
background_task "$binary_name" "$timeout" &