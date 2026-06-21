package site.yuqi.admin.repo.source;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import site.yuqi.admin.domain.source.Project;

import java.util.List;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    @Query("""
        select p from Project p
        where (:keyword is null or
               lower(coalesce(p.title,'')) like lower(concat('%', :keyword, '%')) or
               lower(coalesce(p.content,'')) like lower(concat('%', :keyword, '%')))
          and (:category is null or p.category = :category)
        order by p.publishedAt desc nulls last
    """)
    List<Project> search(@Param("keyword") String keyword,
                         @Param("category") String category,
                         Pageable pageable);
}
