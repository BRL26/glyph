#!/system/bin/sh
# Legacy cleanup only.
#
# Earlier versions ran a root shell helper that watched the volume keys and
# wrote LED frames itself. It polled `settings get system volume_music`, which
# lagged the key press by up to ~240 ms, and it competed with the app's own
# writer, which produced the volume-bar-versus-white-ring flicker.
#
# Glyph now owns the LED node through a single long-lived root shell, so this
# module exists only to remove leftovers from the old helper at boot.
MODDIR=${0%/*}
PID_FILE=/data/local/tmp/glyph-helper.pid

if [ -f "$PID_FILE" ]; then
    old=$(cat "$PID_FILE" 2>/dev/null)
    rm -f "$PID_FILE"
    if [ -n "$old" ]; then
        kill -9 -- "-$old" 2>/dev/null
        kill -9 "$old" 2>/dev/null
    fi
fi

# Keep the bracketed patterns so this shell's own command line cannot match.
for p in $(ps -A -o PID,ARGS | grep '[g]lyph-helper\.sh' | awk '{print $1}'); do
    kill -9 "$p" 2>/dev/null
done
for p in $(ps -A -o PID,ARGS | grep '[g]etevent -l' | awk '{print $1}'); do
    kill -9 "$p" 2>/dev/null
done

rm -f /data/local/tmp/glyph-volume.conf \
      /data/local/tmp/glyph-base \
      /data/local/tmp/glyph-volume-target \
      /data/local/tmp/glyph-last-frame

# Auto-start Glyph once Android is up. This runs from KernelSU's late_start
# service as root, so it does not depend on the BOOT_COMPLETED receiver or on
# the ROM's background-start restrictions.
(
    while [ "$(getprop sys.boot_completed)" != "1" ]; do
        sleep 2
    done
    sleep 6
    am start-service --user 0 -n dev.brl.glyph/.GlyphService \
        -a dev.brl.glyph.REFRESH >/dev/null 2>&1
) &
