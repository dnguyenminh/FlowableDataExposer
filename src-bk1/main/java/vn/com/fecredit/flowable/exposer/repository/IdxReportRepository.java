package vn.com.fecredit.flowable.exposer.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.com.fecredit.flowable.exposer.entity.IdxReport;
import java.util.Optional;

public interface IdxReportRepository extends JpaRepository<IdxReport, Long> {
    Optional<IdxReport> findByCaseInstanceId(String caseInstanceId);
    void deleteByCaseInstanceId(String caseInstanceId);
}
