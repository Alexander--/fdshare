/*
 * Copyright © 2015 Alexander Rvachev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.sf.fdshare;

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.*;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.support.annotation.VisibleForTesting;
import android.util.Log;
import net.sf.fdshare.internal.FdCompat;

import java.io.Closeable;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.lang.Process;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A factory object, that can be used to create {@link ParcelFileDescriptor} instances for files, inaccessible to
 * the application itself. Simply put, semantics of {@link #open(File, int)} are same as of
 * {@link ParcelFileDescriptor#open(File, int)} but with root access.
 * <p>
 * Here is what it does:
 * <ul>
 * <li> requests root access by calling available "su" command;
 * <li> opens a file via the root access;
 * <li> gets it's file descriptor in JVM and returns to you in form of {@link ParcelFileDescriptor}.
 * </ul>
 * <p>
 * Known classes, that can be used with file descriptors are:
 * <ul>
 * <li> {@link java.io.FileInputStream} and {@link java.io.FileOutputStream};
 * <li> {@link java.io.RandomAccessFile};
 * <li> {@link java.nio.channels.FileChannel}
 * <li> {@link java.nio.channels.FileLock};
 * <li> {@link android.os.MemoryFile};
 * </ul>
 * and, probably, many others. The inner workings of {@link android.content.ContentProvider} and entire Android
 * Storage Access Framework are based on them as well.
 * <p>
 * The implementation uses a helper process, run with elevated privileges, that communicates with background thread via
 * a domain socket. There is a single extra thread and single process per factory instance and a best effort is taken to
 * cleanup those when the instance is closed.
 * <p>
 * Note, that most of descriptor properties, including read/write access modes can not be changed after it was created.
 * All descriptor properties are retained when passed between processes, such as via AIDL/Binder or Unix domain
 * sockets, but the integer number, representing the descriptor in each process, may change.
 *
 * @see ParcelFileDescriptor#open(File, int)
 * @see ParcelFileDescriptor#getFileDescriptor()
 */
public final class FileDescriptorFactory implements Closeable {
    public static final String DEBUG_MODE = "net.sf.fdshare.DEBUG";
    public static final String PRIMARY_TIMEOUT = "net.sf.fdshare.TIMEOUT_1";
    public static final String SECONDARY_TIMEOUT = "net.sf.fdshare.TIMEOUT_2";

    /**
     * This type covers most {@code open} flags, properly supported by Bionic and this library.
     * <p>
     * It constants are different from ones, used by {@link ParcelFileDescriptor#open(File, int)},
     * but meaning is mostly same. Consult manual pages of Linux {@code open} function for
     * further details about these.
     * <p>
     * Acceptable values:
     * <ul>
     * <li> {@link #O_RDONLY};
     * <li> {@link #O_WRONLY};
     * <li> {@link #O_RDWR};
     * <li> {@link #O_APPEND};
     * <li> {@link #O_CREAT};
     * <li> {@link #O_DIRECTORY};
     * <li> {@link #O_NOFOLLOW};
     * <li> {@link #O_PATH};
     * <li> {@link #O_TRUNC}.
     * </ul>
     * <p>
     * You can suppress Android Studio type safety warnings and use other values (for example,
     * taken directly from native header files). Beware, that you may be subject to various bugs
     * in Bionic (such as missing or improper O_LARGEFILE support etc.) if you decide to do so.
     * <p>
     * A number of flags, supported by {@code open} system call of modern Linux kernel
     * aren't present here for various reasons. Many of them aren't very relevant,
     * for example, because Java APIs already provide that functionality, and you can
     * set many flags with {@code fcntl} yourself anyway.
     * <p>
     * Note, that the {@link ProcessBuilder} and {@link Runtime#exec} already close
     * excess descriptors for you, so {@code O_CLOEXEC} does not need to be here either.
     */
    @IntDef(value = {
            O_RDONLY,
            O_WRONLY,
            O_RDWR,
            O_APPEND,
            O_CREAT,
            O_DIRECTORY,
            O_NOFOLLOW,
            O_PATH,
            O_TRUNC
    }, flag = true)
    @Documented
    @Retention(RetentionPolicy.SOURCE)
    public @interface OpenFlag {}

    int ff = 0x2000000;
    public static final int O_RDONLY = 0; // 0b000000000000000000
    public static final int O_WRONLY = 1; // 0b000000000000000001;
    public static final int O_RDWR = 2;   // 0b000000000000000010;

    public static final int O_APPEND = 1024;     // 0b0000000000010000000000; // 0x0008 on mips
    public static final int O_CREAT = 64;        // 0b0000000000000001000000; // 0x0100 on mips
    public static final int O_DIRECTORY = 65536; // 0b0000010000000000000000;
    public static final int O_NOFOLLOW = 131072; // 0b0000100000000000000000;
    public static final int O_PATH = 2097152;    // 0b1000000000000000000000;
    public static final int O_TRUNC = 512;       // 0b0000000000001000000000;

