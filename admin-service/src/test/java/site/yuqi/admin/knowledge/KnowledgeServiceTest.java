package site.yuqi.admin.knowledge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.yuqi.admin.domain.AuditAction;
import site.yuqi.admin.service.AuditLogService;
import site.yuqi.admin.service.IndexingJobService;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class KnowledgeServiceTest {
    KnowledgeRepository repo = mock(KnowledgeRepository.class);
    AuditLogService audit = mock(AuditLogService.class);
    IndexingJobService jobs = mock(IndexingJobService.class);
    KnowledgeService service = new KnowledgeService(repo, audit, jobs, mock(KnowledgeIndexDispatcher.class));
    UUID id = UUID.randomUUID();
    String revision = "a".repeat(32);
    KnowledgeRepository.Entry row;

    @BeforeEach void setup() {
        row = new KnowledgeRepository.Entry(id, "Verified answer", Map.of("type", "chat_qa", "management_version", 2), "2026-09-11", revision, true);
        when(repo.find(eq(id), anyBoolean())).thenReturn(Optional.of(row));
        when(repo.indexing(any())).thenReturn(Map.of("status", "PENDING"));
    }
    KnowledgeMutation body(String status, String visibility, String rev) {
        return new KnowledgeMutation("Education", "What education?", "Verified answer", status, visibility, rev);
    }
    @Test void defaultIsPrivateDraft() {
        var body = new KnowledgeMutation("Title", null, "Answer", null, null, null).validated();
        assertThat(body.status()).isEqualTo("DRAFT"); assertThat(body.answerVisibility()).isEqualTo("private");
    }
    @Test void rejectsBlankOversizedAndUnapprovedActivation() {
        assertThatThrownBy(() -> new KnowledgeMutation(" ", null, "Answer", null, null, null).validated()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeMutation("Title", null, "a".repeat(20001), null, null, null).validated()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> body("ACTIVE", "private", revision).validated()).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void activeUpdateInvalidatesOldEvidenceAndQueuesOneVersion() {
        service.update(id, body("ACTIVE", "public", revision), "admin");
        verify(repo).invalidateDerived(id); verify(jobs).enqueueKnowledge(id.toString(), 3);
        verify(audit).log(eq("admin"), eq(AuditAction.UPDATE), eq("KNOWLEDGE"), eq(id.toString()), eq(3), anyMap(), anyMap());
        var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(repo).update(eq(id), eq("Verified answer"), captor.capture());
        assertThat(captor.getValue()).containsEntry("source_requires_login", true).containsEntry("retrieval_eligible", false);
    }
    @Test void draftUpdateDisablesOldEvidenceWithoutModelWork() {
        service.update(id, body("DRAFT", "private", revision), "admin");
        verify(repo).invalidateDerived(id); verifyNoInteractions(jobs);
    }
    @Test void staleRevisionCannotWriteOrDelete() {
        assertThatThrownBy(() -> service.update(id, body("ACTIVE", "public", "b".repeat(32)), "admin")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.delete(id, "b".repeat(32), "admin")).isInstanceOf(IllegalStateException.class);
        verify(repo, never()).update(any(), any(), any()); verify(repo, never()).delete(any()); verifyNoInteractions(jobs, audit);
    }
    @Test void missingRevisionRejected() {
        assertThatThrownBy(() -> service.delete(id, null, "admin")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void generatedSourceIsReadOnly() {
        when(repo.find(eq(id), anyBoolean())).thenReturn(Optional.of(new KnowledgeRepository.Entry(id, "chunk", Map.of(), "", revision, false)));
        assertThatThrownBy(() -> service.update(id, body("DRAFT", "private", revision), "admin")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.delete(id, revision, "admin")).isInstanceOf(IllegalStateException.class);
    }
    @Test void deleteAuditsAndRemovesDerivedData() {
        assertThat(service.delete(id, revision, "admin")).containsEntry("deleted", true);
        verify(repo).delete(id); verify(audit).log(eq("admin"), eq(AuditAction.DELETE), eq("KNOWLEDGE"), eq(id.toString()), eq(2), anyMap(), isNull());
    }
    @Test void boundedSearchAndNoMissingRecordFabrication() {
        assertThatThrownBy(() -> service.list("", "ALL", "ALL", 1000, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list("", "INVALID", "ALL", 25, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.get(UUID.randomUUID())).isInstanceOf(NoSuchElementException.class);
    }
    @Test void createRequiresStableKey() {
        assertThatThrownBy(() -> service.create(body("DRAFT", "private", null), null, "admin")).isInstanceOf(IllegalArgumentException.class);
        verify(repo, never()).insert(any(), any(), any());
    }
}
