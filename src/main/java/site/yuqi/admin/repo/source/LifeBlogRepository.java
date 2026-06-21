package site.yuqi.admin.repo.source;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import site.yuqi.admin.domain.source.LifeBlog;

import java.util.List;

public interface LifeBlogRepository extends JpaRepository<LifeBlog, Long> {

    @Query("""
        select lb from LifeBlog lb
        where (:keyword is null or
               lower(coalesce(lb.title,'')) like lower(concat('%', :keyword, '%')) or
               lower(coalesce(lb.description,'')) like lower(concat('%', :keyword, '%')))
          and (:category is null or lb.category = :category)
        order by lb.publishedAt desc nulls last
    """)
    List<LifeBlog> search(@Param("keyword") String keyword,
                          @Param("category") String category,
                          Pageable pageable);
}
