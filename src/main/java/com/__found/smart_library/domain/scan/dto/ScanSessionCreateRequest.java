package com.__found.smart_library.domain.scan.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record ScanSessionCreateRequest(
        @NotBlank @Size(max = 100) String sessionName,
        @NotBlank @Size(max = 30) String zone,
        LocalDate plannedStartDate
) {
}
