package vn.com.fecredit.flowable.exposer.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.com.fecredit.flowable.exposer.entity.SysCaseDataStore;
import java.util.List;

public interface SysCaseDataStoreRepository extends JpaRepository<SysCaseDataStore, Long> {
    List<SysCaseDataStore> findByEntityType(String entityType);
    SysCaseDataStore findByCaseInstanceId(String caseInstanceId);
}
