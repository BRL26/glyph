package dev.brl.glyph;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

/**
 * Root sink for LED frames.
 *
 * Every frame goes through one long-lived `su` shell, so no root prompt is
 * spawned per frame and Off can never be swallowed by a dead pipe. A helper
 * module left by an older build is evicted on startup so it cannot compete for
 * the LED node.
 */
public final class Root {

    private static final String TAG = "GlyphRoot";
    private static final String LED = "/sys/class/leds/aw20036_led";
    private static final int DRIVER_MAX = 255;
    private static final int EXPECTED_HELPER_VERSION = 11;
    private static final String MODULE_DIR = "/data/adb/modules/glyph_helper";
    private static final long IDLE_CLOSE_MS = 20000L;
    private static final long HEARTBEAT_STALE_MS = 8000L;

    private static Root instance;

    private Context context;
    private FileDescriptor pipeFd;

    private Process process;
    private BufferedWriter writer;
    private final StringBuilder output = new StringBuilder();
    private final Object writeLock = new Object();
    private volatile boolean ready;
    private volatile long lastWrite;
    private int cachedHelperVersion;
    private long helperVersionCheckedAt;
    private boolean helperInstallAttempted;
    private long helperAttemptAt;

    private Root() {
    }

    public static synchronized Root get() {
        if (instance == null) {
            instance = new Root();
        }
        return instance;
    }

    public void init(Context appContext) {
        this.context = appContext.getApplicationContext();
        Log.i(TAG, "root sink initialized");
    }

    public boolean isReady() {
        return pipeFd != null || (ready && process != null && process.isAlive());
    }

    public boolean isUsingPipe() {
        // Frames deliberately stay on the live root shell. A FIFO can look
        // writable after its reader has died, which is exactly how an Off or a
        // preview used to disappear.
        return false;
    }

