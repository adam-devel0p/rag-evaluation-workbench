package com.rageval.workbench.review.infrastructure;

import com.rageval.workbench.review.domain.HumanReview;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HumanReviewRepository extends JpaRepository<HumanReview, UUID> {

    List<HumanReview> findByMetricResultId(UUID metricResultId);

    List<HumanReview> findByMetricResultIdIn(Collection<UUID> metricResultIds);
}
