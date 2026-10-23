package com.skillhunters;

import com.skillhunters.search.ProfessionalTextProjection;
import com.skillhunters.documents.StructuredExtractor.Field;
import com.skillhunters.taxonomy.TaxonomyService;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProfessionalTextProjectionTest {
    private final ProfessionalTextProjection projection=new ProfessionalTextProjection(new TaxonomyService.Dictionary(1,List.of(
        new TaxonomyService.Skill(UUID.randomUUID(),"Java","Language",List.of("JDK")),
        new TaxonomyService.Skill(UUID.randomUUID(),"Spring Boot","Framework",List.of()),
        new TaxonomyService.Skill(UUID.randomUUID(),"C++","Language",List.of())
    )));
    @Test void onlyApprovedVocabularySurvivesProfessionalNarratives() {
        assertThat(projection.labels(Field.EXPERIENCE,"Mission de migration JDK et Spring Boot chez Exemple, 2024.")).containsExactlyInAnyOrder("Java","Spring Boot");
        assertThat(projection.labels(Field.TITLE,"Développeur JavaScript C#")).isEmpty();
        assertThat(projection.labels(Field.EDUCATION,"Formation C++.")).containsExactly("C++");
        assertThat(projection.labels(Field.CERTIFICATION_DECLARATION,"Examen Java préparé")).containsExactly("Java");
    }
    @Test void excludedFieldsAndSensitiveClausesCannotSupplyMentions() {
        for(var field:List.of(Field.FULL_NAME,Field.LANGUAGE,Field.AVAILABILITY,Field.DECLARED_LEVEL))
            assertThat(projection.labels(field,"Java")).isEmpty();
        assertThat(projection.labels(Field.EXPERIENCE,"Loisirs : Java")).isEmpty();
        assertThat(projection.labels(Field.EXPERIENCE,"Religion et Java")).isEmpty();
    }
}
