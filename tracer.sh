#!/bin/bash


# Function to get JVM stack traces
get_jvm_stack_traces() {
    local trace_dir=$1
    jps | while read -r line; do
        pid=$(echo $line | awk '{print $1}')
        if [[ $pid =~ ^[0-9]+$ ]]; then
            jstack -l $pid > "$trace_dir/jstack_$pid.txt"
        fi
    done
}

get_psforest() {
    local trace_dir=$1
    ps -e -a --forest -o pid,ppid,user,args > "$trace_dir/psforest.txt"
}

# Main loop to run the function every 5 seconds
while true; do
    timestamp=$(date +"%Y%m%d_%H%M%S")
    trace_dir="/workspace/jvm_debug/trace-$timestamp"
    mkdir -p "$trace_dir"
    get_psforest $trace_dir
    get_jvm_stack_traces $trace_dir
    sleep 20
done