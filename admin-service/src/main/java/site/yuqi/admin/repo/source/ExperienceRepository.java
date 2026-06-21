package site.yuqi.admin.repo.source;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import site.yuqi.admin.domain.source.Experience;

import java.util.List;

public interface ExperienceRepository extends JpaRepository<Experience, Long> {

    @Query("""
        select e from Experience e
        where (:keyword is null or
               lower(coalesce(e.name,'')) like lower(concat('%', :keyword, '%')) or
               lower(coalesce(e.subname,'')) like lower(concat('%', :keyword, '%')) or
               lower(coalesce(e.text,'')) like lower(concat('%', :keyword, '%')))
        order by e.id desc
    """)
    List<Experience> search(@Param("keyword") String keyword, Pageable pageable);
}
