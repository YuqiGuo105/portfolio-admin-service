package site.yuqi.admin.domain;

public enum OutboxStatus {
    PENDING,
    PROCESSING,
    SENT,
    FAILED,
    DLQ
}
