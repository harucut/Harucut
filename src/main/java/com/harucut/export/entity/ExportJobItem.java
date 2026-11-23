package com.harucut.export.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "export_job_item", indexes = {
        @Index(name = "idx_export_job_item_job", columnList = "export_job_id"),
        @Index(name = "idx_export_job_item_compose_job", columnList = "compose_job_id")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExportJobItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "export_job_item_id")
    private Long id;

    @Column(name = "export_job_id", nullable = false)
    private Long exportJobId;

    @Column(name = "compose_job_id", nullable = false)
    private Long composeJobId;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    public ExportJobItem(Long exportJobId, Long composeJobId, int sortOrder) {
        this.exportJobId = exportJobId;
        this.composeJobId = composeJobId;
        this.sortOrder = sortOrder;
    }
}
