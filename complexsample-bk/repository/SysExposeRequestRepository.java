package vn.com.fecredit.complexsample.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.com.fecredit.complexsample.entity.SysExposeRequest;

import java.util.List;

public interface SysExposeRequestRepository extends JpaRepository<SysExposeRequest, Long> {
    List<SysExposeRequest> findByStatus(String status);
}
