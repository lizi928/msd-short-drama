package com.msd.drama.controller;

import com.msd.common.dto.DramaRagExportDTO;
import com.msd.common.result.PageResult;
import com.msd.common.result.Result;
import com.msd.drama.service.DramaRagExportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * RAG 索引目录导出接口。
 *
 * <p>放在 /internal-source/** 下，网关不路由，只允许服务间直连。</p>
 */
@RestController
@RequestMapping("/internal-source/rag")
public class InternalRagDramaController {

    // RAG 索引目录导出服务
    private final DramaRagExportService exportService;

    /**
     * 注入 RAG 目录导出服务。
     */
    public InternalRagDramaController(DramaRagExportService exportService) {
        this.exportService = exportService;
    }

    /**
     * 分页导出已上架短剧。
     */
    @GetMapping("/dramas")
    public Result<PageResult<DramaRagExportDTO>> dramas(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "200") int size) {
        // 1. 返回已上架短剧、分类、标签、演员和在线剧集标题
        return Result.ok(exportService.export(page, size));
    }
}
