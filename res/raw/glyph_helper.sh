#!/system/bin/sh
# Glyph root helper.
#
# This helper owns exactly one thing: the volume-key hook. Normal frame writes
# and Off go through the app's live root shell, so a dead pipe can never swallow
# a stop or leave a frame latched on the LEDs.
#
# The app writes these root-side files:
#   /data/local/tmp/glyph-volume.conf  Cval Aval Bval Ccount Acount Bcount max
#   /data/local/tmp/glyph-base         36 driver values (0..255) to restore
#
# The hook watches every kernel input device, including the physical volume
# rocker, and paints a bottom-up bar directly from the key event.

HELPER_VERSION=11
APP=dev.brl.glyph
LED=/sys/class/leds/aw20036_led
APP_DIR=/data/user/0/$APP/files
PID_FILE=/data/local/tmp/glyph-helper.pid
VOL_CONF=/data/local/tmp/glyph-volume.conf
VOL_BASE=/data/local/tmp/glyph-base
VOL_TARGET=/data/local/tmp/glyph-volume-target
SETTINGS=/system/bin/settings
GETEVENT=/system/bin/getevent
LOG=/data/local/tmp/glyph-helper.log

stop_old() {
    if [ -f "$PID_FILE" ]; then
        old=$(cat "$PID_FILE" 2>/dev/null)
        rm -f "$PID_FILE" 2>/dev/null
        if [ -n "$old" ]; then
            kill -9 -- "-$old" 2>/dev/null
            kill -9 "$old" 2>/dev/null
        fi
    fi
    # Older helpers left an orphaned event watcher behind. It would keep
    # reading the same key events as the new hook; remove those processes.
    for p in $(ps -A -o PID,ARGS | grep '[g]etevent -l' | awk '{print $1}'); do
        kill -9 "$p" 2>/dev/null
    done
}

case "$1" in
    --stop)
        stop_old
        exit 0
        ;;
    --restart)
        stop_old
        rm -f "$PID_FILE"
        setsid sh "$0" --daemon </dev/null >>"$LOG" 2>&1 &
        exit 0
        ;;
    --daemon)
        ;;
    *)
        if [ -f "$PID_FILE" ]; then
            old=$(cat "$PID_FILE" 2>/dev/null)
            if [ -n "$old" ] && kill -0 "$old" 2>/dev/null; then
                echo "helper already running (pid $old)"
                exit 0
            fi
            rm -f "$PID_FILE"
        fi
        setsid sh "$0" --daemon </dev/null >>"$LOG" 2>&1 &
        exit 0
        ;;
esac

mkdir -p "$APP_DIR" 2>/dev/null
printf '%s\n' "$$" > "$PID_FILE" 2>/dev/null
printf '%s\n' "$HELPER_VERSION" > "$APP_DIR/helper.version" 2>/dev/null
: > "$VOL_TARGET" 2>/dev/null
echo "helper v$HELPER_VERSION started (pid $$)"

heartbeat() {
    while true; do
        date +%s > "$APP_DIR/helper.heartbeat" 2>/dev/null
        sleep 2
    done
}
heartbeat &
HEARTBEAT_PID=$!

write_frame() {
    # Do not touch all_brightness here. The driver can restore the stock torch
    # value when operating_mode changes, which is exactly what caused the
    # volume bar to flicker against a full white ring. The startup claim below
    # switches mode first and clears the torch channels once, after the switch.
    printf '%s' "$1" > "$LED/frame_brightness" 2>/dev/null
}

scale_count() {
    n=$1
    value=$2
    max=$3
    if [ "$value" -le 0 ] || [ "$n" -le 0 ] || [ "$max" -le 0 ]; then
        echo 0
    elif [ "$value" -ge "$max" ]; then
        echo "$n"
    else
        echo $(( (n * value + max - 1) / max ))
    fi
}

