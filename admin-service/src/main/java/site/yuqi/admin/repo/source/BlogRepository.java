package site.yuqi.admin.repo.source;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import site.yuqi.admin.domain.source.Blog;

import java.util.List;
import java.util.UUID;

public interface BlogRepository extends JpaRepository<Blog, UUID> {

    @Query("""
        select b from Blog b
        where (cast(:keyword as string) is null or
               lower(coalesce(b.title,'')) like lower(concat('%', cast(:keyword as string), '%')) or
               lower(coalesce(b.description,'')) like lower(concat('%', cast(:keyword as string), '%')))
          and (cast(:category as string) is null or b.category = cast(:category as string))
        order by coalesce(b.date, '') desc
    """)
    List<Blog> search(@Param("keyword") String keyword,
                      @Param("category") String category,
                      Pageable pageable);
}
