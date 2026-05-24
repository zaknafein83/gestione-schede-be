package it.fsisca.dndsheets.admin.dto;

import java.util.List;

/** Pagina di risultati per GET /admin/users. */
public record AdminUserPage(
        List<AdminUserSummary> items,
        long total,
        int page,
        int pageSize
) {}