    /** The helper writes this file at start; the current hook is version 11. */
    public int helperVersion() {
        if (context == null) {
            return 0;
        }
        long now = System.currentTimeMillis();
        if (now - helperVersionCheckedAt < 2000L) {
            return cachedHelperVersion;
        }
        helperVersionCheckedAt = now;
        File file = new File(context.getFilesDir(), "helper.version");
        if (!file.isFile()) {
            cachedHelperVersion = 0;
            return 0;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(file)));
            String line = reader.readLine();
            cachedHelperVersion = line == null ? 0 : Integer.parseInt(line.trim());
            return cachedHelperVersion;
        } catch (IOException | NumberFormatException ignored) {
            cachedHelperVersion = 0;
            return 0;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                    // Already closed.
                }
            }
        }
    }

    /** True only when the helper has written version metadata recently. */
    public boolean helperAlive() {
        if (helperVersion() < EXPECTED_HELPER_VERSION || context == null) {
            return false;
        }
        File heartbeat = new File(context.getFilesDir(), "helper.heartbeat");
        long age = System.currentTimeMillis() - heartbeat.lastModified();
        return heartbeat.isFile() && age >= 0L && age < HEARTBEAT_STALE_MS;
    }

    /** Starts or reinstalls the helper outside any frame path. Never blocks. */
    public void ensureHelper() {
        if (context == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - helperAttemptAt < 5000L) {
            return;
        }
        helperAttemptAt = now;
        if (helperAlive()) {
            return;
        }
        prepare();
        if (helperVersion() < EXPECTED_HELPER_VERSION && !helperInstallAttempted) {
            helperInstallAttempted = true;
            if (installHelper()) {
                return;
            }
        }
        if (startSu()) {
            // The user can disable the module while the app itself is disabled.
            // Starting the app must clear that flag again.
            sendSu("rm -f " + MODULE_DIR + "/disable");
            sendSu("sh " + MODULE_DIR + "/service.sh");
            Log.i(TAG, "requested helper start");
        }
    }

    public boolean volumeHookAvailable() {
        return helperAlive();
    }

    /**
     * Installs the helper from res/raw through the app's existing root shell.
     * Sending it over stdin avoids relying on SELinux permission for the helper
     * to read the app's private files.
     */
    private boolean installHelper() {
        if (context == null || !startSu()) {
            return false;
        }
        String script = readRawHelper();
        if (script == null || script.length() == 0) {
            Log.w(TAG, "helper resource missing");
            return false;
        }
        // The bracketed patterns cannot match this shell's own command line.
        sendSu("for p in $(ps -A -o PID,ARGS | grep '[g]lyph-helper' | awk '{print $1}'); do kill -9 $p; done");
        sendSu("for p in $(ps -A -o PID,ARGS | grep '[g]etevent -l' | awk '{print $1}'); do kill -9 $p; done");
        sendSu("rm -f /data/adb/service.d/glyph-helper.sh");
        sendSu("mkdir -p " + MODULE_DIR);
        sendSu("cat > " + MODULE_DIR + "/module.prop <<'GLYPH_MODULE_EOF'\n"
                + "id=glyph_helper\n"
                + "name=Glyph Root Helper\n"
                + "version=" + EXPECTED_HELPER_VERSION + "\n"
                + "versionCode=" + EXPECTED_HELPER_VERSION + "\n"
                + "author=Glyph\n"
                + "description=Fast volume-key hook for the Glyph replacement app.\n"
                + "GLYPH_MODULE_EOF");
        String service = "#!/system/bin/sh\n"
                + "MODDIR=${0%/*}\n"
                + "PID_FILE=/data/local/tmp/glyph-helper.pid\n"
                + "LOG=/data/local/tmp/glyph-helper.log\n"
                + "if [ -f \"$PID_FILE\" ]; then old=$(cat \"$PID_FILE\" 2>/dev/null); rm -f \"$PID_FILE\"; "
                + "[ -n \"$old\" ] && kill -9 -- \"-$old\" 2>/dev/null; "
                + "[ -n \"$old\" ] && kill -9 \"$old\" 2>/dev/null; fi\n"
                + "for p in $(ps -A -o PID,ARGS | grep '[g]lyph-helper.sh --daemon' | awk '{print $1}'); do kill -9 $p; done\n"
                + "for p in $(ps -A -o PID,ARGS | grep '[g]etevent -l' | awk '{print $1}'); do kill -9 $p; done\n"
                + "rm -f \"$PID_FILE\"\n"
                + "setsid runcon u:r:su:s0 sh \"$MODDIR/glyph-helper.sh\" --daemon </dev/null >>\"$LOG\" 2>&1 &\n";
        sendSu("cat > " + MODULE_DIR + "/service.sh <<'GLYPH_SERVICE_EOF'\n"
                + service + "GLYPH_SERVICE_EOF");
        sendSu("chmod 755 " + MODULE_DIR + "/service.sh");
        sendSu("cat > " + MODULE_DIR + "/glyph-helper.sh <<'GLYPH_HELPER_EOF'\n"
                + script + "GLYPH_HELPER_EOF");
        sendSu("chmod 755 " + MODULE_DIR + "/glyph-helper.sh");
        sendSu("rm -f " + MODULE_DIR + "/disable " + MODULE_DIR + "/remove");
        sendSu("sh " + MODULE_DIR + "/service.sh");
        Log.i(TAG, "installed helper module v" + EXPECTED_HELPER_VERSION);
        return true;
    }

    private String readRawHelper() {
        InputStream input = null;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            input = context.getResources().openRawResource(R.raw.glyph_helper);
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) > 0) {
                output.write(buffer, 0, count);
            }
            String text = output.toString("UTF-8");
            return text.endsWith("\n") ? text : text + "\n";
        } catch (IOException | RuntimeException error) {
            Log.w(TAG, "could not read helper resource", error);
            return null;
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                    // Already closed.
                }
            }
        }
    }

    /** Configures the root-side volume bar. Values are already driver 0..255. */
    public void setVolumeConfig(int[] values, int[] counts, int max) {
        if (values == null || counts == null || values.length != 3 || counts.length != 3) {
            return;
        }
        if (!startSu()) {
            return;
        }
        String line = values[0] + " " + values[1] + " " + values[2] + " "
                + counts[0] + " " + counts[1] + " " + counts[2] + " "
                + Math.max(0, max);
        sendSu("printf '%s\\n' '" + line + "' > /data/local/tmp/glyph-volume.conf");
    }

    /** Clamps a frame value to the 8-bit range the driver actually stores. */
    public static int driverValue(int value) {
        return Math.max(0, Math.min(DRIVER_MAX, value));
    }

    public boolean test() {
        if (helperAlive()) {
            return true;
        }
        if (!startSu()) {
            return false;
        }
        String marker = "GLYPH_READY_" + System.nanoTime();
        return awaitCommand("id; echo " + marker, marker, 5000);
    }

    /** Runs a short read-only root command and returns its stdout. */
    public String read(String command, long timeoutMs) {
        if (!startSu()) {
            return "";
        }
        String marker = "GLYPH_OUT_" + System.nanoTime();
        synchronized (output) {
            output.setLength(0);
            sendSu(command + "; echo " + marker);
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                int index = output.indexOf(marker);
                if (index >= 0) {
                    return output.substring(0, index).trim();
                }
                if (process == null || !process.isAlive()) {
                    break;
                }
                try {
                    output.wait(80);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            String text = output.toString();
            int index = text.indexOf(marker);
            return index >= 0 ? text.substring(0, index).trim() : text.trim();
        }
    }

    /** Creates the helper FIFO. Must be called by the app so SELinux labels match. */
    public void prepare() {
        if (context == null) {
            return;
        }
        File dir = context.getFilesDir();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.d(TAG, "files dir unavailable: " + dir);
            return;
        }
        File pipe = new File(dir, "glyph.pipe");
        if (!pipe.exists()) {
            try {
                Os.mkfifo(pipe.getAbsolutePath(), 0600);
                Log.i(TAG, "created helper pipe at " + pipe);
            } catch (ErrnoException e) {
                if (e.errno != OsConstants.EEXIST) {
                    Log.d(TAG, "mkfifo failed for " + pipe + ": errno " + e.errno);
                }
            }
        }
    }

    private boolean ensurePipe() {
        if (pipeFd != null) {
            return true;
        }
        if (context == null) {
            return false;
        }
        prepare();
        File pipe = new File(context.getFilesDir(), "glyph.pipe");
        if (!pipe.exists()) {
            return false;
        }
        try {
            // Read-write: opening never blocks and frames buffer until the helper
            // attaches, so startup order cannot lose the first frame.
            pipeFd = Os.open(pipe.getAbsolutePath(),
                    OsConstants.O_RDWR | OsConstants.O_NONBLOCK | OsConstants.O_CLOEXEC, 0);
            ready = true;
            lastWrite = System.currentTimeMillis();
            Log.i(TAG, "root helper pipe ready");
            return true;
        } catch (ErrnoException e) {
            Log.d(TAG, "pipe open failed for " + pipe + ": errno " + e.errno);
            return false;
        }
    }

    private boolean awaitCommand(String command, String marker, long timeoutMs) {
        synchronized (output) {
            output.setLength(0);
            sendSu(command);
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                if (output.indexOf(marker) >= 0) {
                    ready = true;
                    return true;
                }
                if (process == null || !process.isAlive()) {
                    ready = false;
                    return false;
                }
                try {
                    output.wait(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private boolean startSu() {
        if (process != null && process.isAlive()) {
            return true;
        }
        closeSu();
        try {
            // ksu is denied write access to vendor_sysfs_glyph. The su domain
            // is allowed, so every frame shell must transition into it.
            ProcessBuilder builder = new ProcessBuilder(
                    "su", "-c", "exec runcon u:r:su:s0 sh");
            builder.redirectErrorStream(true);
            process = builder.start();
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    drain();
                }
            }, "glyph-root-reader");
            reader.setDaemon(true);
            reader.start();
            // Enter frame mode before clearing torch. The mode switch can
            // restore the stock all-LED value, so clearing first leaves a
            // white ring behind.
            sendSu("echo 1 > " + LED + "/operating_mode");
            sendSu("printf '0' > " + LED + "/all_brightness");
            sendSu("printf '0' > " + LED + "/all_white_brightness");
            // The app is now the only frame writer. Evict the old module
            // helper (and its getevent watcher) if an older boot started it,
            // otherwise it keeps painting volume frames underneath ours.
            sendSu("for p in $(ps -A -o PID,ARGS | grep '[g]lyph-helper' "
                    + "| awk '{print $1}'); do kill -9 $p; done; "
                    + "for p in $(ps -A -o PID,ARGS | grep '[g]etevent -l' "
                    + "| awk '{print $1}'); do kill -9 $p; done; "
                    + "rm -f /data/local/tmp/glyph-volume-target");
            ready = true;
            lastWrite = System.currentTimeMillis();
            Log.i(TAG, "su shell started");
            return true;
        } catch (IOException e) {
            Log.w(TAG, "su unavailable", e);
            ready = false;
            closeSu();
            return false;
        }
    }

    private void drain() {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            char[] buffer = new char[512];
            int count;
            while ((count = reader.read(buffer)) > 0) {
                synchronized (output) {
                    output.append(buffer, 0, count);
                    if (output.length() > 8192) {
                        output.delete(0, output.length() - 4096);
                    }
                    output.notifyAll();
                }
            }
        } catch (IOException ignored) {
            // Process closed.
        } finally {
            ready = false;
        }
    }

    private void sendSu(String command) {
        synchronized (writeLock) {
            if (writer == null) {
                return;
            }
            try {
                writer.write(command);
                writer.write('\n');
                writer.flush();
            } catch (IOException e) {
                Log.w(TAG, "root write failed", e);
                ready = false;
                closeSu();
            }
        }
    }

    private void sendPipe(String line) {
        if (pipeFd == null) {
            return;
        }
        byte[] bytes = (line + "\n").getBytes();
        try {
            Os.write(pipeFd, bytes, 0, bytes.length);
            lastWrite = System.currentTimeMillis();
        } catch (ErrnoException e) {
            if (e.errno == OsConstants.EAGAIN) {
                return;
            }
            Log.w(TAG, "pipe write failed: " + e.errno);
            closePipe();
        } catch (IOException e) {
            Log.w(TAG, "pipe write interrupted", e);
            closePipe();
        }
    }

    private static String frameNumbers(int[] values) {
        StringBuilder numbers = new StringBuilder(180);
        for (int i = 0; i < values.length; i++) {
            int value = values[i];
            if (value < 0) {
                value = 0;
            } else if (value > DRIVER_MAX) {
                value = DRIVER_MAX;
            }
            if (i > 0) {
                numbers.append(' ');
            }
            // aw20036_frame_brightness_store parses into
            // unsigned char brightness[36], so the frame domain is 0..255.
            // Sending 12-bit values made them wrap modulo 256, which is the
            // on/off flicker at the end of every fade.
            numbers.append(value);
        }
        return numbers.toString();
    }

    /** Writes a 36-value frame (0..255) through the live root shell. */
    public void writeFrame(int[] values) {
        if (values == null || values.length != 36 || !startSu()) {
            return;
        }
        String numbers = frameNumbers(values);
        int lit = 0;
        int max = 0;
        for (int value : values) {
            if (value > 0) {
                lit++;
            }
            if (value > max) {
                max = value;
            }
        }
        Log.d(TAG, "frame lit=" + lit + " max=" + max);
        // Only re-assert frame mode when the driver actually left it. Writing
        // operating_mode on every frame made the chip reconfigure in the
        // middle of fades, which looked like flicker.
        sendSu("read m < " + LED + "/operating_mode; "
                + "[ \"$m\" = 1 ] || printf '1' > " + LED + "/operating_mode; "
                + "printf '%s' '" + numbers + "' > " + LED + "/frame_brightness");
    }

    public void clear() {
        int[] off = new int[36];
        writeFrame(off);
        Log.i(TAG, "hard off frame queued");
    }

    public void exec(String shellCommand) {
        if (startSu()) {
            sendSu(shellCommand);
        }
    }

    /**
     * Starts the app service without a foreground service. The notification
     * listener normally keeps the process alive, but a root start also works
     * when the callback arrives from the background or after a force-stop.
     */
    public void startService(String action) {
        if (action == null || !startSu()) {
            return;
        }
        sendSu("am start-service --user 0 -n dev.brl.glyph/.GlyphService -a " + action);
    }

    /** Keeps the non-foreground service alive on ROMs with aggressive killers. */
    public void keepAlive() {
        if (!startSu()) {
            return;
        }
        sendSu("cmd deviceidle whitelist +dev.brl.glyph >/dev/null 2>&1; "
                + "am set-standby-bucket dev.brl.glyph active >/dev/null 2>&1; "
                + "appops set dev.brl.glyph RUN_IN_BACKGROUND allow >/dev/null 2>&1; "
                + "appops set dev.brl.glyph RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1");
    }

    private void closeSu() {
        ready = pipeFd != null;
        try {
            if (writer != null) {
                writer.close();
            }
        } catch (IOException ignored) {
            // Already closed.
        }
        writer = null;
        if (process != null) {
            process.destroy();
        }
        process = null;
    }

    private void closePipe() {
        if (pipeFd != null) {
            try {
                Os.close(pipeFd);
            } catch (ErrnoException ignored) {
                // Already closed.
            }
            pipeFd = null;
        }
    }

    public void close() {
        closeSu();
        closePipe();
        ready = false;
    }
}
