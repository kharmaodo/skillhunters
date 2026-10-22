package com.skillhunters;

import com.skillhunters.search.HybridSearchService;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HybridMathTest {
    @Test void lexicalTokensDoNotTurnJavaScriptIntoJava() {
        assertThat(HybridSearchService.lexical("Java","JavaScript")).isFalse();
        assertThat(HybridSearchService.lexical("Spring Boot","Spring Framework")).isTrue();
        assertThat(HybridSearchService.lexical("C++","C#")).isFalse();
    }
    @Test void malformedOrUnnormalizedVectorsAreRejected() {
        assertThatThrownBy(()->HybridSearchService.validateVectors(List.of(List.of(1.0)),1)).isInstanceOf(IllegalArgumentException.class);
        var zero=new ArrayList<>(Collections.nCopies(384,0.0));
        assertThatThrownBy(()->HybridSearchService.validateVectors(List.of(zero),1)).isInstanceOf(IllegalArgumentException.class);
        zero.set(0,Double.NaN);
        assertThatThrownBy(()->HybridSearchService.validateVectors(List.of(zero),1)).isInstanceOf(IllegalArgumentException.class);
        zero.set(0,1.0);HybridSearchService.validateVectors(List.of(zero),1);
        assertThat(HybridSearchService.cosine(zero,zero)).isEqualTo(1);
    }
}
