package site.yuqi.admin.service;

import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import site.yuqi.admin.domain.ContentEventOutbox;
import site.yuqi.admin.domain.OutboxEventType;
import site.yuqi.admin.domain.OutboxStatus;
import site.yuqi.admin.repo.ContentEventOutboxRepository;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class OutboxAfterCommitTest {
    @Test void workerUpdatesCommitAfterThePublishingTransactionHasFinished() {
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(new DriverManagerDataSource("jdbc:h2:mem:after_commit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setManagedTypes(PersistenceManagedTypes.of(ContentEventOutbox.class.getName()));
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
        factory.afterPropertiesSet();
        try {
            var manager = new JpaTransactionManager(factory.getObject());
            var transaction = new TransactionTemplate(manager);
            var entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
            var repository = new JpaRepositoryFactory(entityManager).getRepository(ContentEventOutboxRepository.class);
            var proxy = new ProxyFactory(new OutboxService(repository));
            proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
            var service = (OutboxService) proxy.getProxy();
            var id = UUID.randomUUID();
            transaction.executeWithoutResult(status -> {
                entityManager.persist(ContentEventOutbox.builder().id(id)
                        .eventType(OutboxEventType.CONTENT_PUBLISHED).sourceType("BLOG").sourceIdText("test")
                        .sourceVersion(1).topic("ARTICLE_UPDATES").payload(Map.of()).idempotencyKey(id.toString())
                        .status(OutboxStatus.PENDING).build());
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() { service.markOutboxEventProcessing(id, 60); }
                });
            });
            OutboxStatus processing = transaction.execute(status -> entityManager.find(ContentEventOutbox.class, id).getStatus());
            assertThat(processing).isEqualTo(OutboxStatus.PROCESSING);
            transaction.executeWithoutResult(status -> TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { service.markOutboxEventSent(id); }
            }));
            OutboxStatus sent = transaction.execute(status -> entityManager.find(ContentEventOutbox.class, id).getStatus());
            assertThat(sent).isEqualTo(OutboxStatus.SENT);
        } finally { factory.destroy(); }
    }
}
