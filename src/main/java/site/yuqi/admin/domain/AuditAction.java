package site.yuqi.admin.domain;

public enum AuditAction {
    CREATE,
    UPDATE,
    PUBLISH,
    ARCHIVE,
    DELETE,
    REINDEX_RAG,
    REINDEX_SEARCH,
    RETRY_INDEXING_JOB
}
