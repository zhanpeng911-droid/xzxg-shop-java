package com.xzxg.shop.knowledge;

import com.xzxg.shop.common.PageRequest;
import com.xzxg.shop.common.PageResponse;
import com.xzxg.shop.domain.Citation;
import com.xzxg.shop.domain.KnowledgeDocument;
import com.xzxg.shop.domain.KnowledgeDocumentInput;
import com.xzxg.shop.domain.UnstructuredIngestRequest;
import com.xzxg.shop.domain.UnstructuredIngestResult;
import com.xzxg.shop.ingest.IngestParser;
import com.xzxg.shop.rag.DocumentSplitter;
import com.xzxg.shop.retrieval.RetrievalRanker;
import com.xzxg.shop.retrieval.VectorService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeService {

    private final KnowledgeRepository repository;
    private final IngestParser parser;
    private final VectorService vectors;

    public KnowledgeService(KnowledgeRepository repository, IngestParser parser,
                            VectorService vectors) {
        this.repository = repository;
        this.parser = parser;
        this.vectors = vectors;
    }

    public UnstructuredIngestResult ingest(UnstructuredIngestRequest request) {
        IngestParser.ParsedDocument parsed = parser.parse(request);
        KnowledgeRepository.CreateResult result = repository.create(new KnowledgeDocumentInput(
                request.merchantId() == null ? "" : request.merchantId().trim(),
                parsed.title(),
                parsed.docType(),
                parsed.content(),
                parsed.sourceUrl(),
                KnowledgeRepository.sha256Hex(parsed.content()),
                parsed.metadata(),
                request.forceReindex()));
        return new UnstructuredIngestResult(result.document(), result.duplicate(),
                parsed.textRunes());
    }

    public KnowledgeDocument create(KnowledgeDocumentInput input) {
        return repository.create(input).document();
    }

    public PageResponse<KnowledgeDocument> documents(String merchantId, PageRequest page,
                                                     boolean all) {
        return repository.listDocuments(merchantId, page, all);
    }

    public List<Citation> search(String query, int topK) {
        List<RetrievalRanker.Candidate> keyword = repository.searchKeyword(query, 200);
        Map<String, RetrievalRanker.Candidate> merged = new LinkedHashMap<>();
        for (RetrievalRanker.Candidate candidate : keyword) {
            merged.put(candidate.chunkId(), candidate);
        }
        for (String chunkId : vectors.searchKnowledgeIds(query, 40)) {
            if (!merged.containsKey(chunkId)) {
                for (RetrievalRanker.Candidate candidate : repository.chunksByIds(List.of(chunkId))) {
                    merged.putIfAbsent(candidate.chunkId(), candidate);
                }
            }
        }
        List<RetrievalRanker.Candidate> ranked = RetrievalRanker.rank(
                query == null ? "" : query, new ArrayList<>(merged.values()), topK <= 0 ? 5 : topK);
        List<Citation> citations = new ArrayList<>(ranked.size());
        for (RetrievalRanker.Candidate candidate : ranked) {
            citations.add(new Citation(candidate.chunkId(), candidate.title(),
                    DocumentSplitter.snippet(candidate.snippet(), DocumentSplitter.SNIPPET_RUNES),
                    candidate.source()));
        }
        return citations;
    }
}
