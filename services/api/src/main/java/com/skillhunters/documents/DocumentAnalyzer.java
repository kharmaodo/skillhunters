package com.skillhunters.documents;

import java.util.List;

/** Internal worker port. Its text is untrusted and never indexed in SH-07. */
public interface DocumentAnalyzer {
    record Block(String source, String text) {}
    record OcrPage(int page, Double confidence, boolean reviewRequired, long durationMs,
                   String engineVersion, String rendererVersion, String modelVersion, List<String> warnings) {}
    record Result(String state, String code, List<Block> blocks, List<Integer> ocrPages,
                  String parserVersion, String avVersion, List<OcrPage> ocr) {
        public Result(String state, String code, List<Block> blocks, List<Integer> ocrPages, String parserVersion, String avVersion) {
            this(state,code,blocks,ocrPages,parserVersion,avVersion,List.of());
        }
    }
    Result analyze(byte[] data, String mediaType, String sha256) throws Exception;
}
