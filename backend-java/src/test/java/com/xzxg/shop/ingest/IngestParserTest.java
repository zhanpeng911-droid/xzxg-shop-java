package com.xzxg.shop.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.xzxg.shop.domain.UnstructuredIngestRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class IngestParserTest {

    private final IngestParser parser = new IngestParser(JsonMapper.builder().build());

    @Test
    void stripsHtmlScriptsAndTags() {
        String text = IngestParser.htmlToText(
                "<html><body><script>ignore()</script><h1>标题</h1><p>正文 &amp; 说明</p></body></html>");

        assertThat(text).contains("标题").contains("正文 & 说明");
        assertThat(text).doesNotContain("ignore()").doesNotContain("<h1>");
    }

    @Test
    void flattensJsonIntoLines() {
        String text = parser.parse(new UnstructuredIngestRequest("m_001", "", "", "json",
                "", "", "{\"name\":\"手机\",\"spec\":{\"storage\":\"256GB\"},\"tags\":[\"拍照\"]}",
                Map.of(), false)).content();

        assertThat(text).contains("name: 手机")
                .contains("spec.storage: 256GB")
                .contains("tags[0]: 拍照");
    }

    @Test
    void infersTitleAndRunCount() {
        IngestParser.ParsedDocument parsed = parser.parse(new UnstructuredIngestRequest(
                "m_001", "", "", "text", "第一行标题\n\n正文内容", "", "", Map.of(), false));

        assertThat(parsed.title()).isEqualTo("第一行标题");
        assertThat(parsed.docType()).isEqualTo("unstructured_note");
        assertThat(parsed.textRunes()).isPositive();
    }

    @Test
    void rejectsEmptyContent() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> parser.parse(
                        new UnstructuredIngestRequest("m_001", "", "", "text", "", "", "",
                                Map.of(), false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty unstructured content");
    }
}
