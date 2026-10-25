package com.msd.content.rag;

import com.msd.common.dto.DramaRagExportDTO;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * BM25 索引存储抽象。
 *
 * <p>4.6 由 ElasticsearchBm25Store 实现；在此之前索引服务可以正常构建向量部分，
 * BM25 分支返回不可用并标记 partial。</p>
 */
public interface Bm25IndexStore {

    /**
     * 删除并重建索引结构。
     */
    void recreateIndex();

    /**
     * 全量写入短剧文档和分集文档，返回成功条数。
     */
    int indexAll(List<DramaRagExportDTO> dramas);

    /**
     * 更新单条分集文档，供摘要回写后即时生效。
     */
    void upsertEpisode(Document document);

    /**
     * 刷新索引，保证写入立即可查。
     */
    void refresh();

    /**
     * 检查索引是否可用。
     */
    boolean ping();

    /**
     * 最近一次失败原因。
     */
    String getLastError();
}
