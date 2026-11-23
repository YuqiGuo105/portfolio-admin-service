package site.yuqi.admin.mediapipeline;

/**
 * How an outbox event leaves this service. Kafka in every deployed environment; plain HTTP exists
 * so that the whole pipeline can run on one machine without a broker.
 */
public interface MediaEventTransport {

    /** Returns normally only when the event has been handed over for certain. */
    void send(MediaWorkEvent event) throws Exception;
}
