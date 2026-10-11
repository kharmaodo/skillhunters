package com.skillhunters.documents;

import java.util.List;

/** Internal worker port. Its text is untrusted and never indexed in SH-07. */
public interface DocumentAnalyzer {
    record Block(String source, String text) {}
    record Result(String state, String code, List<Block> blocks, List<Integer> ocrPages,
                  String parserVersion, String avVersion) {}
    Result analyze(byte[] data, String mediaType, String sha256) throws Exception;
}