    private static final String FD_HELPER_TAG = "fdhelper";

    static final String EXEC_PIC = "fdshare_PIC_exec";
    static final String EXEC_NONPIC = "fdshare_exec";

    static final String EXEC_NAME;
    static final boolean DEBUG;
    static final long HELPER_TIMEOUT;
    static final long IO_TIMEOUT;

    static {
        EXEC_NAME = Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN ? EXEC_PIC : EXEC_NONPIC;

        DEBUG = "true".equals(System.getProperty(DEBUG_MODE));

        HELPER_TIMEOUT = Long.parseLong(System.getProperty(PRIMARY_TIMEOUT, "20000"));
        IO_TIMEOUT = Long.parseLong(System.getProperty(SECONDARY_TIMEOUT, "2500"));
    }

    /**
     * Create a FileDescriptorFactory, using an internally managed helper
     * process. The helper is run with superuser privileges via the "su"
     * command, available on system's PATH.
     * <p>
     * The device has to be rooted. The "su" command must support
     * {@code su -c "command with arguments"} syntax (most modern ones do).
     * <p>
     * You are highly recommended to cache and reuse the returned instance.
     *
     * @throws IOException if creation of instance fails, such as due to absence of "su" command in {@code PATH} etc.
     */
    public static FileDescriptorFactory create(Context context) throws IOException {
        final String command = new File(FdCompat.libDir(context), System.mapLibraryName(EXEC_NAME)).getAbsolutePath();

        final String address = UUID.randomUUID().toString();

        return BuildConfig.DEBUG ? create(address, command, address) : create(address, "su", "-c", command + ' ' + address);
    }

    @VisibleForTesting
    static FileDescriptorFactory create(String address, String... cmd) throws IOException {
        // must be created before the process
        final LocalServerSocket socket = new LocalServerSocket(address);
        try {
            final Process shell = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();

            final FileDescriptorFactory result = new FileDescriptorFactory(shell, socket);

            result.startServer();

            return result;
        } catch (Throwable t) {
            shut(socket);

            throw t;
        }
    }

    private final AtomicBoolean closedStatus = new AtomicBoolean(false);
    private final ArrayBlockingQueue<FdReq> intake = new ArrayBlockingQueue<>(1);
    private final SynchronousQueue<FdResp> responses = new SynchronousQueue<>();

    final LocalServerSocket serverSocket;
    final Process clientProcess;

    private volatile Server serverThread;

    private FileDescriptorFactory(final Process clientProcess, final LocalServerSocket serverSocket) {
        this.clientProcess = clientProcess;
        this.serverSocket = serverSocket;

        intake.offer(FdReq.PLACEHOLDER);
    }

    private void startServer() throws IOException {
        serverThread = new Server();
        serverThread.start();
    }

    /**
     * Return file descriptor for supplied file, open for read-write access with default flags
     * (which are {@link #O_RDWR} and {@link #O_CREAT}) and the same creation mode as used by
     * {@link ParcelFileDescriptor#open(File, int)}.
     *
     * <p>
     *
     * <b>Do not call this method from the main thread!</b>
     *
     * @throws IOException recoverable error, such as when file was not found
     * @throws FactoryBrokenException irrecoverable error, that renders this factory instance unusable
     */
    public @NonNull ParcelFileDescriptor open(File file) throws IOException, FactoryBrokenException {
        return open(file, O_RDWR | O_CREAT);
    }

    /**
     * Return file descriptor for supplied file, open for specified access with supplied flags
     * and the same creation mode as used by {@link ParcelFileDescriptor#open(File, int)}.
     *
     * <p>
     *
     * <b>Do not call this method from the main thread!</b>
     *
     * @param file the {@link File} object, with path to the target file, not necessarily accessible to your UID
     * @param mode either {@link #O_RDONLY}, {@link #O_WRONLY} or {@link #O_RDWR}, or-ed with other {@link OpenFlag} constants
     *
     * @throws IOException recoverable error, such as when file was not found
     * @throws FactoryBrokenException irrecoverable error, that renders this factory instance unusable
     */
    public @NonNull ParcelFileDescriptor open(File file, @OpenFlag int mode) throws IOException, FactoryBrokenException {
        return FdCompat.adopt(openFileDescriptor(file, mode));
    }

    /**
     * Shorthand for creating a {@link RandomAccessFile} from {@link FileDescriptor}, when all you need is
     * a simple read/write functionality.
     */
    public @NonNull RandomAccessFile openRandomAccessFile(File file) throws IOException, FactoryBrokenException {
        return FdCompat.convert(openFileDescriptor(file, O_RDWR | O_CREAT));
    }

