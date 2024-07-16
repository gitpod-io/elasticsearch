#!/bin/bash


# Function to get JVM stack traces
get_jvm_stack_traces() {
    jps | while read -r line; do
        pid=$(echo $line | awk '{print $1}')
        if [ -n "$pid" ]; then
            trace_dir="traces-$pid"
            mkdir -p $trace_dir
            timestamp=$(date +"%Y%m%d_%H%M%S")
            jstack -l $pid > "$trace_dir/stacktrace_$timestamp.txt"
        fi
    done
}

# Main loop to run the function every 5 seconds
while true; do
    get_jvm_stack_traces
    sleep 30
done
