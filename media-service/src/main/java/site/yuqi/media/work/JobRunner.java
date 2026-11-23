package site.yuqi.media.work;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import site.yuqi.media.config.MediaProperties;
import site.yuqi.media.db.JobFlow;
import site.yuqi.media.db.MediaRepository;
import site.yuqi.media.media.ProcessRunner;
import site.yuqi.media.media.VideoInfo;
import site.yuqi.media.model.Job;

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Step 5: workers. Each thread leases one job, renews the lease while it works, and reports the
 * result. A worker that dies simply stops renewing; when the lease runs out the job is handed to
 * someone else as a new attempt.
 */
@Component
public class JobRunner implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private final MediaRepository repository;
    private final JobFlow flow;
    private final WorkDirectories directories;
    private final MediaProperties.Worker config;
    private final Map<String, JobHandler> handlers = new HashMap<>();
    private final String instance;
    private final List<Thread> threads = new ArrayList<>();
    private final Object idle = new Object();
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "media-heartbeat");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean running;

    public JobRunner(MediaRepository repository, JobFlow flow, WorkDirectories directories,
                     MediaProperties properties, List<JobHandler> handlers) {
        this.repository = repository;
        this.flow = flow;
        this.directories = directories;
        this.config = properties.worker();
        handlers.forEach(handler -> this.handlers.put(handler.jobType(), handler));
        this.instance = hostName() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Override
    public void start() {
        running = true;
        if (!config.enabled()) {
            log.info("Media workers are disabled on this instance");
            return;
        }
        for (int i = 1; i <= Math.max(1, config.concurrency()); i++) {
            String owner = instance + "/" + i;
            Thread thread = new Thread(() -> loop(owner), "media-worker-" + i);
            thread.setDaemon(true);
            threads.add(thread);
            thread.start();
        }
        log.info("Started {} media worker(s) as {}", threads.size(), instance);
    }

    @Override
    public void stop() {
        running = false;
        wake();
        threads.forEach(Thread::interrupt);
        heartbeats.shutdownNow();
        threads.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Tells idle workers there may be something to do, instead of leaving them to their next poll. */
    public void wake() {
        synchronized (idle) {
            idle.notifyAll();
        }
    }

    private void loop(String owner) {
        while (running) {
            try {
                Optional<Job> job = repository.claimNext(owner, config.leaseSeconds());
                if (job.isEmpty()) {
                    synchronized (idle) {
                        idle.wait(Math.max(100, config.pollIntervalMs()));
                    }
                    continue;
                }
                runOnce(job.get(), owner);
                wake();
            } catch (InterruptedException stopping) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception unexpected) {
                log.error("Media worker {} hit an unexpected error; pausing briefly", owner, unexpected);
                pause(5000);
            }
        }
    }

    /** One attempt of one job, from lease to outcome. Visible for tests. */
    public void runOnce(Job job, String owner) throws Exception {
        JobHandler handler = handlers.get(job.jobType());
        Path workDir = directories.forAttempt(job);
        JobContext context = new JobContext(job, owner, workDir);
        long every = Math.max(1, config.leaseSeconds() / 3);
        ScheduledFuture<?> heartbeat = heartbeats.scheduleAtFixedRate(() -> {
            try {
                if (!repository.heartbeat(job, owner, config.leaseSeconds())) context.markLeaseLost();
            } catch (RuntimeException databaseTrouble) {
                // Keep working; if renewals keep failing the lease expires and the reaper takes over.
                log.warn("Heartbeat for job {} failed: {}", job.id(), databaseTrouble.getMessage());
            }
        }, every, every, TimeUnit.SECONDS);
        long started = System.nanoTime();
        try {
            log.info("Job {} {} {} attempt {} started asset={} v{}", job.id(), job.jobType(), job.rendition(),
                    job.attempt(), job.assetId(), job.sourceVersion());
            if (handler == null) throw new VideoInfo.UnsupportedMediaException("no handler for " + job.jobType());
            handler.handle(context);
            log.info("Job {} {} {} attempt {} finished in {} ms{}", job.id(), job.jobType(), job.rendition(),
                    job.attempt(), (System.nanoTime() - started) / 1_000_000,
                    context.leaseLost() ? " (lease was lost; result discarded)" : "");
        } catch (ProcessRunner.CancelledException stopped) {
            log.warn("Job {} attempt {} stopped: {}", job.id(), job.attempt(), stopped.getMessage());
        } catch (VideoInfo.UnsupportedMediaException permanent) {
            log.warn("Job {} attempt {} cannot succeed: {}", job.id(), job.attempt(), permanent.getMessage());
            flow.fail(job, owner, permanent.getMessage(), true, 0);
        } catch (Exception transientFailure) {
            long backoff = Math.min(300, 15L << Math.min(4, job.attempt() - 1));
            JobFlow.Failure outcome = flow.fail(job, owner, describe(transientFailure), false, backoff);
            log.warn("Job {} attempt {} failed ({}): {}", job.id(), job.attempt(), outcome, describe(transientFailure));
        } finally {
            heartbeat.cancel(false);
            directories.discard(workDir);
        }
    }

    private static String describe(Exception error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
        }
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception unknown) {
            return "media";
        }
    }
}
