package com.skillhunters.search;

import java.util.List;

/** Receives canonical professional dictionary labels only, never CV text or identity. */
public interface EmbeddingPort {
    String MODEL="minilm-multilingual-e8f8c211-quint8-avx2-mean-l2-v1";
    int DIMENSIONS=384;
    List<List<Double>> encode(List<String> labels) throws Exception;
}
