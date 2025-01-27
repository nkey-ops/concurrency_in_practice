package main;

import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

/**
 * Using {@link FutureTask<?>} implements file downloading logic
 *
 * <p>Creates 15 {@link FutureTask}s and runs in separate threads. The class {@link DownloadTask}
 * represents a runnable responsible to imitate a file downloading process. {@link DownloadStatus}
 * is used to provide current information about a downloading process of {@link DownloadTask}.
 *
 * <p>The main thread will use {@link DownloadStatus} to read status of each downloading file and
 * send formatted output to the console.
 *
 * <p>The {@link DownloadTask} can throw a random exception, this will set the status of file
 * downloading to "Error" with an error message" and out put to the console.
 *
 * The main thread can randomly cancel the task which will lead to the status "Cancelled"
 */
public class FileDownloaderWithFutureTask {

    private static final String WIPE_LINE = "\033[2K";

    private static class DownloadStatus {
        public volatile String fileName;
        public volatile int downloadedBytes;
        public volatile int sizeBytes;
        public volatile long downloadingStartTimeMillis;
        public volatile boolean isFinished;
    }

    private static class DownloadResult {}

    private static class DownloadTask implements Callable<DownloadResult> {
        private DownloadStatus downloadStatus;

        public DownloadTask(DownloadStatus downloadStatus) {
            this.downloadStatus = downloadStatus;
        }

        @Override
        public DownloadResult call() throws Exception {
            // generate randomly size of file, upto 100 mb
            downloadStatus.sizeBytes = (int) (Math.random() * (1024 * 50)) + 1; // < 100MB
            downloadStatus.downloadingStartTimeMillis = System.currentTimeMillis();

            // sleeping for half a second, imitating downloading by adding random amount of bytes
            // out of remaining to download

            while((float) downloadStatus.downloadedBytes / downloadStatus.sizeBytes < 0.94) {
                Thread.sleep(500);
                var bytesDownloaded =
                        (int)
                                (Math.random()
                                        * (downloadStatus.sizeBytes - downloadStatus.downloadedBytes) / 8);

                downloadStatus.downloadedBytes += bytesDownloaded;

                // imitating random exception during downloading
                if (Math.random() > 0.995) throw new RuntimeException("Downloading Exception");
            }

            // just in case if after all iteration we didn't "download" all the bytes,add the
            // remaining
            downloadStatus.downloadedBytes +=
                    downloadStatus.sizeBytes - downloadStatus.downloadedBytes;

            return new DownloadResult();
        }
    }

    public static void main(String[] args) {
        download();
    }

    private static void download() {
        int downloads = 15;

        var futureTasks = new ArrayList<FutureTask<DownloadResult>>(downloads);
        var downloadStatuses = new ArrayList<DownloadStatus>(downloads);

        for (int i = 0; i < downloads; i++) {
            var downloadStatus = new DownloadStatus();
            downloadStatus.fileName = "file #" + (i + 1);
            downloadStatuses.add(downloadStatus);

            var task = new FutureTask<>(new DownloadTask(downloadStatus));
            new Thread(task).start();
            futureTasks.add(task);
        }

        try {
            var finishedDownloads = new ArrayList<String>(downloadStatuses.size());
            var isFirstCycle = true;
            int numberOfLastCycles = 0; // if 0 of last cycles, perform a last cycle, if 1 don't
            int downloadingFiles = downloadStatuses.size();
            do {
                if (isFirstCycle) {
                    isFirstCycle = false;
                } else {
                    System.out.printf("\033[%sA", downloadingFiles);
                }

                for (String string : finishedDownloads) {
                    System.out.print(WIPE_LINE);
                    System.out.println(string);
                }
                finishedDownloads.clear();

                for (int i = 0; i < downloadStatuses.size(); i++) {
                    var ds = downloadStatuses.get(i);
                    var futureTask = futureTasks.get(i);

                    if (Math.random() > 0.999) {
                        futureTask.cancel(true);
                    }

                    if (ds.isFinished) continue;

                    if (futureTask.isDone()) {
                        finishedDownloads.add(getDoneTaskInfo(ds, futureTask));
                        ds.isFinished = true;
                        downloadingFiles--;
                        continue;
                    }

                    int timePassedSec =
                            (int)
                                    ((System.currentTimeMillis() - ds.downloadingStartTimeMillis)
                                            / 1000);
                    int bytesPerSec =
                            timePassedSec == 0
                                    ? ds.downloadedBytes
                                    : ds.downloadedBytes / timePassedSec;
                    int downloadStatusPercentage =
                            ds.sizeBytes == 0 ? 50 : ds.downloadedBytes * 50 / ds.sizeBytes;

                    assert downloadStatusPercentage >= 0 && downloadStatusPercentage <= 100
                            : "downloadStatusPercentage is %s but should be >= 0 and <= 100"
                                    .formatted(downloadStatusPercentage);

                    System.out.print(WIPE_LINE);
                    System.out.printf(
                            "%-10s[%-50s] %3s%% %s/s | Downloaded: %s | Total: %s%n",
                            ds.fileName,
                            "=".repeat(downloadStatusPercentage),
                            downloadStatusPercentage * 2,
                            formatBytes(bytesPerSec),
                            formatBytes(ds.downloadedBytes),
                            formatBytes(ds.sizeBytes));
                }

                if (numberOfLastCycles == 1) {
                    for (String string : finishedDownloads) {
                        System.out.print(WIPE_LINE);
                        System.out.println(string);
                    }
                }
                Thread.sleep(100);
            } while (futureTasks.stream().anyMatch(ft -> !ft.isDone())
                    || numberOfLastCycles++ == 0);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private static String getDoneTaskInfo(
            DownloadStatus ds, FutureTask<DownloadResult> futureTask) {
        Objects.requireNonNull(ds);
        Objects.requireNonNull(futureTask);

        var status = "Finished";

        if (futureTask.isCancelled()) {
            status = "Cancelled";
        } else {
            try {
                futureTask.get();
            } catch (Exception e) {
                status = "Error: " + e.getMessage();
            }
        }

        return "%-10s | Size: %s | Downloaded: %s | Time Taken: %s sec | Status: %s "
                .formatted(
                        ds.fileName,
                        formatBytes(ds.sizeBytes),
                        formatBytes(ds.downloadedBytes),
                        (System.currentTimeMillis() - ds.downloadingStartTimeMillis) / 1000,
                        status);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 100) {
            return "%2s.0  B".formatted(bytes);
        } else if (bytes < 102400) { //  1024 100 == 100kb
            return "%4.1f KB".formatted((bytes / 1024f));
        } else if (bytes < 104857600) { // 1024^2 * 100 == 100mb
            return "%4.1f MB".formatted((bytes / 1048576d)); // 1024^2
        } else if (bytes < 107374182400L) { // 1024^3 * 100 == 100gb
            return "%4.1f GB".formatted((bytes / 1073741824d)); // 1024^4
        } else {
            return "%4.1f TB".formatted((bytes / 1099511627776d)); // 1024^5
        }
    }
}
