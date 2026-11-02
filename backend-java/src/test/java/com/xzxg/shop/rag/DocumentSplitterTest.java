package com.xzxg.shop.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentSplitterTest {

    @Test
    void splitsLongTextWithoutExceedingChunkSize() {
        String content = ("第一段内容。".repeat(200)) + "\n\n" + ("第二段内容。".repeat(200));
        List<DocumentSplitter.ChunkDraft> chunks =
                DocumentSplitter.split("测试文档", content, "product_detail");

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(DocumentSplitter.runeLength(chunk.content()))
                        .isLessThanOrEqualTo(DocumentSplitter.CHUNK_MAX_RUNES + 1));
        assertThat(chunks.get(0).sortOrder()).isEqualTo(10);
        assertThat(chunks.get(0).snippet().codePointCount(0, chunks.get(0).snippet().length()))
                .isLessThanOrEqualTo(DocumentSplitter.SNIPPET_RUNES);
    }

    @Test
    void faqDocumentSplitsOnQuestions() {
        String content = "Q: 怎么退货？\nA: 在订单页申请。\nQ: 多久发货？\nA: 48 小时内。";
        List<DocumentSplitter.ChunkDraft> chunks =
                DocumentSplitter.split("售后", content, "faq");

        assertThat(chunks).hasSize(2);
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.title()).isEqualTo("售后 / FAQ"));
        assertThat(chunks.get(0).content()).contains("怎么退货");
        assertThat(chunks.get(1).content()).contains("多久发货");
    }

    @Test
    void snippetTruncatesByCodePoints() {
        assertThat(DocumentSplitter.snippet("一二三四五", 3)).isEqualTo("一二三");
        assertThat(DocumentSplitter.snippet("abc", 10)).isEqualTo("abc");
    }
}