    @NonNull FileDescriptor openFileDescriptor(File file, @OpenFlag int mode) throws IOException, FactoryBrokenException {
        if (closedStatus.get())
            throw new FactoryBrokenException("Already closed");

        final String path = file.getPath();

        final FdReq request = new FdReq(path, mode);


        FdResp response;
        try {
            if (intake.offer(request, HELPER_TIMEOUT, TimeUnit.MILLISECONDS)
                    && (response = responses.poll(IO_TIMEOUT, TimeUnit.MILLISECONDS)) != null
                    && response.request == request) {
                if (response.fd != null)
                    return response.fd;
                else
                    throw new IOException("Failed to open file: " + response.message);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new IOException("Interrupted before completion");
        }

        close();

        throw new FactoryBrokenException("Failed to retrieve response from helper");
    }

    /**
     * Set the flag, indicating the internally used thread and helper process to stop and making further attempts
     * to use this instance fail. This method can be used any number of times, even if the instance is already closed.
     * It does not throw.
     */
    @Override
    public void close() {
        if (!closedStatus.compareAndSet(false, true)) {
            shut(clientProcess);
            shut(serverSocket);

            if (serverThread != null) {
                serverThread.interrupt();

                do {
                    intake.clear();
                }
                while (!intake.offer(FdReq.STOP));
            }
        }
    }

    public boolean isClosed() {
        return closedStatus.get();
    }

    private final class Server extends Thread {
        private final ByteBuffer statusMsg = ByteBuffer.allocate(512).order(ByteOrder.nativeOrder());

        int lastClientReadCount;

        Server() throws IOException {
            super("fd receiver");
        }

        @Override
        public void run() {
            try (ReadableByteChannel clientOutput = Channels.newChannel(clientProcess.getInputStream());
                 Closeable c = new CloseableSocket(serverSocket))
            {
                try {
                    initializeAndHandleRequests(readHelperPid(clientOutput));
                } finally {
                    try {
                        do {
                            lastClientReadCount = clientOutput.read(statusMsg);

                            if (statusMsg.position() == statusMsg.limit())
                                statusMsg.clear();
                        }
                        while (lastClientReadCount != -1);
                    }
                    catch (IOException ignored) {}
                }
            } catch (Exception e) {
                logException("Server thread forced to quit by error", e);
            } finally {
                FdCompat.set(closedStatus);

                try {
                    setName("BUG: Waiting for su process, which won't quit");

                    clientProcess.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private int readHelperPid(ReadableByteChannel clientOutput) throws IOException {
            // the client forks to obtain controlling terminal for itself
            // so we need some way of knowing it's pid
            // note, that certain people are known to write linkers, spouting random bullshit during
            // executable startup, so we must be prepared to filter that out
            final String greeting = readMessage(clientOutput);

            final Matcher m = Pattern.compile("(?:.*)PID:(\\d+)").matcher(greeting);

            if (!m.matches())
                throw new IOException("Can't get helper PID" + (greeting.length() == 0 ? "" : " : " + greeting));

            return Integer.valueOf(m.group(1));
        }

        private void initializeAndHandleRequests(int helperPid) throws Exception {
            while (!isInterrupted()) {
                try (LocalSocket localSocket = serverSocket.accept())
                {
                    final int socketPid = localSocket.getPeerCredentials().getPid();
                    if (socketPid != helperPid)
                        continue;

                    try (ReadableByteChannel status = Channels.newChannel(localSocket.getInputStream())) {
                        final String socketMsg = readMessage(status);
                        final FileDescriptor ptmxFd = getFd(localSocket);

                        if (ptmxFd == null)
                            throw new Exception("Can't get client tty" + (socketMsg.length() == 0 ? "" : " : " + socketMsg));

                        logTrace(Log.DEBUG, "Response to tty request: '" + socketMsg + "', descriptor " + ptmxFd);

                        try (Writer clientTty = Channels.newWriter(new FileOutputStream(ptmxFd).getChannel(), "UTF-8")) {
                            // Indicate to the helper that it can close it's copy of it's controlling tty.
                            // When our end is closed the kernel tty driver will send SIGHUP to the helper,
                            // cleanly killing it's root process for us
                            clientTty.append("GO\n");
                            clientTty.flush();

                            // as little exercise in preparation to real deal, try to protect our helper from OOM killer
                            final String oomFile = "/proc/" + helperPid + "/oom_score_adj";

                            final FdResp oomFileTestResp = sendFdRequest(new FdReq(oomFile, O_RDWR), clientTty, status, localSocket);

                            logTrace(Log.DEBUG, "Response to " + oomFile + " request: " + oomFileTestResp);

                            if (oomFileTestResp.fd != null) {
                                try (OutputStreamWriter oow = new OutputStreamWriter(new FileOutputStream(oomFileTestResp.fd))) {
                                    oow.append("-1000");

                                    logTrace(Log.DEBUG, "Successfully adjusted helper's OOM score to -1000");
                                } catch (IOException ok) {
                                    logException("Write to " + oomFile + " failed", ok);
                                }
                            }

                            if (intake.take() == FdReq.STOP)
                                return;

                            processRequestsUntilStopped(localSocket, status, clientTty);

                            break;
                        }
                    }
                }
            }
        }

        private void processRequestsUntilStopped(LocalSocket fdrecv, ReadableByteChannel status, Writer control) throws IOException, InterruptedException {
            FdReq fileOps;

            while ((fileOps = intake.take()) != FdReq.STOP) {
                FdResp response = null;

                try {
                    try {
                        response = sendFdRequest(fileOps, control, status, fdrecv);

                        if (!responses.offer(response, IO_TIMEOUT, TimeUnit.MILLISECONDS))
                            FdCompat.closeDescriptor(response.fd);
                    } catch (IOException ioe) {
                        responses.offer(new FdResp(fileOps, ioe.getMessage(), null), IO_TIMEOUT, TimeUnit.MILLISECONDS);

                        throw ioe;
                    }
                } catch (InterruptedException ie) {
                    if (response != null)
                        FdCompat.closeDescriptor(response.fd);

                    throw ie;
                }
            }
        }

        private FdResp sendFdRequest(FdReq fileOps, Writer req, ReadableByteChannel resp, LocalSocket ls) throws IOException {
            req.append(String.valueOf(fileOps.fileName.getBytes().length)).flush();
            req.append(fileOps.fileName).append('\n').append(String.valueOf(fileOps.mode)).append('\n').flush();

            String responseStr = readMessage(resp);

            final FileDescriptor fd = getFd(ls);

            if (fd == null && "READY".equals(responseStr)) { // unlikely, but..
                responseStr = "Received no file descriptor from helper";
            }

            return new FdResp(fileOps, responseStr, fd);
        }


        private String readMessage(ReadableByteChannel channel) throws IOException {
            statusMsg.clear();

            lastClientReadCount = channel.read(statusMsg);

            return new String(statusMsg.array(), 0, statusMsg.position());
        }

        private FileDescriptor getFd(LocalSocket ls) throws IOException {
            final FileDescriptor[] fds = ls.getAncillaryFileDescriptors();

            return fds != null && fds.length == 1 && fds[0] != null ? fds[0] : null;
        }
    }

    private static void logTrace(int proprity, String message, Object... args) {
        if (DEBUG)
            Log.println(proprity, FD_HELPER_TAG, String.format(message, args));
    }

    private static void logException(String explanation, Exception err) {
        if (DEBUG) {
            Log.e(FD_HELPER_TAG, explanation);

            err.printStackTrace();
        } else {
            Log.d(FD_HELPER_TAG, explanation);
        }
    }

    private static void shut(Closeable closeable) {
        try {
            if (closeable != null)
                closeable.close();
        } catch (IOException e) {
            // just as planned
        }
    }

    private static void shut(LocalServerSocket sock) {
        try {
            if (sock != null)
                sock.close();
        } catch (IOException e) {
            logException("Failed to close server socket", e);
        }
    }

    private static void shut(Process proc) {
        try {
            if (proc != null) {
                shut(proc.getInputStream());
                shut(proc.getOutputStream());
                proc.destroy();
            }
        } catch (Exception e) {
            // just as planned
        }
    }

    private static final class FdReq {
        static FdReq STOP = new FdReq(null, 0);

        static FdReq PLACEHOLDER = new FdReq(null, 0);

        final String fileName;
        final int mode;

        public FdReq(String fileName, int mode) {
            this.fileName = fileName;
            this.mode = mode;
        }

        @Override
        public String toString() {
            return fileName + ',' + mode;
        }
    }

    private static final class FdResp {
        final FdReq request;
        final String message;
        final FileDescriptor fd;

        public FdResp(FdReq request, String message, FileDescriptor fd) {
            this.request = request;
            this.message = message;
            this.fd = fd;
        }

        @Override
        public String toString() {
            return "Request: " + request + ". Helper response: '" + message + "', descriptor: " + fd;
        }
    }

    // workaround for some stupid bug in annotations extractor
    private static class CloseableSocket implements Closeable {
        private final LocalServerSocket lss;

        public CloseableSocket(LocalServerSocket lss) {
            this.lss = lss;
        }

        @Override
        public void close() throws IOException {
            lss.close();
        }
    }
}