build_volume_frame() {
    value=$1
    fade=$2
    set -- $(cat "$VOL_CONF" 2>/dev/null)
    [ "$#" -ge 7 ] || return 1
    c_val=$1
    a_val=$2
    b_val=$3
    c_count=$4
    a_count=$5
    b_count=$6
    max=$7
    [ "$max" -gt 0 ] || return 1
    [ "$c_count" -gt 0 ] || [ "$a_count" -gt 0 ] || [ "$b_count" -gt 0 ] || return 1
    c_lit=$(scale_count "$c_count" "$value" "$max")
    a_lit=$(scale_count "$a_count" "$value" "$max")
    b_lit=$(scale_count "$b_count" "$value" "$max")
    base=$(cat "$VOL_BASE" 2>/dev/null)
    set -- $base
    [ "$#" -eq 36 ] || set -- 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0
    out=""
    i=0
    for val in "$@"; do
        if [ "$i" -lt 20 ]; then
            if [ "$i" -lt "$c_lit" ]; then
                val=$c_val
                [ "$fade" -lt 100 ] && val=$((c_val * fade / 100))
            fi
        elif [ "$i" -lt 31 ]; then
            pos=$((30 - i))
            if [ "$pos" -lt "$a_lit" ]; then
                val=$a_val
                [ "$fade" -lt 100 ] && val=$((a_val * fade / 100))
            fi
        else
            pos=$((i - 31))
            if [ "$pos" -lt "$b_lit" ]; then
                val=$b_val
                [ "$fade" -lt 100 ] && val=$((b_val * fade / 100))
            fi
        fi
        out="$out $val"
        i=$((i + 1))
    done
    echo "${out# }"
}

# Claim the driver in frame mode, then clear the stock torch channels. The
# order matters: entering frame mode can restore the previous all-LED value.
printf '1' > "$LED/operating_mode" 2>/dev/null
printf '0' > "$LED/all_brightness" 2>/dev/null
printf '0' > "$LED/all_white_brightness" 2>/dev/null
printf '255' > "$LED/brightness" 2>/dev/null

volume_worker_loop() {
    last=-1
    idle=0
    while true; do
        value=$(cat "$VOL_TARGET" 2>/dev/null)
        case "$value" in
            ''|*[!0-9]*) value="" ;;
        esac
        if [ -z "$value" ]; then
            sleep 0.04
            continue
        fi
        if [ "$value" != "$last" ]; then
            last=$value
            idle=0
            frame=$(build_volume_frame "$value" 100)
            if [ -n "$frame" ]; then
                echo "volume $value -> bar (C/A/B counts from $(cat "$VOL_CONF" 2>/dev/null))"
                write_frame "$frame"
            else
                echo "volume $value ignored: no volume strip assigned"
            fi
        else
            idle=$((idle + 1))
            # Keep the bar above any app frame. The app has no volume write
            # path once the helper is live, but animations can still tick.
            frame=$(build_volume_frame "$value" 100)
            [ -n "$frame" ] && write_frame "$frame"
        fi

        if [ "$idle" -ge 20 ]; then
            # A new key during the fade must take over instead of being lost.
            next=$(cat "$VOL_TARGET" 2>/dev/null)
            if [ -n "$next" ] && [ "$next" != "$last" ]; then
                idle=0
                continue
            fi
            for fade in 65 35 15 0; do
                frame=$(build_volume_frame "$value" "$fade")
                [ -n "$frame" ] && write_frame "$frame"
                sleep 0.04
                next=$(cat "$VOL_TARGET" 2>/dev/null)
                if [ -n "$next" ] && [ "$next" != "$last" ]; then
                    idle=0
                    break
                fi
            done
            if [ "$idle" -eq 0 ]; then
                continue
            fi
            base=$(cat "$VOL_BASE" 2>/dev/null)
            set -- $base
            [ "$#" -eq 36 ] && write_frame "$base"
            : > "$VOL_TARGET" 2>/dev/null
            return
        fi
        sleep 0.06
    done
}

volume_loop() {
    last=-1
    volume_worker=""
    while true; do
        # No device argument watches every input node: the physical rocker is
        # not event7 on every build, and headset buttons still work too.
        $GETEVENT -l 2>/dev/null | while IFS= read -r line; do
            case "$line" in
                *KEY_VOLUMEUP*DOWN*|*KEY_VOLUMEDOWN*DOWN*)
                    value=$last
                    tries=0
                    while [ "$tries" -lt 12 ]; do
                        value=$($SETTINGS get system volume_music 2>/dev/null)
                        case "$value" in
                            ''|*[!0-9]*) value=$last ;;
                        esac
                        [ "$value" != "$last" ] && break
                        tries=$((tries + 1))
                        sleep 0.02
                    done
                    if [ "$value" != "$last" ]; then
                        last=$value
                        echo "volume key -> $value"
                        printf '%s\n' "$value" > "$VOL_TARGET" 2>/dev/null
                        if [ -z "$volume_worker" ] || ! kill -0 "$volume_worker" 2>/dev/null; then
                            volume_worker_loop &
                            volume_worker=$!
                        fi
                    fi
                    ;;
            esac
        done
        sleep 1
    done
}
volume_loop &

while true; do
    sleep 10
done
