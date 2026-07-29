#!/system/bin/sh
# Low-overhead API-19 process sampler used by scanner benchmarks.
# Usage: profile-scan.sh PACKAGE SAMPLE_COUNT OUTPUT_PREFIX

PACKAGE_NAME="$1"
SAMPLE_COUNT="$2"
OUTPUT_PREFIX="$3"

if [ -z "$PACKAGE_NAME" ] || [ -z "$SAMPLE_COUNT" ] || [ -z "$OUTPUT_PREFIX" ]; then
    echo "usage: profile-scan.sh PACKAGE SAMPLE_COUNT OUTPUT_PREFIX" >&2
    exit 2
fi

PROCESS_OUTPUT="${OUTPUT_PREFIX}-process.csv"
THREAD_OUTPUT="${OUTPUT_PREFIX}-threads.csv"
echo "sample,epoch,pid,utime_ticks,stime_ticks,vsize_bytes,rss_pages,threads,fd_count,vmrss_kb,vmdata_kb,read_bytes,write_bytes,rchar,wchar,cpu_user,cpu_nice,cpu_system,cpu_idle,cpu_iowait" > "$PROCESS_OUTPUT"
echo "sample,epoch,pid,tid,name,utime_ticks,stime_ticks" > "$THREAD_OUTPUT"

sample=0
while [ "$sample" -lt "$SAMPLE_COUNT" ]; do
    epoch=$(date +%s)
    read cpu_key cpu_user cpu_nice cpu_system cpu_idle cpu_iowait cpu_rest < /proc/stat
    set -- $(ps | grep " $PACKAGE_NAME$")
    pid="$2"
    if [ -n "$pid" ] && [ -r "/proc/$pid/stat" ]; then
        set -- $(cat "/proc/$pid/stat")
        process_stat="${14},${15},${23},${24}"
        threads=0
        for task in /proc/$pid/task/*; do
            [ -e "$task" ] && threads=$((threads + 1))
        done
        fd_count=0
        for descriptor in /proc/$pid/fd/*; do
            [ -e "$descriptor" ] && fd_count=$((fd_count + 1))
        done
        vmrss=""; vmdata=""
        while read key value rest; do
            case "$key" in
                VmRSS:) vmrss="$value" ;;
                VmData:) vmdata="$value" ;;
            esac
        done < "/proc/$pid/status"
        read_bytes=""; write_bytes=""; rchar=""; wchar=""
        if [ -r "/proc/$pid/io" ]; then
            while read key value; do
                case "$key" in
                    read_bytes:) read_bytes="$value" ;;
                    write_bytes:) write_bytes="$value" ;;
                    rchar:) rchar="$value" ;;
                    wchar:) wchar="$value" ;;
                esac
            done < "/proc/$pid/io"
        fi
        echo "$sample,$epoch,$pid,$process_stat,$threads,$fd_count,$vmrss,$vmdata,$read_bytes,$write_bytes,$rchar,$wchar,$cpu_user,$cpu_nice,$cpu_system,$cpu_idle,$cpu_iowait" >> "$PROCESS_OUTPUT"

        for task in /proc/$pid/task/*; do
            [ -r "$task/stat" ] || continue
            tid=${task##*/}
            name=""
            while read key value rest; do
                [ "$key" = "Name:" ] && name="$value"
            done < "$task/status"
            set -- $(cat "$task/stat")
            task_stat="${14},${15}"
            echo "$sample,$epoch,$pid,$tid,$name,$task_stat" >> "$THREAD_OUTPUT"
        done
    else
        echo "$sample,$epoch,,,,,,,,,,,,,$cpu_user,$cpu_nice,$cpu_system,$cpu_idle,$cpu_iowait" >> "$PROCESS_OUTPUT"
    fi
    sample=$((sample + 1))
    sleep 1
done
