package site.yuqi.media.work;

import site.yuqi.media.model.Job;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** What a handler gets for one attempt: the leased job, who holds the lease, and a private scratch directory. */
public final class JobContext {

    private final Job job;
    private final String owner;
    private final Path workDir;
    private final AtomicBoolean leaseLost = new AtomicBoolean();

    public JobContext(Job job, String owner, Path workDir) {
        this.job = job;
        this.owner = owner;
        this.workDir = workDir;
    }

    public Job job() {
        return job;
    }

    public String owner() {
        return owner;
    }

    public Path workDir() {
        return workDir;
    }

    /** Set by the heartbeat when the database says this attempt no longer owns the job. */
    void markLeaseLost() {
        leaseLost.set(true);
    }

    public boolean leaseLost() {
        return leaseLost.get();
    }

    /** Handed to long-running child processes so they are killed as soon as the lease is gone. */
    public BooleanSupplier cancelled() {
        return leaseLost::get;
    }
}
