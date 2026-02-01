package vn.com.fecredit.flowable.exposer.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.com.fecredit.flowable.exposer.entity.SysExposeClassDef;

import java.util.List;
import java.util.Optional;

public interface SysExposeClassDefRepository extends JpaRepository<SysExposeClassDef, Long> {

    @Query("select s from SysExposeClassDef s where s.className = :className order by s.version desc")
    List<SysExposeClassDef> findByClassNameOrderByVersionDesc(@Param("className") String className);

    @Query("select s from SysExposeClassDef s where s.entityType = :entityType and s.enabled = true order by s.version desc")
    List<SysExposeClassDef> findEnabledByEntityTypeOrderByVersionDesc(@Param("entityType") String entityType);

    default Optional<SysExposeClassDef> findLatestEnabledByEntityType(String entityType) {
        return findEnabledByEntityTypeOrderByVersionDesc(entityType).stream().findFirst();
    }
}
