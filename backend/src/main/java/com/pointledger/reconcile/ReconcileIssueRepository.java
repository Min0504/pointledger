package com.pointledger.reconcile;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconcileIssueRepository extends JpaRepository<ReconcileIssue, Long> {

    List<ReconcileIssue> findByResolvedOrderByIdDesc(boolean resolved);
}
