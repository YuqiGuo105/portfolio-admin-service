package site.yuqi.media.work;

/**
 * Runs one kind of job. A handler does the work and then reports the result through
 * {@link site.yuqi.media.db.JobFlow}; it is the database, not the handler, that decides whether
 * the result still counts.
 *
 * <p>Throw {@link site.yuqi.media.media.VideoInfo.UnsupportedMediaException} when the input can
 * never work. Any other exception is treated as passing trouble and retried with a delay.
 */
public interface JobHandler {

    String jobType();

    void handle(JobContext context) throws Exception;
}
