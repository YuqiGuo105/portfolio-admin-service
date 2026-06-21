package site.yuqi.ragindexer.rag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a long text into overlapping character-bounded chunks. Char-based
 * chunking is good enough for English/Chinese mixed content; ~4 chars ~ 1
 * token for English, so the default 2000-char max stays under the 8192-token
 * input limit of text-embedding-3-small with plenty of headroom.
 */
@Component
public class ContentChunker {

    @Value("${portfolio.rag.chunk.max-chars:2000}")
    private int maxChars;

    @Value("${portfolio.rag.chunk.overlap-chars:200}")
    private int overlap;

    public List<String> chunk(String text) {
        if (text == null || text.isBlank()) return List.of();
        String trimmed = text.strip();
        if (trimmed.length() <= maxChars) return List.of(trimmed);

        List<String> out = new ArrayList<>();
        int len = trimmed.length();
        int step = Math.max(1, maxChars - overlap);
        for (int start = 0; start < len; start += step) {
            int end = Math.min(len, start + maxChars);
            out.add(trimmed.substring(start, end));
            if (end >= len) break;
        }
        return out;
    }
}
