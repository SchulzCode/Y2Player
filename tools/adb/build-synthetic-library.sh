#!/system/bin/sh
# Builds a deterministic scanner corpus from one validated metadata-only fixture.
# Usage: build-synthetic-library.sh ROOT TOTAL_FILE_COUNT

ROOT="$1"
TOTAL="$2"
SEED="$ROOT/seed.mp3"

if [ -z "$ROOT" ] || [ -z "$TOTAL" ] || [ ! -f "$SEED" ]; then
    echo "usage: build-synthetic-library.sh ROOT TOTAL_FILE_COUNT (ROOT/seed.mp3 must exist)" >&2
    exit 2
fi

# seed.mp3 is file 1. Ten tracks per album and ten albums per artist produce
# realistic depth without making directory enumeration the whole benchmark.
i=1
while [ "$i" -lt "$TOTAL" ]; do
    zero_based=$((i - 1))
    artist=$((zero_based / 100))
    album=$(((zero_based / 10) % 10))
    track=$((zero_based % 10 + 1))
    directory="$ROOT/Artist_${artist}/Album_${album}_Synthetic_Scanner_Benchmark"
    if [ "$track" -eq 1 ]; then
        mkdir -p "$directory" || exit 3
    fi
    case $((i % 4)) in
        0) name="${track}_short.mp3" ;;
        1) name="${track}_medium_length_track_name_${i}.mp3" ;;
        2) name="${track}_a_deliberately_long_track_filename_for_path_cost_${i}.mp3" ;;
        *) name="${track}_disc_${album}_track_${i}.mp3" ;;
    esac
    cp "$SEED" "$directory/$name" || exit 4
    i=$((i + 1))
    if [ $((i % 500)) -eq 0 ]; then
        echo "created=$i"
    fi
done
echo "created=$i"
