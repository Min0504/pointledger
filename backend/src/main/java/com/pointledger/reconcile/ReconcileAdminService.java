package com.pointledger.reconcile;

import com.pointledger.common.error.DomainException;
import com.pointledger.common.error.ErrorCode;
import com.pointledger.reconcile.dto.ReconcileDtos.IssueView;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReconcileAdminService {

    private final ReconcileIssueRepository issueRepository;

    @Transactional(readOnly = true)
    public List<IssueView> issues(boolean resolved) {
        return issueRepository.findByResolvedOrderByIdDesc(resolved)
                .stream().map(IssueView::from).toList();
    }

    @Transactional
    public IssueView resolve(Long issueId, String memo, String operator) {
        ReconcileIssue issue = issueRepository.findById(issueId)
                .orElseThrow(() -> new DomainException(ErrorCode.ISSUE_NOT_FOUND));
        if (issue.isResolved()) {
            // 두 운영자가 같은 이슈를 집는 경우 — 나중 쪽에 이미 처리됐음을 알린다
            throw new DomainException(ErrorCode.ISSUE_ALREADY_RESOLVED);
        }
        issue.resolve(operator, memo);
        return IssueView.from(issue);
    }
}
