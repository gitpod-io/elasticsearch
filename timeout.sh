#!/bin/bash

# Function to find parent by name and kill child processes
find_and_kill_processes() {
    local binary_name=$1

    # Get the list of PIDs for the given binary name
    pids=$(pgrep -f "$binary_name")

    for pid in $pids; do
        # Kill the process and all its children
        echo "Killing process $pid and its children"
        pkill -TERM -P $pid
        #kill -TERM $pid
    done
}

# Background task function
background_task() {
    local binary_name=$1
    local timeout=$2

    # Wait for the specified timeout
    sleep $timeout

    # Find and kill the processes
    find_and_kill_processes "$binary_name"
}

# Main script execution
binary_name="remote-dev-server.sh"  # Replace with your binary name
timeout=60  # Replace with your desired timeout in seconds

# Start the background task
background_task "$binary_name" "$timeout" &
