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
        where (cast(:keyword as string) is null or
               lower(coalesce(lb.title,'')) like lower(concat('%', cast(:keyword as string), '%')) or
               lower(coalesce(lb.description,'')) like lower(concat('%', cast(:keyword as string), '%')))
          and (cast(:category as string) is null or lb.category = cast(:category as string))
        order by lb.publishedAt desc nulls last
    """)
    List<LifeBlog> search(@Param("keyword") String keyword,
                          @Param("category") String category,
                          Pageable pageable);
}
