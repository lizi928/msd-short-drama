package com.msd.content.client;

import com.msd.common.constant.ServiceNames;
import com.msd.common.dto.DramaRagExportDTO;
import com.msd.common.result.PageResult;
import com.msd.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 短剧目录导出客户端，只走服务间直连，不经过网关。
 */
@FeignClient(name = ServiceNames.DRAMA, contextId = "dramaCatalogClient",
        path = "/internal-source/rag", configuration = ContentFeignConfig.class)
public interface DramaCatalogClient {

    /**
     * 分页导出一页已上架短剧。
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数
     * @return 分页封装的短剧导出数据
     */
    @GetMapping("/dramas")
    Result<PageResult<DramaRagExportDTO>> export(@RequestParam("page") int page,
                                                 @RequestParam("size") int size);
}
